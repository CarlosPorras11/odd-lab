package dev.oddlab.orchestration.application;

import dev.oddlab.orchestration.domain.ExecutionException;
import dev.oddlab.runtime.application.AgentRuntime;
import dev.oddlab.runtime.domain.SimulationScenario;
import dev.oddlab.verification.application.EvidenceInspector;
import dev.oddlab.work.application.RunJournal;
import dev.oddlab.work.domain.*;
import dev.oddlab.workspace.application.ProjectAccess;
import dev.oddlab.workspace.domain.ProjectConfiguration;
import dev.oddlab.workspace.domain.ProjectException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class SimulationCoordinator {
    private final ProjectAccess projects;
    private final RunJournal journal;
    private final ExecutionFiles files;
    private final AgentRuntime runtime;
    private final EvidenceInspector evidence;
    private final String actor;

    public SimulationCoordinator(ProjectAccess projects, RunJournal journal, ExecutionFiles files, AgentRuntime runtime, String actor) {
        this.projects = projects;
        this.journal = journal;
        this.files = files;
        this.runtime = runtime;
        this.evidence = new EvidenceInspector(files);
        this.actor = actor == null || actor.isBlank() ? "local-user" : actor;
    }

    public Outcome run(Path repository, Path requestFile, SimulationScenario scenario) {
        requireSimulation();
        var project = project(repository);
        String request = files.readRequest(requestFile);
        String revision = files.revision(project.root());
        Instant now = Instant.now();
        var run = new RunRecord(UUID.randomUUID(), project.configuration().repositoryId(), actor, request, revision,
                project.configuration().policy(), RunState.CREATED, 0, now, now);
        journal.create(project.root(), run);
        var context = new Context(project.root(), journal.inspect(project.root(), run.repositoryId(), run.runId()));
        try {
            var requestArtifact = files.save(project.root(), run.runId(), "request.txt", request);
            UUID taskId = UUID.randomUUID();
            context.append(entry(RunEntry.Kind.TASK, "Configuración de simulación", Map.of("recordType", "RUN_CONFIGURATION",
                    "runtime", "simulated", "scenario", scenario.name(), "taskId", taskId.toString(),
                    "maxAttempts", Integer.toString(1 + run.policy().maxCorrectionsPerTask()),
                    "requestLocation", requestArtifact.location(), "requestSha256", requestArtifact.sha256(), "delegation", "NONE")));
            context.transition(RunState.EXPLORING, "Exploración local de la simulación");
            UUID requirement = UUID.randomUUID();
            context.append(new RunEntry(requirement, RunEntry.Kind.REQUIREMENT, 1, "Validar el recibo de simulación",
                    Map.of("criterion", "SIMULATED_RECEIPT", "scope", "SIMULATION", "text", "Petición, revisión e intento deben coincidir; no implementar la petición.")));
            context.append(new RunEntry(taskId, RunEntry.Kind.TASK, 1, "Simulación directa y acotada",
                    Map.of("recordType", "TASK", "requirementId", requirement.toString(), "scope", "SIMULATION", "role", "principal")));
            if (scenario == SimulationScenario.DECISION) {
                long expected = context.snapshot.run().version() + 2;
                var pending = entry(RunEntry.Kind.DECISION, "¿Continuar la simulación de esta petición?", Map.of(
                        "status", "PENDING", "action", "CONTINUE_SIMULATION", "scope", run.runId().toString(),
                        "revision", revision, "expectedVersion", Long.toString(expected)));
                context.appendTransition(pending, RunState.WAITING_INPUT, "Decisión pendiente; no hay runtime activo");
                return context.outcome(3, "INPUT_REQUIRED", "Decisión pendiente; continúa con resume desde un terminal interactivo.");
            }
            context.transition(RunState.READY, "Encargo sintético autorizado");
            return execute(context);
        } catch (ExecutionException exception) {
            return context.outcome(exception.exitCode(), exception.code(), exception.getMessage());
        }
    }

    public Outcome resume(Path repository, UUID runId, boolean structured, DecisionInput input) {
        requireSimulation();
        var project = project(repository);
        var context = new Context(project.root(), journal.inspect(project.root(), project.configuration().repositoryId(), runId));
        try {
            var configuration = configuration(context.snapshot);
            if (!context.snapshot.run().policy().equals(project.configuration().policy())
                    || !files.revision(project.root()).equals(context.snapshot.run().baseRevision())) {
                return context.outcome(5, "REVISION_CHANGED", "Revisión o política cambiadas; inicia una petición nueva o reconcilia antes de continuar.");
            }
            var request = new ExecutionFiles.Artifact(configuration.data().get("requestLocation"), configuration.data().get("requestSha256"));
            if (!files.matches(project.root(), request) || !request.sha256().equals(context.snapshot.run().requestSha256())) {
                return context.outcome(5, "REQUEST_EVIDENCE_INVALID", "Copia de petición ausente o alterada; no se repetirá trabajo.");
            }
            if (context.snapshot.run().state() == RunState.COMPLETED) {
                boolean valid = evidence.assess(project.root(), context.snapshot).status().equals("VALID");
                return context.outcome(valid ? 0 : 1, valid ? null : "VERIFICATION_INVALID",
                        valid ? "Simulación ya completada y evidencia vigente; no se ha repetido el runtime."
                                : "La evidencia guardada no es válida; no se repetirá el runtime.");
            }
            if (context.snapshot.run().state().terminal()) {
                return context.outcome(1, "RUN_TERMINAL", "Ejecución terminal; no se repite automáticamente.");
            }
            if (context.snapshot.run().state() == RunState.WAITING_INPUT) {
                var pending = context.snapshot.pendingDecision().orElseThrow(() -> new ExecutionException(
                        "DECISION_INCONSISTENT", "Falta la decisión que permitiría continuar.", 5));
                if (!context.snapshot.run().runId().toString().equals(pending.data().get("scope"))
                        || !context.snapshot.run().baseRevision().equals(pending.data().get("revision"))
                        || !Long.toString(context.snapshot.run().version()).equals(pending.data().get("expectedVersion"))) {
                    return context.outcome(5, "DECISION_STALE", "La decisión pertenece a otra versión; reconcilia el registro.");
                }
                if (structured || !input.interactive()) return context.outcome(3, "INPUT_REQUIRED", "Responde la decisión con resume en terminal interactivo, sin --json.");
                var answer = input.ask(pending);
                if (answer.isEmpty()) return context.outcome(3, "INPUT_REQUIRED", "Sin respuesta válida; decisión pendiente.");
                if (!files.revision(project.root()).equals(context.snapshot.run().baseRevision())) {
                    return context.outcome(5, "REVISION_CHANGED", "La revisión cambió durante la decisión; la respuesta no se aplicará.");
                }
                var resolution = entry(RunEntry.Kind.DECISION, "Respuesta del usuario a la simulación", Map.of(
                        "status", answer.get() ? "APPROVED" : "DENIED", "decisionId", pending.id().toString(), "actor", actor,
                        "scope", runId.toString(), "revision", context.snapshot.run().baseRevision()));
                context.appendTransition(resolution, answer.get() ? RunState.READY : RunState.FAILED,
                        answer.get() ? "Decisión aprobada para esta revisión" : "Decisión denegada por el usuario");
                if (!answer.get()) return context.outcome(1, "DECISION_DENIED", "El usuario ha denegado continuar la simulación.");
            }
            if (context.snapshot.run().state() != RunState.READY) {
                return context.outcome(5, "RESUME_NOT_SAFE", "Hay un intento activo o un cierre desconocido; se requiere reconciliación, sin repetir efectos.");
            }
            return execute(context);
        } catch (ExecutionException exception) {
            return context.outcome(exception.exitCode(), exception.code(), exception.getMessage());
        }
    }

    private Outcome execute(Context context) {
        var configuration = configuration(context.snapshot);
        UUID taskId = UUID.fromString(configuration.data().get("taskId"));
        var scenario = SimulationScenario.valueOf(configuration.data().get("scenario"));
        if (scenario == SimulationScenario.DECISION && (context.snapshot.pendingDecision().isPresent()
                || context.snapshot.entries().stream().noneMatch(entry -> entry.kind() == RunEntry.Kind.DECISION
                && "APPROVED".equals(entry.data().get("status"))
                && context.snapshot.run().runId().toString().equals(entry.data().get("scope"))
                && context.snapshot.run().baseRevision().equals(entry.data().get("revision"))))) {
            return context.outcome(5, "DECISION_INCONSISTENT", "No hay una aprobación vigente para continuar esta simulación.");
        }
        int maxAttempts = 1 + context.snapshot.run().policy().maxCorrectionsPerTask();
        if (!Integer.toString(maxAttempts).equals(configuration.data().get("maxAttempts"))) {
            throw new ExecutionException("RUN_CONFIGURATION_INVALID", "Presupuesto de ejecución incoherente.", 5);
        }
        int attempts = (int) context.snapshot.entries().stream().filter(entry -> "ATTEMPT_START".equals(entry.data().get("recordType"))).count();
        String lastError = "SIMULATION_FAILED";
        while (attempts < maxAttempts) {
            if (!files.revision(context.root).equals(context.snapshot.run().baseRevision())) {
                context.transition(RunState.RECONCILIATION_REQUIRED, "Revisión cambiada antes del intento");
                return context.outcome(5, "REVISION_CHANGED", "La revisión ha cambiado; no se ejecutará un intento nuevo.");
            }
            UUID attemptId = UUID.randomUUID();
            var start = new RunEntry(attemptId, RunEntry.Kind.TASK, 1, "Inicio de intento simulado", Map.of(
                    "recordType", "ATTEMPT_START", "taskId", taskId.toString(), "runtime", "simulated",
                    "ordinal", Integer.toString(++attempts), "revision", context.snapshot.run().baseRevision(),
                    "requestSha256", context.snapshot.run().requestSha256(), "startedAt", Instant.now().toString()));
            context.appendTransition(start, RunState.RUNNING, "Intención registrada antes de invocar el simulador");
            var run = context.snapshot.run();
            var result = runtime.execute(new AgentRuntime.Assignment(run.runId(), taskId, attemptId, attempts,
                    run.request(), run.requestSha256(), run.baseRevision(), run.policy(), maxAttempts, Map.of("scenario", scenario.name())));
            if (!result.stopped()) {
                context.transition(RunState.RECONCILIATION_REQUIRED, "El runtime no confirma detención");
                return context.outcome(5, "RUNTIME_STOP_UNKNOWN", "Detención no confirmada; no se declara espera segura ni éxito.");
            }
            if (result.status() == AgentRuntime.Status.PERMISSION_REQUESTED) {
                context.append(entry(RunEntry.Kind.TASK, "Intento detenido por permisos", Map.of("recordType", "ATTEMPT_RESULT",
                        "attemptId", attemptId.toString(), "status", "DENIED", "stopped", "true", "usage", "NO_DISPONIBLE")));
                context.appendTransition(entry(RunEntry.Kind.DECISION, "Ampliación de alcance rechazada", Map.of(
                        "status", "DENIED", "action", Objects.toString(result.requestedAction(), "UNKNOWN"),
                        "reason", "Fuera del encargo de simulación", "scope", run.runId().toString())), RunState.FAILED, "Permisos no ampliados");
                return context.outcome(1, "PERMISSION_SCOPE_DENIED", "La petición de permisos del simulador se ha rechazado y registrado.");
            }
            ExecutionFiles.Artifact candidate = null;
            if (result.status() == AgentRuntime.Status.SUCCEEDED && result.payload() != null) {
                candidate = files.save(context.root, run.runId(), "attempt-" + attemptId + ".json", result.payload());
            }
            var outcomeData = new LinkedHashMap<String, String>();
            outcomeData.put("recordType", "ATTEMPT_RESULT");
            outcomeData.put("attemptId", attemptId.toString());
            outcomeData.put("status", result.status().name());
            outcomeData.put("stopped", "true");
            outcomeData.put("usage", "NO_DISPONIBLE");
            outcomeData.put("finishedAt", Instant.now().toString());
            if (candidate != null) {
                outcomeData.put("location", candidate.location());
                outcomeData.put("sha256", candidate.sha256());
            }
            context.appendTransition(entry(RunEntry.Kind.TASK, "Resultado del intento simulado", outcomeData),
                    RunState.VERIFYING, "Resultado recogido; éxito del runtime no implica aceptación");
            if (!files.revision(context.root).equals(run.baseRevision())) {
                context.transition(RunState.RECONCILIATION_REQUIRED, "La revisión cambió tras el intento");
                return context.outcome(5, "REVISION_CHANGED", "La evidencia no corresponde a la revisión actual.");
            }
            boolean verified = candidate != null && files.receiptMatches(context.root, candidate, run.requestSha256(), run.baseRevision(), attemptId);
            if (verified) {
                var report = files.save(context.root, run.runId(), "verification-" + attemptId + ".txt",
                        "scope=SIMULATION\ncriterion=SIMULATED_RECEIPT\nrequestSha256=" + run.requestSha256()
                                + "\nrevision=" + run.baseRevision() + "\nreceiptSha256=" + candidate.sha256() + "\nverified=true\n");
                var original = new ExecutionFiles.Artifact(configuration.data().get("requestLocation"), configuration.data().get("requestSha256"));
                if (!files.matches(context.root, report) || !files.matches(context.root, original)
                        || !files.revision(context.root).equals(run.baseRevision())) {
                    context.transition(RunState.RECONCILIATION_REQUIRED, "Evidencia o revisión cambiadas durante verificación");
                    return context.outcome(5, "VERIFICATION_CHANGED", "No se puede confirmar una evidencia vigente.");
                }
                var data = new LinkedHashMap<String, String>();
                data.put("location", candidate.location()); data.put("sha256", candidate.sha256());
                data.put("revision", run.baseRevision()); data.put("scope", "SIMULATION"); data.put("verified", "true");
                data.put("requestSha256", run.requestSha256()); data.put("taskId", taskId.toString()); data.put("attemptId", attemptId.toString());
                data.put("verificationLocation", report.location()); data.put("verificationSha256", report.sha256());
                journal.completeSimulation(context.root, run.repositoryId(), run.runId(), context.snapshot.run().version(),
                        entry(RunEntry.Kind.EVIDENCE, "Aceptación del recibo de simulación", data), actor);
                context.refresh();
                return context.outcome(0, null, "Simulación completada y verificada; la petición no se ha implementado.");
            }
            lastError = result.status() == AgentRuntime.Status.FAILED ? "SIMULATION_FAILED" : "VERIFICATION_FAILED";
            context.append(entry(RunEntry.Kind.TASK, "Comprobación fallida", Map.of("recordType", "VERIFICATION",
                    "attemptId", attemptId.toString(), "status", "FAILED", "reason", lastError)));
        }
        context.transition(RunState.FAILED, "Presupuesto de intentos agotado");
        return context.outcome(1, lastError, "Simulación fallida o sin artefacto verificable; presupuesto agotado.");
    }

    private RunEntry configuration(RunSnapshot snapshot) {
        var configurations = snapshot.entries().stream().filter(entry -> "RUN_CONFIGURATION".equals(entry.data().get("recordType"))).toList();
        if (configurations.size() != 1 || !"simulated".equals(configurations.getFirst().data().get("runtime"))) {
            throw new ExecutionException("RUN_NOT_RESUMABLE", "Esta ejecución no pertenece al flujo simulado de E-04.", 2);
        }
        return configurations.getFirst();
    }

    private void requireSimulation() {
        var capabilities = runtime.capabilities();
        if (!capabilities.id().equals("simulated") || !capabilities.simulated() || capabilities.externalProcesses()
                || capabilities.tools() || capabilities.network()) {
            throw new ExecutionException("RUNTIME_NOT_ALLOWED", "E-04 solo admite el runtime simulado sin herramientas, procesos externos ni red.", 2);
        }
    }

    private Project project(Path repository) {
        Path root = projects.requireRepositoryRoot(repository);
        var configuration = projects.load(root).orElseThrow(() -> new ProjectException("CONFIG_NOT_FOUND", "Ejecuta init antes de iniciar trabajo.", 2));
        if (projects.inspect(root).stream().anyMatch(check -> check.status().equals("ERROR"))) {
            throw new ProjectException("PROJECT_NOT_READY", "La preparación del proyecto no es válida; revisa doctor.", 2);
        }
        journal.check(root, configuration.repositoryId());
        return new Project(root, configuration);
    }

    private RunEntry entry(RunEntry.Kind kind, String title, Map<String, String> data) {
        return new RunEntry(UUID.randomUUID(), kind, 1, title, data);
    }

    private record Project(Path root, ProjectConfiguration configuration) {}
    public record Outcome(RunSnapshot snapshot, int exitCode, String errorCode, String message, String verificationStatus, String viewStatus) {}

    private final class Context {
        private final Path root;
        private RunSnapshot snapshot;
        private Context(Path root, RunSnapshot snapshot) { this.root = root; this.snapshot = snapshot; }
        private void refresh() { snapshot = journal.inspect(root, snapshot.run().repositoryId(), snapshot.run().runId()); }
        private void append(RunEntry entry) {
            journal.append(root, snapshot.run().repositoryId(), snapshot.run().runId(), snapshot.run().version(), entry, actor);
            refresh();
        }
        private void transition(RunState state, String reason) {
            journal.transition(root, snapshot.run().repositoryId(), snapshot.run().runId(), snapshot.run().version(), state, actor, reason);
            refresh();
        }
        private void appendTransition(RunEntry entry, RunState state, String reason) {
            journal.appendAndTransition(root, snapshot.run().repositoryId(), snapshot.run().runId(), snapshot.run().version(), entry, state, actor, reason);
            refresh();
        }
        private Outcome outcome(int code, String errorCode, String message) {
            String viewStatus = "CURRENT";
            try { files.renderView(root, snapshot); }
            catch (ExecutionException exception) { viewStatus = "UNAVAILABLE"; }
            return new Outcome(snapshot, code, errorCode, message, evidence.assess(root, snapshot).status(), viewStatus);
        }
    }
}
