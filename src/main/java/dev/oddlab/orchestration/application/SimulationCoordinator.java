package dev.oddlab.orchestration.application;

import dev.oddlab.orchestration.domain.ExecutionException;
import dev.oddlab.runtime.application.AgentRuntime;
import dev.oddlab.runtime.application.RuntimeControl;
import dev.oddlab.runtime.application.ProcessSupervisor;
import dev.oddlab.control.application.RunControls;
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
    private final RunControls controls;
    private final ProcessSupervisor processes;
    private final Map<String, AgentRuntime> runtimes;

    public SimulationCoordinator(ProjectAccess projects, RunJournal journal, ExecutionFiles files, AgentRuntime runtime, String actor, RunControls controls, ProcessSupervisor processes, Map<String, AgentRuntime> runtimes) {
        this.projects = projects;
        this.journal = journal;
        this.files = files;
        this.runtime = runtime;
        this.controls = controls;
        this.processes = processes;
        this.runtimes = Map.copyOf(runtimes);
        this.evidence = new EvidenceInspector(files);
        this.actor = actor == null || actor.isBlank() ? "local-user" : actor;
    }

    public Outcome run(Path repository, Path requestFile, SimulationScenario scenario) {
        return run(repository, requestFile, scenario, runtime.capabilities().id(), 15);
    }

    public Outcome run(Path repository, Path requestFile, SimulationScenario scenario, String runtimeId, int processSeconds) {
        requireSimulation(selectRuntime(runtimeId));
        if (processSeconds < 1 || processSeconds > 60) throw new ExecutionException("PROCESS_DURATION_INVALID", "Duración entre 1 y 60 segundos.", 2);
        var project = project(repository);
        UUID runId = UUID.randomUUID();
        return owned(project, runId, () -> runOwned(repository, requestFile, scenario, runtimeId, processSeconds, runId));
    }

    private Outcome runOwned(Path repository, Path requestFile, SimulationScenario scenario, String runtimeId, int processSeconds, UUID runId) {
        var project = project(repository);
        String request = files.readRequest(requestFile);
        String revision = files.revision(project.root());
        Instant now = Instant.now();
        var run = new RunRecord(runId, project.configuration().repositoryId(), actor, request, revision,
                project.configuration().policy(), RunState.CREATED, 0, now, now);
        journal.create(project.root(), run);
        var context = new Context(project.root(), journal.inspect(project.root(), run.repositoryId(), run.runId()));
        try {
            var requestArtifact = files.save(project.root(), run.runId(), "request.txt", request);
            UUID taskId = UUID.randomUUID();
            context.append(entry(RunEntry.Kind.TASK, "Configuración de simulación", Map.of("recordType", "RUN_CONFIGURATION",
                    "runtime", runtimeId, "scenario", scenario.name(), "taskId", taskId.toString(),
                    "maxAttempts", Integer.toString(1 + run.policy().maxCorrectionsPerTask()),
                    "requestLocation", requestArtifact.location(), "requestSha256", requestArtifact.sha256(), "delegation", "NONE", "processSeconds", Integer.toString(processSeconds))));
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
                var controlled = applyControl(context);
                if (controlled != null) return controlled;
                return context.outcome(3, "INPUT_REQUIRED", "Decisión pendiente; continúa con resume desde un terminal interactivo.");
            }
            context.transition(RunState.READY, "Encargo sintético autorizado");
            return execute(context);
        } catch (ExecutionException exception) {
            return context.outcome(exception.exitCode(), exception.code(), exception.getMessage());
        }
    }

    public Outcome resume(Path repository, UUID runId, boolean structured, DecisionInput input) {
        var project = project(repository);
        journal.inspect(project.root(), project.configuration().repositoryId(), runId);
        return owned(project, runId, () -> resumeOwned(repository, runId, structured, input));
    }

    private Outcome resumeOwned(Path repository, UUID runId, boolean structured, DecisionInput input) {
        var project = project(repository);
        var context = new Context(project.root(), journal.inspect(project.root(), project.configuration().repositoryId(), runId));
        try {
            var configuration = configuration(context.snapshot);
            requireSimulation(selectRuntime(configuration.data().get("runtime")));
            if (!context.snapshot.run().state().terminal()) {
                var recovered = recover(context);
                if (recovered != null) return recovered;
                var controlled = applyControl(context);
                if (controlled != null) return controlled;
            }
            if (!context.snapshot.run().policy().equals(project.configuration().policy())
                    || !files.revision(project.root()).equals(context.snapshot.run().baseRevision())) {
                return context.outcome(5, "REVISION_CHANGED", "Revisión o política cambiadas; inicia una petición nueva o reconcilia antes de continuar.");
            }
            var request = new ExecutionFiles.Artifact(configuration.data().get("requestLocation"), configuration.data().get("requestSha256"));
            if (!files.matches(project.root(), request) || !request.sha256().equals(context.snapshot.run().requestSha256())) {
                return context.outcome(5, "REQUEST_EVIDENCE_INVALID", "Copia de petición ausente o alterada; no se repetirá trabajo.");
            }
            if (!context.snapshot.run().state().terminal()) {
                if (context.snapshot.run().state() == RunState.PAUSED) {
                    if (!quiescent(context) || !pauseProofValid(context)) return unknown(context);
                    var saved = lastStart(context);
                    if (saved != null) {
                        var result = context.snapshot.entries().stream().filter(entry -> "ATTEMPT_RESULT".equals(entry.data().get("recordType"))
                                && saved.id().toString().equals(entry.data().get("attemptId")) && "SUCCEEDED".equals(entry.data().get("status"))
                                && entry.data().containsKey("location")).findFirst();
                        if (result.isPresent()) {
                            context.transition(RunState.VERIFYING, "Resultado guardado antes de la pausa; no repetir intento");
                            var accepted = acceptSavedResult(context, new ExecutionFiles.Artifact(result.get().data().get("location"), result.get().data().get("sha256")),
                                    saved.id(), UUID.fromString(saved.data().get("taskId")));
                            return accepted != null ? accepted : unknown(context);
                        }
                    }
                    context.transition(context.snapshot.pendingDecision().isPresent() ? RunState.WAITING_INPUT : RunState.READY,
                            "Reanudación con detención previamente comprobada");
                }
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
                        || !decisionVersionValid(context.snapshot, pending)) {
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
            var controlled = applyControl(context);
            if (controlled != null) return controlled;
            if (!files.revision(context.root).equals(context.snapshot.run().baseRevision())) {
                context.transition(RunState.RECONCILIATION_REQUIRED, "Revisión cambiada antes del intento");
                return context.outcome(5, "REVISION_CHANGED", "La revisión ha cambiado; no se ejecutará un intento nuevo.");
            }
            UUID attemptId = UUID.randomUUID();
            var start = new RunEntry(attemptId, RunEntry.Kind.TASK, 1, "Inicio de intento simulado", Map.of(
                    "recordType", "ATTEMPT_START", "taskId", taskId.toString(), "runtime", configuration.data().get("runtime"),
                    "ordinal", Integer.toString(++attempts), "revision", context.snapshot.run().baseRevision(),
                    "requestSha256", context.snapshot.run().requestSha256(), "startedAt", Instant.now().toString()));
            context.appendTransition(start, RunState.RUNNING, "Intención registrada antes de invocar el simulador");
            var run = context.snapshot.run();
            var result = selectRuntime(configuration.data().get("runtime")).execute(new AgentRuntime.Assignment(run.runId(), taskId, attemptId, attempts,
                    run.request(), run.requestSha256(), run.baseRevision(), run.policy(), maxAttempts, Map.of("scenario", scenario.name(), "processSeconds", configuration.data().getOrDefault("processSeconds", "15"))),
                    new RuntimeControl() {
                        @Override public Optional<String> requestedAction() { return pendingControl(context).map(request -> request.action().name()); }
                        @Override public void started(List<RuntimeControl.ProcessIdentity> identities) {
                            for (int index = 0; index < identities.size(); index++) {
                                var identity = identities.get(index);
                                if (context.snapshot.entries().stream().noneMatch(entry -> "PROCESS_STARTED".equals(entry.data().get("recordType"))
                                        && attemptId.toString().equals(entry.data().get("attemptId")) && Long.toString(identity.pid()).equals(entry.data().get("pid")))) {
                                    context.append(entry(RunEntry.Kind.TASK, "Proceso sintético registrado", Map.of("recordType", "PROCESS_STARTED",
                                            "attemptId", attemptId.toString(), "pid", Long.toString(identity.pid()), "startedAt", identity.startedAt(), "role", index == 0 ? "parent" : "child")));
                                }
                            }
                        }
                    });
            if (!result.stopped()) {
                context.transition(RunState.RECONCILIATION_REQUIRED, "El runtime no confirma detención");
                return context.outcome(5, "RUNTIME_STOP_UNKNOWN", "Detención no confirmada; no se declara espera segura ni éxito.");
            }
            if (result.status() == AgentRuntime.Status.STOPPED) {
                context.append(entry(RunEntry.Kind.TASK, "Intento interrumpido y detenido", Map.of("recordType", "ATTEMPT_RESULT",
                        "attemptId", attemptId.toString(), "status", "STOPPED", "stopped", "true")));
                controlled = applyControl(context);
                if (controlled != null) return controlled;
                context.transition(RunState.RECONCILIATION_REQUIRED, "Detención sin solicitud de control vinculada");
                return context.outcome(5, "CONTROL_UNBOUND", "Detención sin solicitud vinculada; requiere reconciliación.");
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
            controlled = applyControl(context);
            if (controlled != null) return controlled;
            var accepted = acceptSavedResult(context, candidate, attemptId, taskId);
            if (accepted != null) return accepted;
            lastError = result.status() == AgentRuntime.Status.FAILED ? "SIMULATION_FAILED" : "VERIFICATION_FAILED";
            context.append(entry(RunEntry.Kind.TASK, "Comprobación fallida", Map.of("recordType", "VERIFICATION",
                    "attemptId", attemptId.toString(), "status", "FAILED", "reason", lastError)));
        }
        context.transition(RunState.FAILED, "Presupuesto de intentos agotado");
        return context.outcome(1, lastError, "Simulación fallida o sin artefacto verificable; presupuesto agotado.");
    }

    private Outcome acceptSavedResult(Context context, ExecutionFiles.Artifact candidate, UUID attemptId, UUID taskId) {
        var run = context.snapshot.run();
        var configuration = configuration(context.snapshot);
        if (!projects.load(context.root).orElseThrow().policy().equals(run.policy()) || !files.revision(context.root).equals(run.baseRevision())) {
            context.transition(RunState.RECONCILIATION_REQUIRED, "La revisión cambió tras el intento");
            return context.outcome(5, "REVISION_CHANGED", "La evidencia no corresponde a la revisión actual.");
        }
        boolean verified = candidate != null && files.receiptMatches(context.root, candidate, run.requestSha256(), run.baseRevision(), attemptId);
        if (verified) {
            var report = saveReport(context, "verification-" + attemptId + ".txt",
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
        return null;
    }

    private ExecutionFiles.Artifact saveReport(Context context, String name, String content) {
        try { return files.save(context.root, context.snapshot.run().runId(), name, content); }
        catch (ExecutionException exception) {
            if (!exception.code().equals("ARTIFACT_EXISTS")) throw exception;
            try {
                String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var existing = new ExecutionFiles.Artifact(".odd-lab/artifacts/" + context.snapshot.run().runId() + "/" + name, hash);
                if (files.matches(context.root, existing)) return existing;
                throw new ExecutionException("RECOVERY_ARTIFACT_CONFLICT", "Informe previo distinto; no se sobrescribe ni se repite el intento.", 5);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private AgentRuntime selectRuntime(String id) {
        var engine = runtimes.get(id);
        if (engine == null) throw new ExecutionException("RUNTIME_NOT_ALLOWED", "Perfil sintético no disponible.", 2);
        return engine;
    }

    private Outcome owned(Project project, UUID runId, java.util.function.Supplier<Outcome> work) {
        try (var lease = controls.claim(project.root(), project.configuration().repositoryId(), runId)) {
            var finished = new java.util.concurrent.CountDownLatch(1);
            var hook = new Thread(() -> {
                if (finished.getCount() == 0) return;
                try {
                    controls.request(project.root(), project.configuration().repositoryId(), runId, RunControls.Action.PAUSE);
                    finished.await(6, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) { /* Unconfirmed shutdown is reconciled on resume. */ }
            });
            Runtime.getRuntime().addShutdownHook(hook);
            try { return work.get(); }
            finally {
                finished.countDown();
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
            }
        }
    }

    public Outcome control(Path repository, UUID runId, RunControls.Action action, int waitSeconds) {
        if (waitSeconds < 0 || waitSeconds > 30) throw new ExecutionException("CONTROL_TIMEOUT_INVALID", "Espera entre 0 y 30 segundos.", 2);
        var project = project(repository);
        var context = new Context(project.root(), journal.inspect(project.root(), project.configuration().repositoryId(), runId));
        RunState target = action == RunControls.Action.PAUSE ? RunState.PAUSED : RunState.CANCELLED;
        if (context.snapshot.run().state() == target) return controlOutcome(context, 0, null, "Detención ya confirmada.");
        if (context.snapshot.run().state().terminal()) return controlOutcome(context, 2, "RUN_TERMINAL", "La ejecución ya es terminal; no se modifica.");
        var request = controls.request(project.root(), project.configuration().repositoryId(), runId, action);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(waitSeconds);
        do {
            context.refresh();
            boolean acknowledged = context.snapshot.entries().stream().anyMatch(entry -> "CONTROL_ACK".equals(entry.data().get("recordType"))
                    && request.id().toString().equals(entry.data().get("requestId")));
            if (acknowledged && context.snapshot.run().state() == target) return controlOutcome(context, 0, null, "Solicitud " + request.id() + " confirmada.");
            if (context.snapshot.run().state().terminal() || System.nanoTime() >= deadline) break;
            try { Thread.sleep(50); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
        } while (true);
        return controlOutcome(context, 5, "CONTROL_PENDING", "Solicitud " + request.id() + " guardada sin confirmación; usa resume para reconciliar si no hay propietario activo.");
    }

    private Outcome controlOutcome(Context context, int code, String error, String message) {
        // A requester neither owns the execution nor writes a derived view.
        return new Outcome(context.snapshot, code, error, message, evidence.assess(context.root, context.snapshot).status(), "UNCHANGED");
    }

    private Optional<RunControls.Request> pendingControl(Context context) {
        return controls.requests(context.root, context.snapshot.run().repositoryId(), context.snapshot.run().runId()).stream()
                .filter(request -> context.snapshot.entries().stream().noneMatch(entry -> "CONTROL_ACK".equals(entry.data().get("recordType"))
                        && request.id().toString().equals(entry.data().get("requestId"))))
                .sorted(Comparator.comparing(request -> request.action() != RunControls.Action.CANCEL)).findFirst();
    }

    private List<RuntimeControl.ProcessIdentity> identities(Context context) {
        try {
            return context.snapshot.entries().stream().filter(entry -> "PROCESS_STARTED".equals(entry.data().get("recordType")))
                    .map(entry -> new RuntimeControl.ProcessIdentity(Long.parseLong(entry.data().get("pid")), Instant.parse(entry.data().get("startedAt")).toString())).toList();
        } catch (RuntimeException exception) { throw new ExecutionException("PROCESS_IDENTITY_INVALID", "Identidades de proceso incoherentes; no se enviarán señales ni se repetirá trabajo.", 5); }
    }

    private RunEntry lastStart(Context context) {
        return context.snapshot.entries().stream().filter(entry -> "ATTEMPT_START".equals(entry.data().get("recordType")))
                .max(Comparator.comparingInt(entry -> Integer.parseInt(entry.data().get("ordinal")))).orElse(null);
    }

    private boolean quiescent(Context context) {
        var start = lastStart(context);
        if (start == null) return true;
        boolean result = context.snapshot.entries().stream().anyMatch(entry -> "ATTEMPT_RESULT".equals(entry.data().get("recordType"))
                && start.id().toString().equals(entry.data().get("attemptId")) && "true".equals(entry.data().get("stopped")));
        var processesForRun = identities(context);
        return result && (processesForRun.isEmpty() || processes.stopped(processesForRun));
    }

    private Outcome applyControl(Context context) {
        var pending = pendingControl(context);
        if (pending.isEmpty()) return null;
        if (!quiescent(context)) return null;
        return stop(context, pending.get().action());
    }

    private Outcome stop(Context context, RunControls.Action action) {
        RunState stopping = action == RunControls.Action.PAUSE ? RunState.PAUSING : RunState.CANCELLING;
        if (context.snapshot.run().state() != stopping) context.transition(stopping, "Control con efectos detenidos comprobados");
        for (var request : controls.requests(context.root, context.snapshot.run().repositoryId(), context.snapshot.run().runId())) {
            if ((request.action() == action || action == RunControls.Action.CANCEL)
                    && context.snapshot.entries().stream().noneMatch(entry -> "CONTROL_ACK".equals(entry.data().get("recordType"))
                    && request.id().toString().equals(entry.data().get("requestId")))) {
                context.append(entry(RunEntry.Kind.TASK, "Solicitud de control procesada", Map.of("recordType", "CONTROL_ACK",
                        "requestId", request.id().toString(), "action", request.action().name(), "resolution", action.name(), "requestedAt", request.createdAt().toString(), "confirmedAt", Instant.now().toString())));
            }
        }
        if (action == RunControls.Action.CANCEL && context.snapshot.pendingDecision().isPresent()) {
            var pending = context.snapshot.pendingDecision().orElseThrow();
            context.append(entry(RunEntry.Kind.DECISION, "Decisión cerrada por cancelación", Map.of("status", "DENIED",
                    "decisionId", pending.id().toString(), "scope", context.snapshot.run().runId().toString(),
                    "revision", context.snapshot.run().baseRevision(), "reason", "CANCELLED")));
        }
        var report = files.save(context.root, context.snapshot.run().runId(), "stop-" + UUID.randomUUID() + ".txt",
                "runId=" + context.snapshot.run().runId() + "\naction=" + action.name() + "\nstopped=true\nobservedAt=" + Instant.now()
                        + "\nprocesses=" + identities(context) + "\n");
        if (!files.matches(context.root, report)) return unknown(context);
        var proof = entry(RunEntry.Kind.EVIDENCE, "Detención sintética comprobada", Map.of("recordType", "STOP_CONFIRMATION",
                "stopped", "true", "runId", context.snapshot.run().runId().toString(), "action", action.name(), "scope", "SYNTHETIC_PROCESSES",
                "location", report.location(), "sha256", report.sha256(), "revision", context.snapshot.run().baseRevision()));
        journal.confirmStopped(context.root, context.snapshot.run().repositoryId(), context.snapshot.run().runId(), context.snapshot.run().version(),
                proof, action == RunControls.Action.PAUSE ? RunState.PAUSED : RunState.CANCELLED, actor);
        context.refresh();
        return context.outcome(4, null, "Ejecución detenida y " + (action == RunControls.Action.PAUSE ? "pausada." : "cancelada."));
    }

    private Outcome recover(Context context) {
        RunState state = context.snapshot.run().state();
        if (state == RunState.VERIFYING) {
            if (!quiescent(context)) return unknown(context);
            var control = applyControl(context);
            if (control != null) return control;
            var start = lastStart(context);
            if (start == null) return unknown(context);
            var result = context.snapshot.entries().stream().filter(entry -> "ATTEMPT_RESULT".equals(entry.data().get("recordType"))
                    && start.id().toString().equals(entry.data().get("attemptId"))).findFirst().orElseThrow();
            if (result.data().containsKey("location")) {
                var accepted = acceptSavedResult(context, new ExecutionFiles.Artifact(result.data().get("location"), result.data().get("sha256")),
                        start.id(), UUID.fromString(start.data().get("taskId")));
                if (accepted != null) return accepted;
            }
            return unknown(context);
        }
        if (!List.of(RunState.RUNNING, RunState.RECONCILIATION_REQUIRED, RunState.PAUSING, RunState.CANCELLING).contains(state)) return null;
        if (!quiescent(context)) {
            var start = lastStart(context);
            var all = identities(context);
            boolean completeIdentity = start != null && context.snapshot.entries().stream().anyMatch(entry -> "PROCESS_STARTED".equals(entry.data().get("recordType"))
                    && start.id().toString().equals(entry.data().get("attemptId")) && "child".equals(entry.data().get("role")));
            if (!completeIdentity || !processes.stopAndConfirm(all)) return unknown(context);
            context.append(entry(RunEntry.Kind.TASK, "Intento reconciliado sin repetir", Map.of("recordType", "ATTEMPT_RESULT",
                    "attemptId", start.id().toString(), "status", "STOPPED", "stopped", "true", "recovered", "true")));
        }
        var action = pendingControl(context).map(RunControls.Request::action)
                .orElse(state == RunState.CANCELLING ? RunControls.Action.CANCEL : RunControls.Action.PAUSE);
        return stop(context, action);
    }

    private Outcome unknown(Context context) {
        if (context.snapshot.run().state() != RunState.RECONCILIATION_REQUIRED) context.transition(RunState.RECONCILIATION_REQUIRED, "No se pueden reconciliar todos los efectos");
        return context.outcome(5, "RECONCILIATION_REQUIRED", "No hay prueba suficiente de procesos/resultado; no se repetirá el intento.");
    }

    private boolean pauseProofValid(Context context) {
        var proof = context.snapshot.events().stream().filter(event -> event.type().equals("ENTRY_ADDED"))
                .flatMap(event -> context.snapshot.entries().stream().filter(entry -> entry.id().toString().equals(event.detail())
                        && "STOP_CONFIRMATION".equals(entry.data().get("recordType"))))
                .reduce((earlier, later) -> later);
        return proof.isPresent() && "PAUSE".equals(proof.get().data().get("action"))
                && files.matches(context.root, new ExecutionFiles.Artifact(proof.get().data().get("location"), proof.get().data().get("sha256")));
    }

    private boolean decisionVersionValid(RunSnapshot snapshot, RunEntry pending) {
        try {
            long version = Long.parseLong(pending.data().get("expectedVersion"));
            if (version < 0 || version > snapshot.run().version()) return false;
            if (snapshot.events().get(Math.toIntExact(version)).state() != RunState.WAITING_INPUT) return false;
            // Later changes may only be a confirmed pause/control cycle; current version still protects the answer transaction.
            return snapshot.events().stream().filter(event -> event.version() > version && event.type().equals("ENTRY_ADDED"))
                    .allMatch(event -> snapshot.entries().stream().anyMatch(entry -> entry.id().toString().equals(event.detail())
                            && List.of("CONTROL_ACK", "STOP_CONFIRMATION").contains(entry.data().getOrDefault("recordType", ""))));
        } catch (RuntimeException exception) { return false; }
    }

    private RunEntry configuration(RunSnapshot snapshot) {
        var configurations = snapshot.entries().stream().filter(entry -> "RUN_CONFIGURATION".equals(entry.data().get("recordType"))).toList();
        if (configurations.size() != 1 || !runtimes.containsKey(configurations.getFirst().data().get("runtime"))) {
            throw new ExecutionException("RUN_NOT_RESUMABLE", "Esta ejecución no pertenece a un perfil sintético compatible.", 2);
        }
        return configurations.getFirst();
    }

    private void requireSimulation(AgentRuntime engine) {
        var capabilities = engine.capabilities();
        if (!(capabilities.id().equals("simulated") && !capabilities.externalProcesses()
                || capabilities.id().equals("process") && capabilities.externalProcesses()) || !capabilities.simulated()
                || capabilities.tools() || capabilities.network()) {
            throw new ExecutionException("RUNTIME_NOT_ALLOWED", "Solo se admiten perfiles sintéticos compilados, sin herramientas arbitrarias ni red.", 2);
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
