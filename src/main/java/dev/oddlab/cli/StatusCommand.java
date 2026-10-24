package dev.oddlab.cli;

import dev.oddlab.work.application.QueryRuns;
import dev.oddlab.work.domain.RunRecord;
import dev.oddlab.verification.application.EvidenceInspector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Consulta el registro local de ejecuciones sin modificarlo ni iniciar IA.")
public final class StatusCommand extends ProjectCommand {
    @Option(names = "--run", paramLabel = "<id>", description = "UUID de una ejecución; si se omite, lista las ejecuciones.")
    private UUID runId;
    private final QueryRuns query;
    private final EvidenceInspector evidence;
    private final dev.oddlab.control.application.RunControls controls;

    public StatusCommand(QueryRuns query, EvidenceInspector evidence, dev.oddlab.control.application.RunControls controls, CommandOutput output) {
        super(output);
        this.query = query;
        this.evidence = evidence;
        this.controls = controls;
    }

    @Override
    protected int perform() {
        if (runId != null) {
            var snapshot = query.inspect(repository, runId);
            var run = snapshot.run();
            var assessment = evidence.assess(repository, snapshot);
            var result = new LinkedHashMap<String, Object>(summary(run));
            result.put("verificationStatus", assessment.status());
            result.put("pendingDecision", ExecutionPresentation.pending(snapshot));
        result.put("controlRequests", ExecutionPresentation.controls(controls, repository, snapshot));
            return output.write(spec.commandLine(), structured, result, run.runId() + " " + run.state()
                    + " (versión " + run.version() + ") Verificación: " + assessment.status(), null, 0);
        }
        var runs = query.list(repository);
        var summaries = runs.stream().map(run -> {
            var snapshot = query.inspect(repository, run.runId());
            var result = new LinkedHashMap<String, Object>(summary(snapshot.run()));
            result.put("verificationStatus", evidence.assess(repository, snapshot).status());
            result.put("pendingDecision", ExecutionPresentation.pending(snapshot));
        result.put("controlRequests", ExecutionPresentation.controls(controls, repository, snapshot));
            return result;
        }).toList();
        String message = summaries.isEmpty() ? "Sin ejecuciones registradas."
                : summaries.stream().map(result -> result.get("runId") + " " + result.get("state")
                + " (versión " + result.get("version") + ") Verificación: " + result.get("verificationStatus"))
                .collect(Collectors.joining(System.lineSeparator()));
        return output.write(spec.commandLine(), structured, Map.of("runs", summaries), message, null, 0);
    }

    static Map<String, Object> summary(RunRecord run) {
        return Map.of("runId", run.runId().toString(), "state", run.state().name(), "version", run.version(),
                "repositoryId", run.repositoryId().toString(), "actor", run.actor(), "baseRevision", run.baseRevision(),
                "createdAt", run.createdAt().toString(), "updatedAt", run.updatedAt().toString());
    }
}
