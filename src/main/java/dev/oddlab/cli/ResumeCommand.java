package dev.oddlab.cli;

import dev.oddlab.orchestration.application.DecisionInput;
import dev.oddlab.orchestration.application.SimulationCoordinator;
import java.util.UUID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "resume", mixinStandardHelpOptions = true,
        description = "Continúa una simulación segura o responde una decisión desde un terminal interactivo.")
public final class ResumeCommand extends ProjectCommand {
    @Option(names = "--run", required = true, paramLabel = "<id>", description = "UUID de la simulación.")
    private UUID runId;
    private final SimulationCoordinator coordinator;
    private final DecisionInput input;

    public ResumeCommand(SimulationCoordinator coordinator, DecisionInput input, CommandOutput output) {
        super(output);
        this.coordinator = coordinator;
        this.input = input;
    }

    @Override
    protected int perform() {
        var outcome = coordinator.resume(repository, runId, structured, input);
        return output.write(spec.commandLine(), structured, ExecutionPresentation.result(outcome), ExecutionPresentation.human(outcome),
                outcome.errorCode(), outcome.exitCode());
    }
}
