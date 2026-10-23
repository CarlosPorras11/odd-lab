package dev.oddlab.cli;

import dev.oddlab.work.application.QueryRuns;
import dev.oddlab.work.domain.RunRecord;
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

    public StatusCommand(QueryRuns query, CommandOutput output) {
        super(output);
        this.query = query;
    }

    @Override
    protected int perform() {
        if (runId != null) {
            var run = query.inspect(repository, runId).run();
            return output.write(spec.commandLine(), structured, summary(run), run.runId() + " " + run.state()
                    + " (versión " + run.version() + ")", null, 0);
        }
        var runs = query.list(repository);
        String message = runs.isEmpty() ? "Sin ejecuciones registradas."
                : runs.stream().map(run -> run.runId() + " " + run.state() + " (versión " + run.version() + ")")
                .collect(Collectors.joining(System.lineSeparator()));
        return output.write(spec.commandLine(), structured, Map.of("runs", runs.stream().map(StatusCommand::summary).toList()), message, null, 0);
    }

    static Map<String, Object> summary(RunRecord run) {
        return Map.of("runId", run.runId().toString(), "state", run.state().name(), "version", run.version(),
                "repositoryId", run.repositoryId().toString(), "actor", run.actor(), "baseRevision", run.baseRevision(),
                "createdAt", run.createdAt().toString(), "updatedAt", run.updatedAt().toString());
    }
}
