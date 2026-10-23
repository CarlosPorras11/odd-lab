package dev.oddlab.bootstrap;

import dev.oddlab.cli.RootCommand;
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
    CommandLine commandLine(BuildProperties buildProperties) {
        var commandLine = new CommandLine(new RootCommand());
        commandLine.getCommandSpec().version(buildProperties.getName() + " " + buildProperties.getVersion());
        return commandLine;
    }
}
