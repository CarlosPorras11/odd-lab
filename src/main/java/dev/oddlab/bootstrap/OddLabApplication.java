package dev.oddlab.bootstrap;

import dev.oddlab.cli.RootCommand;
import dev.oddlab.cli.CommandOutput;
import dev.oddlab.cli.InitCommand;
import dev.oddlab.cli.DoctorCommand;
import dev.oddlab.workspace.application.InitializeProject;
import dev.oddlab.workspace.application.DiagnoseProject;
import dev.oddlab.workspace.application.ProjectAccess;
import dev.oddlab.workspace.infrastructure.LocalProjectAccess;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;

@SpringBootApplication
public class OddLabApplication {

    public static void main(String[] args) {
        int exitCode = execute(new SpringApplication(OddLabApplication.class), args,
                new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        System.exit(exitCode);
    }

    static int execute(SpringApplication application, String[] args, PrintWriter out, PrintWriter err) {
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // CLI arguments belong to Picocli, never to Spring's configuration parser.
        try (var context = application.run()) {
            var commandLine = context.getBean(CommandLine.class);
            commandLine.setOut(out);
            commandLine.setErr(err);
            return commandLine.execute(args);
        }
    }

    @Bean
    ProjectAccess projectAccess() {
        return new LocalProjectAccess();
    }

    @Bean
    CommandLine commandLine(BuildProperties buildProperties, ProjectAccess projectAccess) {
        var commandLine = new CommandLine(new RootCommand());
        var output = new CommandOutput();
        commandLine.addSubcommand("init", new InitCommand(new InitializeProject(projectAccess), output));
        commandLine.addSubcommand("doctor", new DoctorCommand(new DiagnoseProject(projectAccess), output));
        commandLine.getCommandSpec().version(buildProperties.getName() + " " + buildProperties.getVersion());
        commandLine.getSubcommands().values().forEach(command -> command.getCommandSpec()
                .version(commandLine.getCommandSpec().version()));
        commandLine.setParameterExceptionHandler((exception, args) -> {
            var command = exception.getCommandLine();
            boolean structured = !command.getCommandName().equals("odd-lab")
                    && java.util.Arrays.stream(args).anyMatch(value -> value.equals("--json") || value.equals("--json=true"));
            if (structured) {
                return output.write(command, true, null, "Argumentos inválidos; consulta --help.", "USAGE_INVALID", 2);
            }
            command.getErr().println(exception.getMessage());
            command.usage(command.getErr());
            return 2;
        });
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> output.write(command,
                parseResult.hasMatchedOption("--json"), null,
                "Fallo interno del comando; no se ha confirmado su finalización.", "INTERNAL_ERROR", 1));
        return commandLine;
    }
}
