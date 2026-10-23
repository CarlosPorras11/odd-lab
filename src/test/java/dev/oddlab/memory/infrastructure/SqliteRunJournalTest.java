package dev.oddlab.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.work.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteRunJournalTest {
    @TempDir
    Path repository;
    private final UUID repositoryId = UUID.randomUUID();
    private final SqliteRunJournal journal = new SqliteRunJournal();

    @BeforeEach
    void layout() throws Exception {
        Files.createDirectories(repository.resolve(".odd-lab/state"));
    }

    @Test
    void initializesV1AndRepeatedInitializationDoesNotRewriteDatabase() throws Exception {
        journal.initialize(repository, repositoryId);
        byte[] before = Files.readAllBytes(database());
        var modified = Files.getLastModifiedTime(database());
        journal.initialize(repository, repositoryId);
        journal.check(repository, repositoryId);
        assertThat(journal.list(repository, repositoryId)).isEmpty();
        assertThat(Files.readAllBytes(database())).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(database())).isEqualTo(modified);
        assertThat(sqlInt("PRAGMA user_version")).isEqualTo(1);
    }

    @Test
    void queriesDoNotCreateMissingDatabaseAndInitUpgradesEmptyV0() throws Exception {
        assertError(() -> journal.list(repository, repositoryId), "STORAGE_NOT_INITIALIZED");
        assertThat(database()).doesNotExist();
        Files.createFile(database());
        assertError(() -> journal.list(repository, repositoryId), "STORAGE_NOT_INITIALIZED");
        assertThat(Files.size(database())).isZero();
        journal.initialize(repository, repositoryId);
        journal.check(repository, repositoryId);
    }

    @Test
    void persistsOriginalRequestPolicyRecordsAndEventsAcrossAdapterInstances() {
        journal.initialize(repository, repositoryId);
        RunRecord run = run();
        journal.create(repository, run);
        long version = 0;
        for (var kind : RunEntry.Kind.values()) {
            Map<String, String> data = kind == RunEntry.Kind.EVIDENCE ? Map.of("location", "artifacts/report.txt",
                    "revision", "abc123", "sha256", "a".repeat(64)) : Map.of("text", "contenido de " + kind);
            journal.append(repository, repositoryId, run.runId(), version++, new RunEntry(UUID.randomUUID(), kind, 1, kind.name(), data), "carlos");
        }
        journal.transition(repository, repositoryId, run.runId(), version++, RunState.FAILED, "carlos", "Fallo sintético registrado");
        var replacement = new SqliteRunJournal();
        var snapshot = replacement.inspect(repository, repositoryId, run.runId());
        assertThat(snapshot.run().request()).isEqualTo(run.request());
        assertThat(snapshot.run().requestSha256()).isEqualTo(run.requestSha256());
        assertThat(snapshot.run().policy()).isEqualTo(run.policy());
        assertThat(snapshot.run().baseRevision()).isEqualTo("abc123");
        assertThat(snapshot.run().state()).isEqualTo(RunState.FAILED);
        assertThat(snapshot.run().version()).isEqualTo(version);
        assertThat(snapshot.entries()).hasSize(4);
        assertThat(snapshot.events()).hasSize((int) version + 1);
        assertThat(snapshot.events().getLast().detail()).isEqualTo("Fallo sintético registrado");
        replacement.check(repository, repositoryId);
    }

    @Test
    void staleVersionAndIllegalTransitionLeaveStateAndEventsIntact() {
        journal.initialize(repository, repositoryId);
        var run = run();
        journal.create(repository, run);
        journal.transition(repository, repositoryId, run.runId(), 0, RunState.EXPLORING, "carlos", "Explorar");
        var before = journal.inspect(repository, repositoryId, run.runId());
        assertError(() -> journal.transition(repository, repositoryId, run.runId(), 0, RunState.READY, "carlos", "Obsoleto"), "RUN_VERSION_CONFLICT");
        assertError(() -> journal.transition(repository, repositoryId, run.runId(), 1, RunState.RUNNING, "carlos", "Salto inválido"), "INVALID_STATE_TRANSITION");
        assertThat(journal.inspect(repository, repositoryId, run.runId())).isEqualTo(before);
    }

    @Test
    void rollsBackInitialRunWhenItsEventCannotBeSaved() throws Exception {
        journal.initialize(repository, repositoryId);
        failEvents();
        assertError(() -> journal.create(repository, run()), "STORAGE_FAILURE");
        assertThat(sqlInt("SELECT count(*) FROM runs")).isZero();
        assertThat(sqlInt("SELECT count(*) FROM run_events")).isZero();
    }

    @Test
    void rollsBackStateWhenItsEventCannotBeSaved() throws Exception {
        journal.initialize(repository, repositoryId);
        var run = run();
        journal.create(repository, run);
        var before = journal.inspect(repository, repositoryId, run.runId());
        failEvents();
        assertError(() -> journal.transition(repository, repositoryId, run.runId(), 0, RunState.EXPLORING, "carlos", "Explorar"), "STORAGE_FAILURE");
        assertThat(journal.inspect(repository, repositoryId, run.runId())).isEqualTo(before);
    }

    @Test
    void rollsBackEntryAndVersionWhenItsEventCannotBeSaved() throws Exception {
        journal.initialize(repository, repositoryId);
        var run = run();
        journal.create(repository, run);
        failEvents();
        assertError(() -> journal.append(repository, repositoryId, run.runId(), 0, entry(), "carlos"), "STORAGE_FAILURE");
        var snapshot = journal.inspect(repository, repositoryId, run.runId());
        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.run().version()).isZero();
        assertThat(snapshot.events()).hasSize(1);
    }

    @Test
    void duplicateEntryCannotAdvanceVersionOrAppendAnotherEvent() {
        journal.initialize(repository, repositoryId);
        var run = run();
        var entry = entry();
        journal.create(repository, run);
        journal.append(repository, repositoryId, run.runId(), 0, entry, "carlos");
        var before = journal.inspect(repository, repositoryId, run.runId());
        assertError(() -> journal.append(repository, repositoryId, run.runId(), 1, entry, "carlos"), "STORAGE_FAILURE");
        assertThat(journal.inspect(repository, repositoryId, run.runId())).isEqualTo(before);
    }

    @Test
    void refusesOtherScopeEvenWhenDatabaseWasCopied() throws Exception {
        journal.initialize(repository, repositoryId);
        journal.create(repository, run());
        UUID other = UUID.randomUUID();
        byte[] before = Files.readAllBytes(database());
        assertError(() -> journal.list(repository, other), "STORAGE_SCOPE_MISMATCH");
        assertError(() -> journal.initialize(repository, other), "STORAGE_SCOPE_MISMATCH");
        assertError(() -> journal.create(repository, new RunRecord(UUID.randomUUID(), other, "actor", "otro ámbito", "abc123",
                RepositoryPolicy.initial(), RunState.CREATED, 0, Instant.EPOCH, Instant.EPOCH)), "STORAGE_SCOPE_MISMATCH");
        assertThat(Files.readAllBytes(database())).isEqualTo(before);
    }

    @Test
    void rejectsFutureVersionWithoutMigratingOrOverwritingIt() throws Exception {
        journal.initialize(repository, repositoryId);
        sql("PRAGMA user_version = 42");
        byte[] before = Files.readAllBytes(database());
        assertError(() -> journal.list(repository, repositoryId), "STORAGE_VERSION_UNSUPPORTED");
        assertError(() -> journal.initialize(repository, repositoryId), "STORAGE_VERSION_UNSUPPORTED");
        assertThat(Files.readAllBytes(database())).isEqualTo(before);
    }

    @Test
    void rejectsUnrelatedDatabaseInsteadOfCreatingTablesInIt() throws Exception {
        sql("CREATE TABLE private_data (value TEXT)");
        byte[] before = Files.readAllBytes(database());
        assertError(() -> journal.initialize(repository, repositoryId), "STORAGE_FOREIGN_DATABASE");
        assertThat(Files.readAllBytes(database())).isEqualTo(before);
    }

    @Test
    void rejectsCorruptFileWithoutReplacingIt() throws Exception {
        Files.writeString(database(), "Esto no es SQLite");
        assertError(() -> journal.initialize(repository, repositoryId), "STORAGE_FAILURE");
        assertThat(Files.readString(database())).isEqualTo("Esto no es SQLite");
    }

    @Test
    void detectsChangedRequestAndMissingEvent() throws Exception {
        journal.initialize(repository, repositoryId);
        var run = run();
        journal.create(repository, run);
        sql("UPDATE runs SET request = 'alterada'");
        assertError(() -> journal.inspect(repository, repositoryId, run.runId()), "STORAGE_CORRUPT");
        sql("UPDATE runs SET request_sha256 = '" + new RunRecord(run.runId(), repositoryId, run.actor(), "alterada", run.baseRevision(),
                run.policy(), run.state(), 0, run.createdAt(), run.updatedAt()).requestSha256() + "'");
        journal.append(repository, repositoryId, run.runId(), 0, entry(), "carlos");
        sql("DELETE FROM run_entries");
        assertError(() -> journal.inspect(repository, repositoryId, run.runId()), "STORAGE_CORRUPT");
        sql("DELETE FROM run_events");
        assertError(() -> journal.check(repository, repositoryId), "STORAGE_CORRUPT");
    }

    @Test
    void rejectsEvidenceWithoutRevisionAndHashAndUnknownRun() {
        journal.initialize(repository, repositoryId);
        assertThatThrownBy(() -> new RunEntry(UUID.randomUUID(), RunEntry.Kind.EVIDENCE, 1, "Sin procedencia", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertError(() -> journal.inspect(repository, repositoryId, UUID.randomUUID()), "RUN_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"created_at = 'not-an-instant'", "policy_json = 'null'", "policy_json = '{}'", "policy_json = '{malformed'"})
    void reportsMalformedStoredValuesAsCorruption(String assignment) throws Exception {
        journal.initialize(repository, repositoryId);
        journal.create(repository, run());
        sql("UPDATE runs SET " + assignment);
        assertError(() -> journal.check(repository, repositoryId), "STORAGE_CORRUPT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-journal", "-wal", "-shm"})
    void refusesDatabaseAndSidecarSymlinks(String suffix) throws Exception {
        Path outside = Files.createTempFile("odd-ledger-external-", ".txt");
        try {
            Files.writeString(outside, "intacto");
            Files.createSymbolicLink(database().resolveSibling(SqliteRunJournal.DATABASE + suffix), outside);
            assertError(() -> journal.initialize(repository, repositoryId), "UNSAFE_STORAGE_PATH");
            assertThat(Files.readString(outside)).isEqualTo("intacto");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "PAUSED", "CANCELLED"})
    void cannotAssertSuccessOrStoppedProcessesWithoutDedicatedProof(String state) {
        assertError(() -> RunState.VERIFYING.requireTransitionTo(RunState.valueOf(state)), "STATE_PROOF_REQUIRED");
    }

    private RunRecord run() {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        return new RunRecord(UUID.randomUUID(), repositoryId, "carlos", "Petición original\nCon acentos: revisión.\n", "abc123",
                RepositoryPolicy.initial(), RunState.CREATED, 0, now, now);
    }

    @Test
    void decisionAndStateRollBackTogetherWhenStateEventFails() throws Exception {
        journal.initialize(repository, repositoryId);
        var run = run();
        journal.create(repository, run);
        journal.transition(repository, repositoryId, run.runId(), 0, RunState.EXPLORING, "test", "Explorar");
        var before = journal.inspect(repository, repositoryId, run.runId());
        sql("CREATE TRIGGER fail_state BEFORE INSERT ON run_events WHEN NEW.type = 'STATE_CHANGED' BEGIN SELECT RAISE(ABORT, 'synthetic'); END");
        var decision = new RunEntry(UUID.randomUUID(), RunEntry.Kind.DECISION, 1, "Pendiente", Map.of("status", "PENDING"));
        assertError(() -> journal.appendAndTransition(repository, repositoryId, run.runId(), 1, decision, RunState.WAITING_INPUT, "test", "Esperar"), "STORAGE_FAILURE");
        assertThat(journal.inspect(repository, repositoryId, run.runId())).isEqualTo(before);
    }

    @Test
    void completionNeedsScopedProofAndRollsBackEvidenceIfFinalEventFails() throws Exception {
        journal.initialize(repository, repositoryId);
        var run = run();
        journal.create(repository, run);
        journal.append(repository, repositoryId, run.runId(), 0, new RunEntry(UUID.randomUUID(), RunEntry.Kind.REQUIREMENT, 1,
                "Criterio", Map.of("criterion", "SIMULATED_RECEIPT")), "test");
        UUID task = UUID.randomUUID();
        journal.append(repository, repositoryId, run.runId(), 1, new RunEntry(task, RunEntry.Kind.TASK, 1, "Tarea", Map.of("recordType", "TASK")), "test");
        long version = 2;
        for (RunState state : List.of(RunState.EXPLORING, RunState.READY, RunState.RUNNING, RunState.VERIFYING)) {
            journal.transition(repository, repositoryId, run.runId(), version++, state, "test", "Preparar fixture");
        }
        var before = journal.inspect(repository, repositoryId, run.runId());
        var data = new java.util.HashMap<>(Map.of("location", "artifacts/receipt.json", "sha256", "b".repeat(64),
                "revision", run.baseRevision(), "scope", "SIMULATION", "verified", "false", "requestSha256", run.requestSha256(), "taskId", task.toString()));
        long expected = version;
        assertError(() -> journal.completeSimulation(repository, repositoryId, run.runId(), expected,
                new RunEntry(UUID.randomUUID(), RunEntry.Kind.EVIDENCE, 1, "Sin prueba", data), "test"), "STATE_PROOF_REQUIRED");
        data.put("verified", "true");
        sql("CREATE TRIGGER fail_complete BEFORE INSERT ON run_events WHEN NEW.state = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'synthetic'); END");
        assertError(() -> journal.completeSimulation(repository, repositoryId, run.runId(), expected,
                new RunEntry(UUID.randomUUID(), RunEntry.Kind.EVIDENCE, 1, "Prueba sintética", data), "test"), "STORAGE_FAILURE");
        assertThat(journal.inspect(repository, repositoryId, run.runId())).isEqualTo(before);
    }

    private RunEntry entry() {
        return new RunEntry(UUID.randomUUID(), RunEntry.Kind.REQUIREMENT, 1, "R-01", Map.of("text", "Preservar requisitos"));
    }

    private Path database() {
        return repository.resolve(".odd-lab/state").resolve(SqliteRunJournal.DATABASE);
    }

    private void failEvents() throws Exception {
        sql("CREATE TRIGGER fail_event BEFORE INSERT ON run_events BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
    }

    private void sql(String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database()); var statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private int sqlInt(String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database()); var statement = connection.createStatement();
             var result = statement.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private void assertError(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(JournalException.class)
                .extracting(exception -> ((JournalException) exception).code()).isEqualTo(code);
    }
}
