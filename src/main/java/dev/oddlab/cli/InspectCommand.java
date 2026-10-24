package dev.oddlab.cli;

import dev.oddlab.work.application.QueryRuns;
import dev.oddlab.work.domain.RunEntry;
import dev.oddlab.verification.application.EvidenceInspector;
import java.util.LinkedHashMap;
import java.util.UUID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "inspect", mixinStandardHelpOptions = true,
        description = "Muestra petición, política, requisitos, tareas, decisiones, evidencia y eventos registrados.")
public final class InspectCommand extends ProjectCommand {
    @Option(names = "--run", required = true, paramLabel = "<id>", description = "UUID de la ejecución a inspeccionar.")
    private UUID runId;
    private final QueryRuns query;
    private final EvidenceInspector evidence;
    private final dev.oddlab.control.application.RunControls controls;

    public InspectCommand(QueryRuns query, EvidenceInspector evidence, dev.oddlab.control.application.RunControls controls, CommandOutput output) {
        super(output);
        this.query = query;
        this.evidence = evidence;
        this.controls = controls;
    }

    @Override
    protected int perform() {
        var snapshot = query.inspect(repository, runId);
        var result = new LinkedHashMap<String, Object>(StatusCommand.summary(snapshot.run()));
        result.put("request", snapshot.run().request());
        result.put("requestSha256", snapshot.run().requestSha256());
        result.put("policy", snapshot.run().policy());
        result.put("events", snapshot.events());
        var assessment = evidence.assess(repository, snapshot);
        result.put("verificationStatus", assessment.status());
        result.put("pendingDecision", ExecutionPresentation.pending(snapshot));
        result.put("controlRequests", ExecutionPresentation.controls(controls, repository, snapshot));
        result.put("requirements", snapshot.entries().stream().filter(entry -> entry.kind() == RunEntry.Kind.REQUIREMENT).toList());
        result.put("tasks", snapshot.entries().stream().filter(entry -> entry.kind() == RunEntry.Kind.TASK).toList());
        result.put("decisions", snapshot.entries().stream().filter(entry -> entry.kind() == RunEntry.Kind.DECISION).toList());
        result.put("evidence", snapshot.entries().stream().filter(entry -> entry.kind() == RunEntry.Kind.EVIDENCE).toList());
        var message = new StringBuilder("Ejecución: " + snapshot.run().runId() + " " + snapshot.run().state())
                .append("\nPetición original: ").append(snapshot.run().request())
                .append("\nSHA-256: ").append(snapshot.run().requestSha256())
                .append("\nBase Git: ").append(snapshot.run().baseRevision())
                .append("\nPolítica registrada: ").append(snapshot.run().policy())
                .append("\nVerificación actual: ").append(assessment.status());
        message.append("\nSolicitudes de control: ").append(result.get("controlRequests"));
        snapshot.entries().forEach(entry -> message.append("\n").append(entry.kind()).append(" ")
                .append(entry.id()).append(": ").append(entry.title()).append(" ").append(entry.data()));
        snapshot.events().forEach(event -> message.append("\nEvento v").append(event.version())
                .append(" ").append(event.type()).append(" ").append(event.state()).append(" ").append(event.detail()));
        return output.write(spec.commandLine(), structured, result, message.toString(), null, 0);
    }
}
