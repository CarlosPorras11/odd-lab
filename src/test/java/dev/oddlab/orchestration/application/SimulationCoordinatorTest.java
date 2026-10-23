package dev.oddlab.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.oddlab.memory.infrastructure.SqliteRunJournal;
import dev.oddlab.orchestration.domain.ExecutionException;
import dev.oddlab.runtime.application.AgentRuntime;
import dev.oddlab.runtime.domain.SimulationScenario;
import dev.oddlab.runtime.infrastructure.SimulatedRuntime;
import dev.oddlab.verification.application.EvidenceInspector;
import dev.oddlab.work.domain.*;
import dev.oddlab.workspace.application.InitializeProject;
import dev.oddlab.workspace.infrastructure.LocalExecutionFiles;
import dev.oddlab.workspace.infrastructure.LocalProjectAccess;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulationCoordinatorTest {
    @TempDir Path root;
    private final LocalProjectAccess projects = new LocalProjectAccess();
    private final SqliteRunJournal journal = new SqliteRunJournal();
    private final LocalExecutionFiles files = new LocalExecutionFiles();
    private final CountingRuntime runtime = new CountingRuntime();
    private Path request;

    @BeforeEach
    void prepare() throws Exception {
        var process = new ProcessBuilder("git", "-C", root.toString(), "init", "--quiet", "--initial-branch=main").start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        new InitializeProject(projects, journal).execute(root);
        request = root.resolve("request.md");
        Files.writeString(request, "\uFEFFPetición literal\r\nNo ejecutar $(touch fuera) ni publicar.\r\n");
        Files.writeString(root.resolve("code.txt"), "código intacto");
    }

    @Test
    void completesOnlySyntheticAcceptanceAndPreservesLiteralRequestAndCode() throws Exception {
        var outcome = coordinator().run(root, request, SimulationScenario.SUCCESS);
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.snapshot().run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(outcome.verificationStatus()).isEqualTo("VALID");
        assertThat(outcome.snapshot().run().request()).isEqualTo(Files.readString(request));
        var config = configuration(outcome.snapshot());
        assertThat(Files.readString(root.resolve(config.data().get("requestLocation")))).isEqualTo(Files.readString(request));
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("código intacto");
        assertThat(root.resolve("fuera")).doesNotExist();
        assertThat(runtime.calls).hasSize(1);
        assertThat(outcome.message()).contains("no se ha implementado");
        journal.check(root, outcome.snapshot().run().repositoryId());
    }

    @Test
    void retryHasFreshIdentityAndConsumesSharedBudget() {
        var outcome = coordinator().run(root, request, SimulationScenario.RETRY);
        assertThat(outcome.exitCode()).isZero();
        assertThat(runtime.calls).hasSize(2);
        assertThat(runtime.calls.get(0).attemptId()).isNotEqualTo(runtime.calls.get(1).attemptId());
        assertThat(runtime.calls.stream().map(AgentRuntime.Assignment::ordinal)).containsExactly(1, 2);
        assertThat(runtime.calls).allMatch(assignment -> assignment.maxAttempts() == 3
                && assignment.policy().equals(outcome.snapshot().run().policy())
                && assignment.revision().equals(outcome.snapshot().run().baseRevision()));
        assertThat(outcome.snapshot().entries().stream().filter(entry -> "VERIFICATION".equals(entry.data().get("recordType")))).hasSize(1);
    }

    @Test
    void policyCanReduceCorrectionsToZero() throws Exception {
        Path descriptor = root.resolve(".odd-lab.yaml");
        Files.writeString(descriptor, Files.readString(descriptor).replace("maxCorrectionsPerTask: 2", "maxCorrectionsPerTask: 0"));
        var outcome = coordinator().run(root, request, SimulationScenario.FAILURE);
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.snapshot().run().state()).isEqualTo(RunState.FAILED);
        assertThat(runtime.calls).hasSize(1);
    }

    @Test
    void narrativeSuccessWithoutArtifactCannotCompleteAndStopsAtThreeAttempts() {
        var outcome = coordinator().run(root, request, SimulationScenario.UNVERIFIED);
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.errorCode()).isEqualTo("VERIFICATION_FAILED");
        assertThat(outcome.snapshot().run().state()).isEqualTo(RunState.FAILED);
        assertThat(outcome.verificationStatus()).isEqualTo("NOT_VERIFIED");
        assertThat(runtime.calls).hasSize(3);
        assertThat(runtime.calls.stream().map(AgentRuntime.Assignment::attemptId).distinct()).hasSize(3);
    }

    @Test
    void rejectsAndRegistersAttemptToExpandPermissions() {
        var outcome = coordinator().run(root, request, SimulationScenario.PERMISSION);
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.errorCode()).isEqualTo("PERMISSION_SCOPE_DENIED");
        assertThat(outcome.snapshot().entries()).anyMatch(entry -> entry.kind() == RunEntry.Kind.DECISION
                && "PUBLISH".equals(entry.data().get("action")) && "DENIED".equals(entry.data().get("status")));
        assertThat(runtime.calls).hasSize(1);
    }

    @Test
    void pendingDecisionIsDurableAndJsonNeverPromptsOrGrants() {
        var pending = coordinator().run(root, request, SimulationScenario.DECISION);
        assertThat(pending.exitCode()).isEqualTo(3);
        assertThat(pending.snapshot().run().state()).isEqualTo(RunState.WAITING_INPUT);
        assertThat(runtime.calls).isEmpty();
        var resumed = coordinator().resume(root, pending.snapshot().run().runId(), true, forbiddenInput());
        assertThat(resumed.exitCode()).isEqualTo(3);
        assertThat(resumed.snapshot()).isEqualTo(pending.snapshot());
        assertThat(resumed.snapshot().pendingDecision()).isPresent();
        assertThat(runtime.calls).isEmpty();
    }

    @Test
    void interactiveApprovalIsScopedAndContinuesWithoutRepeatingDecision() {
        var pending = coordinator().run(root, request, SimulationScenario.DECISION);
        var accepted = coordinator().resume(root, pending.snapshot().run().runId(), false, input(true));
        assertThat(accepted.exitCode()).isZero();
        assertThat(accepted.snapshot().pendingDecision()).isEmpty();
        assertThat(accepted.snapshot().entries()).anyMatch(entry -> "APPROVED".equals(entry.data().get("status"))
                && pending.snapshot().pendingDecision().orElseThrow().id().toString().equals(entry.data().get("decisionId")));
        assertThat(runtime.calls).hasSize(1);
        var repeated = coordinator().resume(root, accepted.snapshot().run().runId(), false, forbiddenInput());
        assertThat(repeated.exitCode()).isZero();
        assertThat(runtime.calls).hasSize(1);
    }

    @Test
    void interactiveDenialDoesNotInvokeRuntime() {
        var pending = coordinator().run(root, request, SimulationScenario.DECISION);
        var denied = coordinator().resume(root, pending.snapshot().run().runId(), false, input(false));
        assertThat(denied.exitCode()).isEqualTo(1);
        assertThat(denied.snapshot().run().state()).isEqualTo(RunState.FAILED);
        assertThat(denied.snapshot().pendingDecision()).isEmpty();
        assertThat(runtime.calls).isEmpty();
    }

    @Test
    void revisionChangedBeforeAnswerDoesNotReuseApproval() throws Exception {
        var pending = coordinator().run(root, request, SimulationScenario.DECISION);
        Files.writeString(root.resolve("code.txt"), "cambió");
        var resumed = coordinator().resume(root, pending.snapshot().run().runId(), false, forbiddenInput());
        assertThat(resumed.exitCode()).isEqualTo(5);
        assertThat(resumed.errorCode()).isEqualTo("REVISION_CHANGED");
        assertThat(runtime.calls).isEmpty();
        assertThat(resumed.snapshot().pendingDecision()).isPresent();
    }

    @Test
    void invalidOriginalCopyPreventsResume() throws Exception {
        var pending = coordinator().run(root, request, SimulationScenario.DECISION);
        Files.writeString(root.resolve(configuration(pending.snapshot()).data().get("requestLocation")), "alterada");
        var resumed = coordinator().resume(root, pending.snapshot().run().runId(), false, forbiddenInput());
        assertThat(resumed.exitCode()).isEqualTo(5);
        assertThat(resumed.errorCode()).isEqualTo("REQUEST_EVIDENCE_INVALID");
        assertThat(runtime.calls).isEmpty();
    }

    @Test
    void changedArtifactInvalidatesAcceptanceWithoutReplayingRuntime() throws Exception {
        var completed = coordinator().run(root, request, SimulationScenario.SUCCESS);
        var evidence = completed.snapshot().entries().stream().filter(entry -> entry.kind() == RunEntry.Kind.EVIDENCE).findFirst().orElseThrow();
        Files.writeString(root.resolve(evidence.data().get("location")), "alterado");
        assertThat(new EvidenceInspector(files).assess(root, completed.snapshot()).status()).isEqualTo("INVALID");
        var resumed = coordinator().resume(root, completed.snapshot().run().runId(), true, forbiddenInput());
        assertThat(resumed.exitCode()).isEqualTo(1);
        assertThat(resumed.snapshot().run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(resumed.verificationStatus()).isEqualTo("INVALID");
        assertThat(runtime.calls).hasSize(1);
    }

    @Test
    void changedCodeMakesEvidenceStaleAndViewCanBeRegeneratedIndependently() throws Exception {
        var completed = coordinator().run(root, request, SimulationScenario.SUCCESS);
        Path view = root.resolve(".odd-lab/views/" + completed.snapshot().run().runId() + ".md");
        Files.delete(view);
        var resumed = coordinator().resume(root, completed.snapshot().run().runId(), true, forbiddenInput());
        assertThat(resumed.exitCode()).isZero();
        assertThat(Files.readString(view)).contains("Versión: " + completed.snapshot().run().version());
        assertThat(resumed.snapshot()).isEqualTo(completed.snapshot());
        Files.writeString(root.resolve("code.txt"), "código nuevo");
        assertThat(new EvidenceInspector(files).assess(root, completed.snapshot()).status()).isEqualTo("STALE");
        assertThat(runtime.calls).hasSize(1);
    }

    @Test
    void unknownStopNeverBecomesWaitingOrCompletedAndCannotResumeBlindly() {
        AgentRuntime unknown = new AgentRuntime() {
            @Override public Capabilities capabilities() { return runtime.capabilities(); }
            @Override public Result execute(Assignment assignment) { return new Result(Status.SUCCEEDED, null, "desconocido", false, null); }
        };
        var outcome = coordinator(unknown).run(root, request, SimulationScenario.SUCCESS);
        assertThat(outcome.exitCode()).isEqualTo(5);
        assertThat(outcome.snapshot().run().state()).isEqualTo(RunState.RECONCILIATION_REQUIRED);
        assertThat(coordinator(unknown).resume(root, outcome.snapshot().run().runId(), true, forbiddenInput()).exitCode()).isEqualTo(5);
    }

    @Test
    void forbiddenRuntimeReceivesNoContextAndCreatesNoRun() {
        AgentRuntime forbidden = new AgentRuntime() {
            @Override public Capabilities capabilities() { return new Capabilities("external", false, true, true, true); }
            @Override public Result execute(Assignment assignment) { throw new AssertionError("No enviar contexto"); }
        };
        assertThatThrownBy(() -> coordinator(forbidden).run(root, request, SimulationScenario.SUCCESS))
                .isInstanceOf(ExecutionException.class).extracting(exception -> ((ExecutionException) exception).code()).isEqualTo("RUNTIME_NOT_ALLOWED");
        assertThat(journal.list(root, projects.load(root).orElseThrow().repositoryId())).isEmpty();
    }

    @Test
    void interruptionAfterIntentDoesNotRepeatAnUnknownAttempt() {
        var counter = new int[]{0};
        AgentRuntime interrupted = new AgentRuntime() {
            @Override public Capabilities capabilities() { return runtime.capabilities(); }
            @Override public Result execute(Assignment assignment) {
                counter[0]++;
                throw new ExecutionException("SYNTHETIC_INTERRUPT", "Interrupción sintética", 1);
            }
        };
        var outcome = coordinator(interrupted).run(root, request, SimulationScenario.SUCCESS);
        assertThat(outcome.snapshot().run().state()).isEqualTo(RunState.RUNNING);
        var resumed = coordinator(interrupted).resume(root, outcome.snapshot().run().runId(), true, forbiddenInput());
        assertThat(resumed.exitCode()).isEqualTo(5);
        assertThat(counter[0]).isEqualTo(1);
    }

    @Test
    void readyStateCannotBypassPendingBusinessDecision() {
        var pending = coordinator().run(root, request, SimulationScenario.DECISION);
        var run = pending.snapshot().run();
        journal.transition(root, run.repositoryId(), run.runId(), run.version(), RunState.READY, "test", "Fixture no autorizada");
        var resumed = coordinator().resume(root, run.runId(), true, forbiddenInput());
        assertThat(resumed.exitCode()).isEqualTo(5);
        assertThat(runtime.calls).isEmpty();
    }

    private SimulationCoordinator coordinator() { return coordinator(runtime); }
    private SimulationCoordinator coordinator(AgentRuntime engine) { return new SimulationCoordinator(projects, journal, files, engine, "test-user"); }
    private RunEntry configuration(RunSnapshot snapshot) {
        return snapshot.entries().stream().filter(entry -> "RUN_CONFIGURATION".equals(entry.data().get("recordType"))).findFirst().orElseThrow();
    }
    private DecisionInput input(boolean answer) {
        return new DecisionInput() {
            @Override public boolean interactive() { return true; }
            @Override public Optional<Boolean> ask(RunEntry decision) { return Optional.of(answer); }
        };
    }
    private DecisionInput forbiddenInput() {
        return new DecisionInput() {
            @Override public boolean interactive() { return true; }
            @Override public Optional<Boolean> ask(RunEntry decision) { throw new AssertionError("No debe preguntar"); }
        };
    }
    private static final class CountingRuntime implements AgentRuntime {
        private final SimulatedRuntime delegate = new SimulatedRuntime();
        private final List<Assignment> calls = new ArrayList<>();
        @Override public Capabilities capabilities() { return delegate.capabilities(); }
        @Override public Result execute(Assignment assignment) { calls.add(assignment); return delegate.execute(assignment); }
    }
}
