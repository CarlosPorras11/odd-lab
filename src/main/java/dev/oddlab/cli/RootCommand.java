package dev.oddlab.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "odd-lab", mixinStandardHelpOptions = true,
        description = "Sistema propio de desarrollo con agentes inspirado en ODD.",
        footer = "Los comandos de ejecución de agentes todavía no están disponibles.")
public final class RootCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }
}
