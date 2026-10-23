package dev.oddlab.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.slf4j.LoggerFactory;

@ExtendWith(OutputCaptureExtension.class)
class OddLabApplicationTest {

    @ParameterizedTest
    @CsvSource({"--help, 0", "--version, 0", "--desconocida, 2"})
    void closesSpringContextAndReturnsCliExitCode(String argument, int expectedExitCode, CapturedOutput captured) {
        var closedContext = new AtomicReference<ConfigurableApplicationContext>();
        var application = new SpringApplication(OddLabApplication.class);
        application.addListeners(event -> {
            if (event instanceof ContextRefreshedEvent) {
                LoggerFactory.getLogger(OddLabApplicationTest.class).warn("Diagnóstico de prueba E-01");
            }
            if (event instanceof ContextClosedEvent closed) {
                closedContext.set((ConfigurableApplicationContext) closed.getApplicationContext());
            }
        });
        var stdout = new StringWriter();
        var stderr = new StringWriter();

        int exitCode = OddLabApplication.execute(application, new String[]{argument},
                new PrintWriter(stdout, true), new PrintWriter(stderr, true));

        assertThat(exitCode).isEqualTo(expectedExitCode);
        assertThat(closedContext.get()).isNotNull();
        assertThat(closedContext.get().isActive()).isFalse();
        assertThat(captured.getOut()).isEmpty();
        assertThat(captured.getErr()).contains("Diagnóstico de prueba E-01");
        if (expectedExitCode == 0) {
            assertThat(stdout.toString()).isNotBlank();
            assertThat(stderr.toString()).isEmpty();
        } else {
            assertThat(stdout.toString()).isEmpty();
            assertThat(stderr.toString()).contains(argument);
        }
    }
}
