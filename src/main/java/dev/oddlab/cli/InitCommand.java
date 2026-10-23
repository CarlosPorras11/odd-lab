package dev.oddlab.cli;

import dev.oddlab.workspace.application.InitializeProject;
import java.util.Map;
import picocli.CommandLine.Command;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Prepara identidad, política local y directorios operativos de odd-lab.",
        footer = "Crea .odd-lab.yaml, directorios operativos y registro SQLite v1. Añade exclusiones a .gitignore conservando su contenido. No crea commits ni habilita proveedores.")
public final class InitCommand extends ProjectCommand {
    private final InitializeProject initialize;

    public InitCommand(InitializeProject initialize, CommandOutput output) {
        super(output);
        this.initialize = initialize;
    }

    @Override
    protected int perform() {
        var result = initialize.execute(repository);
        String message = (result.created() ? "Proyecto inicializado: " : "Proyecto validado y preparado: ")
                + result.repository() + System.lineSeparator() + "Identidad: " + result.configuration().repositoryId();
        return output.write(spec.commandLine(), structured, Map.of("repository", result.repository().toString(),
                "configuration", result.configuration(), "created", result.created()), message, null, 0);
    }
}
