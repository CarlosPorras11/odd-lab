package dev.oddlab.cli;

import dev.oddlab.control.application.RunControls;
import dev.oddlab.orchestration.application.SimulationCoordinator;
import java.util.UUID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "control", mixinStandardHelpOptions = true, description = "Guarda una solicitud de control y espera confirmación acotada del propietario.")
public final class ControlCommand extends ProjectCommand {
    @Option(names = "--run", required = true, paramLabel = "<id>", description = "UUID de la ejecución.")
    private UUID runId;
    @Option(names = "--wait-seconds", defaultValue = "5", description = "Espera de confirmación entre 0 y 30 segundos; por defecto 5.")
    private int waitSeconds;
    private final RunControls.Action action;
    private final SimulationCoordinator coordinator;
    public ControlCommand(SimulationCoordinator coordinator, RunControls.Action action, CommandOutput output) {
        super(output); this.coordinator = coordinator; this.action = action;
    }
    @Override protected int perform() {
        var result = coordinator.control(repository, runId, action, waitSeconds);
        return output.write(spec.commandLine(), structured, ExecutionPresentation.result(result), ExecutionPresentation.human(result), result.errorCode(), result.exitCode());
    }
}
