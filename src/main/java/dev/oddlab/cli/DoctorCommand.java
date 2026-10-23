package dev.oddlab.cli;

import dev.oddlab.workspace.application.DiagnoseProject;
import java.util.Map;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;

@Command(name = "doctor", mixinStandardHelpOptions = true,
        description = "Comprueba Git, configuración y directorios sin modificar el repositorio ni iniciar IA.")
public final class DoctorCommand extends ProjectCommand {
    private final DiagnoseProject diagnose;

    public DoctorCommand(DiagnoseProject diagnose, CommandOutput output) {
        super(output);
        this.diagnose = diagnose;
    }

    @Override
    protected int perform() {
        var result = diagnose.execute(repository);
        String message = "Diagnóstico local: " + result.repository() + System.lineSeparator()
                + result.checks().stream().map(check -> check.code() + " [" + check.status() + "] " + check.message())
                .collect(Collectors.joining(System.lineSeparator()));
        return output.write(spec.commandLine(), structured, Map.of("repository", result.repository().toString(),
                "configuration", result.configuration(), "healthy", result.healthy(), "checks", result.checks()),
                message, result.healthy() ? null : "DIAGNOSTIC_FAILED", result.healthy() ? 0 : 2);
    }
}
