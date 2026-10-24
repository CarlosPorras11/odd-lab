package dev.oddlab.memory.infrastructure;

import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.work.application.RunJournal;
import dev.oddlab.work.domain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class SqliteRunJournal implements RunJournal {
    public static final String DATABASE = "journal.sqlite";
    private static final int APPLICATION_ID = 0x4f44444c;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String migration;
    private final String migrationHash;

    public SqliteRunJournal() {
        try (var input = getClass().getResourceAsStream("/db/migration/V001__run_journal.sql")) {
            if (input == null) throw new IllegalStateException("No se encuentra la migración del registro");
            byte[] bytes = input.readAllBytes();
            migration = new String(bytes, StandardCharsets.UTF_8);
            migrationHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo cargar la migración", exception);
        }
    }

    @Override
    public void initialize(Path repository, UUID repositoryId) {
        Path database = location(repository, false);
        if (Files.exists(database)) {
            try (var connection = connect(database, "ro")) {
                int version = pragma(connection, "user_version");
                if (version != 0) {
                    validate(connection, repositoryId);
                    return;
                }
                requireEmptyDatabase(connection);
            } catch (SQLException exception) {
                throw storageFailure(exception);
            }
        }
        try (var connection = connect(database, "rwc")) {
            connection.setAutoCommit(false);
            try {
                requireEmptyDatabase(connection);
                try (var statement = connection.createStatement()) {
                    for (String sql : migration.split(";")) if (!sql.isBlank()) statement.execute(sql);
                    statement.execute("PRAGMA application_id = " + APPLICATION_ID);
                    statement.execute("PRAGMA user_version = 1");
                }
                try (var statement = connection.prepareStatement("INSERT INTO journal_metadata VALUES (1, ?, ?)")) {
                    statement.setString(1, repositoryId.toString());
                    statement.setString(2, migrationHash);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure(exception);
        }
    }

    @Override
    public void check(Path repository, UUID repositoryId) {
        read(repository, repositoryId, connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !result.getString(1).equals("ok")) throw corrupt();
            }
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (result.next()) throw corrupt();
            }
            try (var statement = connection.prepareStatement("SELECT run_id FROM runs WHERE repository_id = ?")) {
                statement.setString(1, repositoryId.toString());
                try (var result = statement.executeQuery()) {
                    while (result.next()) snapshot(connection, repositoryId, UUID.fromString(result.getString(1)));
                }
            }
            return null;
        });
    }

    @Override
    public List<RunRecord> list(Path repository, UUID repositoryId) {
        return read(repository, repositoryId, connection -> {
            var runs = new ArrayList<RunRecord>();
            try (var statement = connection.prepareStatement("SELECT * FROM runs WHERE repository_id = ? ORDER BY created_at DESC, run_id")) {
                statement.setString(1, repositoryId.toString());
                try (var result = statement.executeQuery()) {
                    while (result.next()) runs.add(snapshot(connection, repositoryId, UUID.fromString(result.getString("run_id"))).run());
                }
            }
            return List.copyOf(runs);
        });
    }

    @Override
    public RunSnapshot inspect(Path repository, UUID repositoryId, UUID runId) {
        return read(repository, repositoryId, connection -> snapshot(connection, repositoryId, runId));
    }

    @Override
    public void create(Path repository, RunRecord run) {
        if (run.state() != RunState.CREATED || run.version() != 0 || !run.createdAt().equals(run.updatedAt())) {
            throw new JournalException("INVALID_INITIAL_RUN", "Una ejecución nueva debe empezar en CREATED y versión 0.", 2);
        }
        write(repository, run.repositoryId(), connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, run.runId().toString());
                statement.setString(2, run.repositoryId().toString());
                statement.setString(3, run.actor());
                statement.setString(4, run.request());
                statement.setString(5, run.requestSha256());
                statement.setString(6, run.baseRevision());
                statement.setString(7, json.writeValueAsString(run.policy()));
                statement.setString(8, run.state().name());
                statement.setLong(9, run.version());
                statement.setString(10, run.createdAt().toString());
                statement.setString(11, run.updatedAt().toString());
                statement.executeUpdate();
            }
            event(connection, run.runId(), 0, "CREATED", null, RunState.CREATED, run.actor(), "Petición registrada", run.createdAt());
            return null;
        });
    }

    @Override
    public void transition(Path repository, UUID repositoryId, UUID runId, long expectedVersion,
                           RunState target, String actor, String reason) {
        requireText(actor, reason);
        write(repository, repositoryId, connection -> {
            var current = snapshot(connection, repositoryId, runId).run();
            requireVersion(current, expectedVersion);
            current.state().requireTransitionTo(target);
            Instant now = nextTime(current);
            update(connection, current, expectedVersion, target, now);
            event(connection, runId, expectedVersion + 1, "STATE_CHANGED", current.state(), target, actor, reason, now);
            return null;
        });
    }

    @Override
    public void append(Path repository, UUID repositoryId, UUID runId, long expectedVersion, RunEntry entry, String actor) {
        requireText(actor);
        write(repository, repositoryId, connection -> {
            var current = snapshot(connection, repositoryId, runId).run();
            requireVersion(current, expectedVersion);
            if (current.state().terminal()) throw new JournalException("RUN_TERMINAL", "La ejecución está en estado terminal.", 2);
            insertEntry(connection, runId, entry);
            Instant now = nextTime(current);
            update(connection, current, expectedVersion, current.state(), now);
            event(connection, runId, expectedVersion + 1, "ENTRY_ADDED", current.state(), current.state(), actor, entry.id().toString(), now);
            return null;
        });
    }

    @Override
    public void appendAndTransition(Path repository, UUID repositoryId, UUID runId, long expectedVersion,
                                    RunEntry entry, RunState target, String actor, String reason) {
        requireText(actor, reason);
        write(repository, repositoryId, connection -> {
            var current = snapshot(connection, repositoryId, runId).run();
            requireVersion(current, expectedVersion);
            current.state().requireTransitionTo(target);
            appendTransition(connection, current, entry, target, actor, reason);
            return null;
        });
    }

    @Override
    public void completeSimulation(Path repository, UUID repositoryId, UUID runId, long expectedVersion, RunEntry evidence, String actor) {
        requireText(actor);
        write(repository, repositoryId, connection -> {
            var snapshot = snapshot(connection, repositoryId, runId);
            var current = snapshot.run();
            requireVersion(current, expectedVersion);
            if (current.state() != RunState.VERIFYING || evidence.kind() != RunEntry.Kind.EVIDENCE
                    || !"SIMULATION".equals(evidence.data().get("scope")) || !"true".equals(evidence.data().get("verified"))
                    || !current.requestSha256().equals(evidence.data().get("requestSha256"))
                    || !current.baseRevision().equals(evidence.data().get("revision"))
                    || snapshot.entries().stream().noneMatch(entry -> entry.kind() == RunEntry.Kind.REQUIREMENT
                            && "SIMULATED_RECEIPT".equals(entry.data().get("criterion")))
                    || snapshot.entries().stream().noneMatch(entry -> entry.kind() == RunEntry.Kind.TASK
                            && "TASK".equals(entry.data().get("recordType")) && entry.id().toString().equals(evidence.data().get("taskId")))) {
                throw new JournalException("STATE_PROOF_REQUIRED", "Falta evidencia de aceptación de la simulación.", 2);
            }
            appendTransition(connection, current, evidence, RunState.COMPLETED, actor, "Simulación verificada; no implementación de la petición");
            return null;
        });
    }

    @Override
    public void confirmStopped(Path repository, UUID repositoryId, UUID runId, long expectedVersion,
                               RunEntry proof, RunState target, String actor) {
        requireText(actor);
        write(repository, repositoryId, connection -> {
            var current = snapshot(connection, repositoryId, runId).run();
            requireVersion(current, expectedVersion);
            if (!((current.state() == RunState.PAUSING && target == RunState.PAUSED)
                    || (current.state() == RunState.CANCELLING && target == RunState.CANCELLED))
                    || proof.kind() != RunEntry.Kind.EVIDENCE || !"STOP_CONFIRMATION".equals(proof.data().get("recordType"))
                    || !"true".equals(proof.data().get("stopped")) || !runId.toString().equals(proof.data().get("runId"))
                    || !current.baseRevision().equals(proof.data().get("revision"))
                    || !(target == RunState.PAUSED ? "PAUSE" : "CANCEL").equals(proof.data().get("action"))) {
                throw new JournalException("STATE_PROOF_REQUIRED", "Falta confirmación de detención para esta ejecución.", 2);
            }
            appendTransition(connection, current, proof, target, actor, "Detención comprobada por el coordinador");
            return null;
        });
    }

    private void insertEntry(Connection connection, UUID runId, RunEntry entry) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO run_entries VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, runId.toString());
            statement.setString(2, entry.id().toString());
            statement.setString(3, entry.kind().name());
            statement.setInt(4, entry.schemaVersion());
            statement.setString(5, entry.title());
            statement.setString(6, json.writeValueAsString(entry.data()));
            statement.executeUpdate();
        }
    }

    private void appendTransition(Connection connection, RunRecord current, RunEntry entry, RunState target, String actor, String reason) throws SQLException {
        Instant now = nextTime(current);
        insertEntry(connection, current.runId(), entry);
        update(connection, current, current.version(), current.state(), now);
        event(connection, current.runId(), current.version() + 1, "ENTRY_ADDED", current.state(), current.state(), actor, entry.id().toString(), now);
        update(connection, current, current.version() + 1, target, now);
        event(connection, current.runId(), current.version() + 2, "STATE_CHANGED", current.state(), target, actor, reason, now);
    }

    private <T> T read(Path repository, UUID repositoryId, Operation<T> operation) {
        return transaction(repository, repositoryId, "ro", operation);
    }

    private <T> T write(Path repository, UUID repositoryId, Operation<T> operation) {
        return transaction(repository, repositoryId, "rw", operation);
    }

    private <T> T transaction(Path repository, UUID repositoryId, String mode, Operation<T> operation) {
        Path database = location(repository, true);
        try (var connection = connect(database, mode)) {
            connection.setAutoCommit(false);
            try {
                validate(connection, repositoryId);
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure(exception);
        } catch (IllegalArgumentException | DateTimeException | NullPointerException | tools.jackson.core.JacksonException exception) {
            throw corrupt();
        }
    }

    private Connection connect(Path database, String mode) throws SQLException {
        // URI encoding preserves spaces, #, ? and other path characters; no immutable=1 shortcut for concurrent readers.
        var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=" + mode);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            if (!mode.equals("ro")) statement.execute("PRAGMA synchronous = FULL");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private void validate(Connection connection, UUID repositoryId) throws SQLException {
        int version = pragma(connection, "user_version");
        if (version == 0) throw new JournalException("STORAGE_NOT_INITIALIZED", "Registro sin inicializar; ejecuta init --repo <ruta>.", 2);
        if (version != 1) throw new JournalException("STORAGE_VERSION_UNSUPPORTED", "Versión del registro no soportada; no se modificará.", 2);
        if (pragma(connection, "application_id") != APPLICATION_ID) throw corrupt();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT repository_id, migration_sha256 FROM journal_metadata WHERE singleton = 1")) {
            if (!result.next()) throw corrupt();
            if (!result.getString(1).equals(repositoryId.toString())) {
                throw new JournalException("STORAGE_SCOPE_MISMATCH", "El registro pertenece a otro repositorio; no se recuperarán sus datos.", 2);
            }
            if (!result.getString(2).equals(migrationHash)) throw corrupt();
        }
    }

    private void requireEmptyDatabase(Connection connection) throws SQLException {
        if (pragma(connection, "user_version") != 0 || pragma(connection, "application_id") != 0) {
            throw new JournalException("STORAGE_VERSION_UNSUPPORTED", "No se puede inicializar este registro sin revisar su versión.", 2);
        }
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
            if (result.next()) throw new JournalException("STORAGE_FOREIGN_DATABASE", "La base existente no es un registro vacío de odd-lab.", 2);
        }
    }

    private int pragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA " + name)) {
            if (!result.next()) throw corrupt();
            return result.getInt(1);
        }
    }

    private Path location(Path repository, boolean required) {
        for (Path directory : List.of(repository.resolve(".odd-lab"), repository.resolve(".odd-lab/state"))) {
            if (Files.isSymbolicLink(directory)) throw unsafe();
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new JournalException("STORAGE_NOT_INITIALIZED", "Falta el directorio de estado; ejecuta init --repo <ruta>.", 2);
            }
        }
        Path database = repository.resolve(".odd-lab/state").resolve(DATABASE);
        for (String suffix : List.of("", "-journal", "-wal", "-shm")) {
            Path file = database.resolveSibling(DATABASE + suffix);
            if (Files.isSymbolicLink(file) || (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) throw unsafe();
        }
        if (required && !Files.exists(database)) {
            throw new JournalException("STORAGE_NOT_INITIALIZED", "Falta el registro; ejecuta init --repo <ruta>.", 2);
        }
        return database;
    }

    private RunRecord find(Connection connection, UUID repositoryId, UUID runId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM runs WHERE run_id = ? AND repository_id = ?")) {
            statement.setString(1, runId.toString());
            statement.setString(2, repositoryId.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new JournalException("RUN_NOT_FOUND", "No se encuentra esa ejecución en este repositorio.", 2);
                return run(result);
            }
        }
    }

    private RunRecord run(ResultSet result) throws SQLException {
        var run = new RunRecord(UUID.fromString(result.getString("run_id")), UUID.fromString(result.getString("repository_id")),
                result.getString("actor"), result.getString("request"), result.getString("base_revision"),
                json.readValue(result.getString("policy_json"), RepositoryPolicy.class), RunState.valueOf(result.getString("state")),
                result.getLong("version"), Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")));
        if (!run.requestSha256().equals(result.getString("request_sha256"))) throw corrupt();
        return run;
    }

    private RunSnapshot snapshot(Connection connection, UUID repositoryId, UUID runId) throws SQLException {
        var run = find(connection, repositoryId, runId);
        var entries = new ArrayList<RunEntry>();
        try (var statement = connection.prepareStatement("SELECT * FROM run_entries WHERE run_id = ? ORDER BY kind, entry_id")) {
            statement.setString(1, runId.toString());
            try (var result = statement.executeQuery()) {
                while (result.next()) entries.add(new RunEntry(UUID.fromString(result.getString("entry_id")),
                        RunEntry.Kind.valueOf(result.getString("kind")), result.getInt("schema_version"), result.getString("title"),
                        json.readValue(result.getString("data_json"), new TypeReference<Map<String, String>>() {})));
            }
        }
        var events = new ArrayList<RunEvent>();
        try (var statement = connection.prepareStatement("SELECT * FROM run_events WHERE run_id = ? ORDER BY version")) {
            statement.setString(1, runId.toString());
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    String previous = result.getString("previous_state");
                    events.add(new RunEvent(UUID.fromString(result.getString("event_id")), result.getLong("version"), result.getString("type"),
                            previous == null ? null : RunState.valueOf(previous), RunState.valueOf(result.getString("state")),
                            result.getString("actor"), result.getString("detail"), Instant.parse(result.getString("occurred_at"))));
                }
            }
        }
        if (events.size() != run.version() + 1 || events.isEmpty() || events.getFirst().state() != RunState.CREATED
                || !events.getFirst().type().equals("CREATED") || events.getFirst().previousState() != null) throw corrupt();
        var registeredEntries = new HashSet<UUID>();
        var entryIds = entries.stream().map(RunEntry::id).collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < events.size(); index++) {
            var current = events.get(index);
            if (current.version() != index || (index > 0 && (current.previousState() != events.get(index - 1).state()
                    || current.occurredAt().isBefore(events.get(index - 1).occurredAt()) || current.type().equals("CREATED")))) throw corrupt();
            if (current.type().equals("ENTRY_ADDED")) {
                UUID entryId = UUID.fromString(current.detail());
                if (current.previousState() != current.state() || !entryIds.contains(entryId) || !registeredEntries.add(entryId)) throw corrupt();
            }
            if (current.type().equals("STATE_CHANGED") && current.previousState() == current.state()) throw corrupt();
        }
        if (events.getLast().state() != run.state() || !registeredEntries.equals(entryIds)
                || !events.getFirst().occurredAt().equals(run.createdAt()) || !events.getLast().occurredAt().equals(run.updatedAt())) throw corrupt();
        return new RunSnapshot(run, entries, events);
    }

    private void update(Connection connection, RunRecord run, long expectedVersion, RunState target, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE runs SET state = ?, version = version + 1, updated_at = ? WHERE run_id = ? AND repository_id = ? AND version = ?")) {
            statement.setString(1, target.name());
            statement.setString(2, now.toString());
            statement.setString(3, run.runId().toString());
            statement.setString(4, run.repositoryId().toString());
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) throw versionConflict();
        }
    }

    private void event(Connection connection, UUID runId, long version, String type, RunState previous,
                       RunState state, String actor, String detail, Instant occurredAt) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO run_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, runId.toString());
            statement.setString(2, UUID.randomUUID().toString());
            statement.setLong(3, version);
            statement.setString(4, type);
            statement.setString(5, previous == null ? null : previous.name());
            statement.setString(6, state.name());
            statement.setString(7, actor);
            statement.setString(8, detail);
            statement.setString(9, occurredAt.toString());
            statement.executeUpdate();
        }
    }

    private Instant nextTime(RunRecord run) {
        Instant now = Instant.now();
        return now.isBefore(run.updatedAt()) ? run.updatedAt() : now;
    }

    private void requireText(String... values) {
        if (Arrays.stream(values).anyMatch(value -> value == null || value.isBlank())) {
            throw new JournalException("INVALID_EVENT", "Actor y motivo del evento deben ser explícitos.", 2);
        }
    }

    private void requireVersion(RunRecord run, long expectedVersion) {
        if (run.version() != expectedVersion) throw versionConflict();
    }

    private JournalException versionConflict() {
        return new JournalException("RUN_VERSION_CONFLICT", "Versión obsoleta; vuelve a consultar la ejecución.", 2);
    }

    private JournalException unsafe() {
        return new JournalException("UNSAFE_STORAGE_PATH", "El registro y sus archivos auxiliares deben ser archivos locales, sin enlaces simbólicos.", 2);
    }

    private JournalException corrupt() {
        return new JournalException("STORAGE_CORRUPT", "Registro inconsistente o dañado; conserva los archivos y revisa su integridad.", 1);
    }

    private JournalException storageFailure(SQLException exception) {
        int code = exception.getErrorCode() & 0xff;
        return new JournalException(code == 5 || code == 6 ? "STORAGE_BUSY" : "STORAGE_FAILURE",
                code == 5 || code == 6 ? "Registro ocupado; reintenta cuando termine la operación activa."
                        : "No se pudo operar con el registro; revisa integridad, permisos y espacio sin borrar sus archivos.", 1);
    }

    @FunctionalInterface
    private interface Operation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
