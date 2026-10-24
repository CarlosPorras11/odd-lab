package dev.oddlab.cli;

import dev.oddlab.orchestration.application.SimulationCoordinator;
import dev.oddlab.runtime.domain.SimulationScenario;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "run", mixinStandardHelpOptions = true,
        description = "Valida el flujo local con un runtime simulado; no implementa la petición ni inicia IA.")
public final class RunCommand extends ProjectCommand {
    public enum RuntimeChoice { simulated, process }
    @Option(names = "--runtime", required = true, description = "Motor disponible: ${COMPLETION-CANDIDATES}.")
    private RuntimeChoice runtime;
    @Option(names = "--request-file", required = true, paramLabel = "<archivo>", description = "Petición literal UTF-8, máximo 1 MiB.")
    private Path requestFile;
    @Option(names = "--scenario", defaultValue = "SUCCESS", description = "Escenario: ${COMPLETION-CANDIDATES}.")
    private SimulationScenario scenario;
    @Option(names = "--process-seconds", defaultValue = "15", description = "Duración del trabajador sintético process, entre 1 y 60 segundos.")
    private int processSeconds;
    private final SimulationCoordinator coordinator;

    public RunCommand(SimulationCoordinator coordinator, CommandOutput output) {
        super(output);
        this.coordinator = coordinator;
    }

    @Override
    protected int perform() {
        var outcome = coordinator.run(repository, requestFile, scenario, runtime.name(), processSeconds);
        return output.write(spec.commandLine(), structured, ExecutionPresentation.result(outcome), ExecutionPresentation.human(outcome),
                outcome.errorCode(), outcome.exitCode());
    }
}
