package dev.oddlab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackagedCliIT {

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", ""})
    void showsHelpWithoutRepositoryOrState(String argument) throws Exception {
        var result = invoke(argument.isEmpty() ? List.of() : List.of(argument));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Usage: odd-lab", "--help", "--version");
        assertThat(result.stdout()).doesNotContain("--json", ":: Spring Boot ::", "Started OddLabApplication");
        assertThat(result.stderr()).isEmpty();
        assertNoProjectState(result.workingDirectory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--version", "-V"})
    void showsVersionFromBuildMetadata(String argument) throws Exception {
        var result = invoke(List.of(argument));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("odd-lab " + System.getProperty("oddLab.version")
                + System.lineSeparator());
        assertThat(result.stderr()).isEmpty();
        assertNoProjectState(result.workingDirectory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--desconocida", "desconocido", "--json", "--spring.main.web-application-type=servlet"})
    void rejectsUnsupportedArguments(String argument) throws Exception {
        var result = invoke(List.of(argument));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(argument, "Usage: odd-lab");
        assertNoProjectState(result.workingDirectory());
    }

    @Test
    void packagedApplicationHasNoWebOrRuntimeDependencies() throws IOException {
        try (var jar = new java.util.jar.JarFile(System.getProperty("oddLab.jar"))) {
            var entries = jar.stream().map(java.util.jar.JarEntry::getName).toList();
            assertThat(entries).noneMatch(name -> name.contains("tomcat") || name.contains("jetty")
                    || name.contains("spring-web") || name.contains("engram") || name.contains("gentle-ai"));
        }
    }

    private Result invoke(List<String> arguments) throws Exception {
        Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path stdout = temporaryDirectory.resolve("stdout.txt");
        Path stderr = temporaryDirectory.resolve("stderr.txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-jar", Path.of(System.getProperty("oddLab.jar")).toAbsolutePath().toString()));
        command.addAll(arguments);
        var process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("La CLI debe terminar en 20 segundos").isTrue();
            assertThat(process.descendants().filter(ProcessHandle::isAlive).toList()).isEmpty();
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8), workingDirectory);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private void assertNoProjectState(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private record Result(int exitCode, String stdout, String stderr, Path workingDirectory) {
    }
}
