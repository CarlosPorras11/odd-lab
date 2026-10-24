package dev.oddlab.cli;

import dev.oddlab.memory.infrastructure.SqliteRunJournal;
import dev.oddlab.runtime.application.RuntimeControl.ProcessIdentity;
import dev.oddlab.runtime.infrastructure.LocalProcessSupervisor;
import dev.oddlab.work.domain.*;
import dev.oddlab.workspace.infrastructure.LocalProjectAccess;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ControlCommandsIT {
    @TempDir Path temporary;
    private Path root;
    private Path request;
    private UUID repositoryId;
    private final SqliteRunJournal journal = new SqliteRunJournal();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Process> launched = new ArrayList<>();
    private int sequence;
    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("repo"));
        var git = new ProcessBuilder("git", "-C", root.toString(), "init", "--quiet").start();
        assertThat(git.waitFor(10, TimeUnit.SECONDS)).isTrue(); assertThat(git.exitValue()).isZero();
        assertThat(invoke("init", "--repo", root.toString(), "--json").code()).isZero();
        repositoryId = new LocalProjectAccess().load(root).orElseThrow().repositoryId();
        request = temporary.resolve("request.md"); Files.writeString(request, "Petición sintética, sin órdenes ejecutables.\n");
    }
    @AfterEach void cleanup() {
        try {
            for (var run : journal.list(root, repositoryId)) new LocalProcessSupervisor().stopAndConfirm(identities(journal.inspect(root, repositoryId, run.runId())));
        } catch (Exception ignored) { }
        for (var process : launched) if (process.isAlive()) { process.destroyForcibly(); try { process.waitFor(5, TimeUnit.SECONDS); } catch (Exception ignored) { } }
    }

    @Test void anotherOwnerIsExcludedAndCancelConfirmsRealParentAndChildStop() throws Exception {
        var owner = startRun("10");
        var active = awaitChild(owner);
        var rejected = invoke("resume", "--repo", root.toString(), "--run", active.run().runId().toString(), "--json");
        assertThat(rejected.code()).isEqualTo(5);
        assertThat(json.readTree(rejected.stdout()).at("/error/code").asString()).isEqualTo("RUN_OWNED");
        var cancelled = control("cancel", active.run().runId());
        assertThat(cancelled.code()).isZero();
        assertThat(json.readTree(cancelled.stdout()).path("state").asString()).isEqualTo("CANCELLED");
        assertThat(owner.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(owner.process().exitValue()).isEqualTo(4);
        assertThat(new LocalProcessSupervisor().stopped(identities(active))).isTrue();
        assertThat(starts(snapshot(active))).isEqualTo(1);
        assertThat(Files.readString(owner.stderr())).isEmpty();
    }

    @Test void pauseStopsTheTreeAndResumeConsumesNextAttempt() throws Exception {
        var owner = startRun("3"); var active = awaitChild(owner);
        assertThat(control("pause", active.run().runId()).code()).isZero();
        assertThat(owner.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(owner.process().exitValue()).isEqualTo(4);
        assertThat(snapshot(active).run().state()).isEqualTo(RunState.PAUSED);
        assertThat(new LocalProcessSupervisor().stopped(identities(active))).isTrue();
        var resumed = invoke("resume", "--repo", root.toString(), "--run", active.run().runId().toString(), "--json");
        assertThat(resumed.code()).isZero();
        assertThat(json.readTree(resumed.stdout()).at("/result/verificationStatus").asString()).isEqualTo("VALID");
        assertThat(starts(snapshot(active))).isEqualTo(2);
    }

    @Test void killedOwnerDoesNotAuthorizeReplayAndResumeReconcilesOrphansFirst() throws Exception {
        var owner = startRun("5"); var active = awaitChild(owner);
        owner.process().destroyForcibly();
        assertThat(owner.process().waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(new LocalProcessSupervisor().stopped(identities(active))).isFalse();
        var recovered = invoke("resume", "--repo", root.toString(), "--run", active.run().runId().toString(), "--json");
        assertThat(recovered.code()).isEqualTo(4);
        assertThat(snapshot(active).run().state()).isEqualTo(RunState.PAUSED);
        assertThat(starts(snapshot(active))).isEqualTo(1);
        assertThat(new LocalProcessSupervisor().stopped(identities(active))).isTrue();
        assertThat(invoke("resume", "--repo", root.toString(), "--run", active.run().runId().toString(), "--json").code()).isZero();
        assertThat(starts(snapshot(active))).isEqualTo(2);
    }

    @Test void terminalSignalRequestsPauseAndConfirmsDescendants() throws Exception {
        var owner = startRun("10"); var active = awaitChild(owner);
        owner.process().destroy();
        assertThat(owner.process().waitFor(12, TimeUnit.SECONDS)).isTrue();
        assertThat(snapshot(active).run().state()).isEqualTo(RunState.PAUSED);
        assertThat(new LocalProcessSupervisor().stopped(identities(active))).isTrue();
        assertThat(starts(snapshot(active))).isEqualTo(1);
    }

    @Test void persistedProcessResultAndOrphanReportRecoverWithoutAnotherAttempt() throws Exception {
        try (var db = database(); var statement = db.createStatement()) {
            statement.execute("CREATE TRIGGER fail_completion BEFORE INSERT ON run_events WHEN NEW.type='STATE_CHANGED' AND NEW.state='COMPLETED' BEGIN SELECT RAISE(ABORT, 'synthetic crash boundary'); END");
        }
        var owner = startRun("1");
        assertThat(owner.process().waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(owner.process().exitValue()).isEqualTo(1);
        var run = journal.list(root, repositoryId).getFirst();
        var saved = journal.inspect(root, repositoryId, run.runId());
        assertThat(saved.run().state()).isEqualTo(RunState.VERIFYING);
        assertThat(new LocalProcessSupervisor().stopped(identities(saved))).isTrue();
        try (var db = database(); var statement = db.createStatement()) { statement.execute("DROP TRIGGER fail_completion"); }
        var recovered = invoke("resume", "--repo", root.toString(), "--run", run.runId().toString(), "--json");
        assertThat(recovered.code()).isZero();
        assertThat(starts(journal.inspect(root, repositoryId, run.runId()))).isEqualTo(1);
        assertThat(json.readTree(recovered.stdout()).at("/result/verificationStatus").asString()).isEqualTo("VALID");
    }

    @Test void ownerlessRequestIsVisibleAndRemainsUnconfirmedUntilReconciled() throws Exception {
        var result = invoke("run", "--repo", root.toString(), "--request-file", request.toString(), "--runtime", "simulated", "--scenario", "decision", "--json");
        UUID id = UUID.fromString(json.readTree(result.stdout()).path("runId").asString());
        var pending = invoke("cancel", "--repo", root.toString(), "--run", id.toString(), "--wait-seconds", "0", "--json");
        assertThat(pending.code()).isEqualTo(5);
        var inspected = invoke("inspect", "--repo", root.toString(), "--run", id.toString(), "--json");
        assertThat(json.readTree(inspected.stdout()).at("/result/controlRequests/0/status").asString()).isEqualTo("PENDING");
        assertThat(invoke("resume", "--repo", root.toString(), "--run", id.toString(), "--json").code()).isEqualTo(4);
        var after = invoke("inspect", "--repo", root.toString(), "--run", id.toString(), "--json");
        assertThat(json.readTree(after.stdout()).at("/result/controlRequests/0/status").asString()).isEqualTo("CONFIRMED");
        assertThat(json.readTree(after.stdout()).path("pendingDecision").isNull()).isTrue();
    }

    private java.sql.Connection database() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + root.resolve(".odd-lab/state/journal.sqlite")); }
    private RunSnapshot snapshot(RunSnapshot previous) { return journal.inspect(root, repositoryId, previous.run().runId()); }
    private long starts(RunSnapshot snapshot) { return snapshot.entries().stream().filter(entry -> "ATTEMPT_START".equals(entry.data().get("recordType"))).count(); }
    private List<ProcessIdentity> identities(RunSnapshot snapshot) {
        return snapshot.entries().stream().filter(entry -> "PROCESS_STARTED".equals(entry.data().get("recordType")))
                .map(entry -> new ProcessIdentity(Long.parseLong(entry.data().get("pid")), entry.data().get("startedAt"))).toList();
    }
    private RunSnapshot awaitChild(Invocation owner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            var runs = journal.list(root, repositoryId);
            if (!runs.isEmpty()) {
                var snapshot = journal.inspect(root, repositoryId, runs.getFirst().runId());
                if (identities(snapshot).size() == 2) return snapshot;
            }
            if (!owner.process().isAlive()) fail("Proceso terminado antes del handshake: " + Files.readString(owner.stdout()) + Files.readString(owner.stderr()));
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No se registró el hijo");
    }
    private Invocation startRun(String seconds) throws Exception {
        return start("run", "--repo", root.toString(), "--request-file", request.toString(), "--runtime", "process", "--process-seconds", seconds, "--json");
    }
    private Result control(String action, UUID id) throws Exception { return invoke(action, "--repo", root.toString(), "--run", id.toString(), "--wait-seconds", "10", "--json"); }
    private Invocation start(String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", Path.of(System.getProperty("oddLab.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Path out = temporary.resolve("out-" + sequence), err = temporary.resolve("err-" + sequence++);
        var process = new ProcessBuilder(command).directory(temporary.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        launched.add(process); return new Invocation(process, out, err);
    }
    private Result invoke(String... args) throws Exception {
        var invocation = start(args);
        assertThat(invocation.process().waitFor(30, TimeUnit.SECONDS)).isTrue();
        var stdout = Files.readString(invocation.stdout());
        assertThat(Files.readString(invocation.stderr())).isEmpty();
        assertThat(stdout.lines().count()).isEqualTo(1);
        assertThat(json.readTree(stdout).path("schemaVersion").asInt()).isEqualTo(1);
        return new Result(invocation.process().exitValue(), stdout);
    }
    private record Invocation(Process process, Path stdout, Path stderr) {}
    private record Result(int code, String stdout) {}
}
