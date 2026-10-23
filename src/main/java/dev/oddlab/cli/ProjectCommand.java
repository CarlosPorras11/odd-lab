package dev.oddlab.cli;

import dev.oddlab.workspace.domain.ProjectException;
import dev.oddlab.work.domain.JournalException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

abstract class ProjectCommand implements Callable<Integer> {
    @Option(names = "--repo", required = true, paramLabel = "<ruta>", description = "Raíz explícita de un repositorio Git existente.")
    protected Path repository;

    @Option(names = "--json", description = "Devuelve un único objeto JSON; sin preguntas interactivas.")
    protected boolean structured;

    @Spec
    protected CommandSpec spec;

    protected final CommandOutput output;

    ProjectCommand(CommandOutput output) {
        this.output = output;
    }

    @Override
    public final Integer call() {
        try {
            return perform();
        } catch (ProjectException exception) {
            return output.write(spec.commandLine(), structured, null, exception.getMessage(), exception.code(), exception.exitCode());
        } catch (JournalException exception) {
            return output.write(spec.commandLine(), structured, null, exception.getMessage(), exception.code(), exception.exitCode());
        }
    }

    protected abstract int perform();

}
