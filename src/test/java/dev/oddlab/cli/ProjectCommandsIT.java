package dev.oddlab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProjectCommandsIT {
    @TempDir
    Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private int invocation;

    @Test
    void initializesIdempotentlyPreservingGitignoreAndExistingFiles() throws Exception {
        Path repository = repository();
        byte[] originalIgnore = "# Reglas propias\r\n*.log".getBytes(StandardCharsets.UTF_8);
        Files.write(repository.resolve(".gitignore"), originalIgnore);
        Files.writeString(repository.resolve("keep.txt"), "intacto");
        var first = invoke("init", "--repo", repository.toString(), "--json");
        assertThat(first.code()).isZero();
        assertThat(first.stderr()).isEmpty();
        var document = json.readTree(first.stdout());
        assertThat(document.at("/result/created").asBoolean()).isTrue();
        assertThat(document.at("/result/configuration/policy/allowedProviders").size()).isZero();
        assertThat(document.at("/result/configuration/policy/networkAllowed").asBoolean()).isFalse();
        assertThat(Files.readAllBytes(repository.resolve(".gitignore"))).startsWith(originalIgnore);
        assertThat(Files.readString(repository.resolve("keep.txt"))).isEqualTo("intacto");
        for (String name : List.of("state", "artifacts", "workspaces", "views")) {
            assertThat(repository.resolve(".odd-lab").resolve(name)).isDirectory();
            Files.writeString(repository.resolve(".odd-lab").resolve(name).resolve("private.txt"), "privado");
        }
        assertThat(git(repository, "status", "--porcelain")).doesNotContain(".odd-lab/");
        Map<String, String> before = snapshot(repository);
        var second = invoke("init", "--json", "--repo", repository.toString());
        assertThat(second.code()).isZero();
        assertThat(json.readTree(second.stdout()).at("/result/created").asBoolean()).isFalse();
        assertThat(snapshot(repository)).isEqualTo(before);
        assertThat(git(repository, "log", "--oneline", "--all")).isEmpty();
    }

    @Test
    void doctorIsReadOnlyAndDistinguishesPendingCapabilities() throws Exception {
        Path repository = initializedRepository();
        Map<String, String> before = snapshot(repository);
        var result = invoke("doctor", "--repo", repository.toString(), "--json");
        assertThat(result.code()).isZero();
        assertThat(result.stderr()).isEmpty();
        var document = json.readTree(result.stdout());
        assertThat(document.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(document.path("command").asString()).isEqualTo("doctor");
        assertThat(document.path("error").isNull()).isTrue();
        assertThat(document.at("/result/healthy").asBoolean()).isTrue();
        assertThat(document.at("/result/checks").toString()).contains("STORAGE", "NOT_CONFIGURED").doesNotContain("NOT_IMPLEMENTED");
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void preservesValidUserConfigurationAndComments() throws Exception {
        Path repository = initializedRepository();
        Path descriptor = repository.resolve(".odd-lab.yaml");
        String configured = "# Política revisada por usuario\n" + Files.readString(descriptor)
                .replace("allowedProviders: []", "allowedProviders: [codex]")
                .replace("maxActiveSubagents: 2", "maxActiveSubagents: 0");
        Files.writeString(descriptor, configured);
        var before = snapshot(repository);
        var result = invoke("init", "--repo", repository.toString(), "--json");
        assertThat(result.code()).isZero();
        assertThat(json.readTree(result.stdout()).at("/result/configuration/policy/maxActiveSubagents").asInt()).isZero();
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void reportsUnknownArgumentAsStructuredError() throws Exception {
        assertError(invoke("doctor", "--bad", "--json=true"), "USAGE_INVALID", 2);
    }

    @Test
    void rejectsBareAndMissingRepositoriesWithoutCreatingState() throws Exception {
        Path bare = Files.createDirectory(temporary.resolve("bare"));
        git(bare, "init", "--bare");
        var before = snapshot(bare);
        assertError(invoke("init", "--repo", bare.toString(), "--json"), "INVALID_REPOSITORY", 2);
        assertError(invoke("init", "--repo", temporary.resolve("missing").toString(), "--json"), "INVALID_REPOSITORY", 2);
        assertThat(snapshot(bare)).isEqualTo(before);
        assertThat(temporary.resolve("missing")).doesNotExist();
    }

    @Test
    void ignoresInheritedGitDirectoryOverrides() throws Exception {
        Path repository = repository();
        var result = invokeWithEnvironment(Map.of("GIT_DIR", temporary.resolve("missing-git").toString()),
                "init", "--repo", repository.toString(), "--json");
        assertThat(result.code()).isZero();
        assertThat(repository.resolve(".odd-lab.yaml")).isRegularFile();
        assertThat(temporary.resolve("missing-git")).doesNotExist();
    }

    @Test
    void doctorReportsMissingDirectoryWithoutRepairingIt() throws Exception {
        Path repository = initializedRepository();
        Files.delete(repository.resolve(".odd-lab/views"));
        var before = snapshot(repository);
        var result = invoke("doctor", "--repo", repository.toString(), "--json");
        assertError(result, "DIAGNOSTIC_FAILED", 2);
        assertThat(json.readTree(result.stdout()).at("/result/checks").toString()).contains("DIRECTORY_VIEWS", "ERROR");
        assertThat(snapshot(repository)).isEqualTo(before);
        assertThat(invoke("init", "--repo", repository.toString()).code()).isZero();
        assertThat(repository.resolve(".odd-lab/views")).isDirectory();
    }

    @Test
    void repairsOverriddenIgnoreRulesWithoutReplacingDescriptor() throws Exception {
        Path repository = initializedRepository();
        byte[] descriptor = Files.readAllBytes(repository.resolve(".odd-lab.yaml"));
        Files.writeString(repository.resolve(".gitignore"), "\n!/.odd-lab/views/\n", java.nio.file.StandardOpenOption.APPEND);
        assertError(invoke("doctor", "--repo", repository.toString(), "--json"), "DIAGNOSTIC_FAILED", 2);
        assertThat(invoke("init", "--repo", repository.toString()).code()).isZero();
        assertThat(Files.readAllBytes(repository.resolve(".odd-lab.yaml"))).isEqualTo(descriptor);
        assertThat(invoke("doctor", "--repo", repository.toString()).code()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"init", "doctor"})
    void requiresExplicitRepositoryWithMachineReadableUsageError(String command) throws Exception {
        assertError(invoke(command, "--json"), "USAGE_INVALID", 2);
        var help = invoke(command, "--help");
        assertThat(help.code()).isZero();
        assertThat(help.stdout()).contains("--repo", "--json");
        assertThat(help.stderr()).isEmpty();
        var version = invoke(command, "--version");
        assertThat(version.code()).isZero();
        assertThat(version.stdout()).isEqualTo("odd-lab " + System.getProperty("oddLab.version") + System.lineSeparator());
        assertThat(version.stderr()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"init", "doctor"})
    void rejectsNonRepositoryWithoutWriting(String command) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plain"));
        assertError(invoke(command, "--repo", directory.toString(), "--json"), "INVALID_REPOSITORY", 2);
        assertThat(snapshot(directory)).isEmpty();
    }

    @Test
    void rejectsNestedDirectoryInsteadOfInitializingWrongRoot() throws Exception {
        Path repository = repository();
        Path nested = Files.createDirectory(repository.resolve("nested"));
        var before = snapshot(repository);
        assertError(invoke("init", "--repo", nested.toString(), "--json"), "REPOSITORY_ROOT_REQUIRED", 2);
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void doctorOfUninitializedRepositoryDoesNotCreateAnything() throws Exception {
        Path repository = repository();
        var before = snapshot(repository);
        assertError(invoke("doctor", "--repo", repository.toString(), "--json"), "CONFIG_NOT_FOUND", 2);
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion: 9", "schemaVersion: [", "!!java.lang.ProcessBuilder {}", "schemaVersion: 1\nschemaVersion: 1"})
    void preservesInvalidExistingDescriptorAndDoesNotPrepareDirectories(String descriptor) throws Exception {
        Path repository = repository();
        Files.writeString(repository.resolve(".odd-lab.yaml"), descriptor);
        var before = snapshot(repository);
        var result = invoke("init", "--repo", repository.toString(), "--json");
        assertThat(result.code()).isEqualTo(2);
        assertThat(json.readTree(result.stdout()).at("/error/code").asString()).isEqualTo("CONFIG_INVALID");
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void rejectsUnknownVersionOfOtherwiseValidConfiguration() throws Exception {
        Path repository = initializedRepository();
        Path descriptor = repository.resolve(".odd-lab.yaml");
        Files.writeString(descriptor, Files.readString(descriptor).replaceFirst("schemaVersion: 1", "schemaVersion: 9"));
        var before = snapshot(repository);
        assertError(invoke("init", "--repo", repository.toString(), "--json"), "CONFIG_VERSION_UNSUPPORTED", 2);
        assertError(invoke("doctor", "--repo", repository.toString(), "--json"), "CONFIG_VERSION_UNSUPPORTED", 2);
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {".gitignore", ".odd-lab.yaml", ".odd-lab", ".odd-lab/state"})
    void rejectsSymlinksWithoutTouchingOutsideTargets(String relative) throws Exception {
        Path repository = repository();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "intacto");
        Path link = repository.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        var before = snapshot(repository);
        assertError(invoke("init", "--repo", repository.toString(), "--json"), "UNSAFE_PROJECT_PATH", 2);
        assertThat(Files.readString(outside.resolve("sentinel"))).isEqualTo("intacto");
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void rejectsFilesInPlaceOfOperationalDirectoriesBeforeWriting() throws Exception {
        Path repository = repository();
        Files.writeString(repository.resolve(".odd-lab"), "no es un directorio");
        var before = snapshot(repository);
        assertError(invoke("init", "--repo", repository.toString(), "--json"), "UNSAFE_PROJECT_PATH", 2);
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void refusesOperationalFilesAlreadyTrackedByGit() throws Exception {
        Path repository = repository();
        Files.createDirectories(repository.resolve(".odd-lab/state"));
        Files.writeString(repository.resolve(".odd-lab/state/private.txt"), "no ocultar silenciosamente");
        git(repository, "add", ".odd-lab/state/private.txt");
        var before = snapshot(repository);
        assertError(invoke("init", "--repo", repository.toString(), "--json"), "OPERATIONAL_DATA_TRACKED", 2);
        assertThat(snapshot(repository)).isEqualTo(before);
    }

    @Test
    void supportsGitWorktreeWithGitfile() throws Exception {
        Path repository = repository();
        git(repository, "-c", "user.name=ODD Test", "-c", "user.email=odd@example.invalid", "commit", "--allow-empty", "-m", "fixture");
        Path worktree = temporary.resolve("worktree con espacios");
        git(repository, "worktree", "add", "--detach", worktree.toString());
        assertThat(worktree.resolve(".git")).isRegularFile();
        assertThat(invoke("init", "--repo", worktree.toString(), "--json").code()).isZero();
        assertThat(invoke("doctor", "--repo", worktree.toString(), "--json").code()).isZero();
        assertThat(repository.resolve(".odd-lab.yaml")).doesNotExist();
    }

    private Path repository() throws Exception {
        Path path = Files.createDirectory(temporary.resolve("repositorio con espacios"));
        git(path, "init", "--initial-branch=main");
        return path;
    }

    private Path initializedRepository() throws Exception {
        Path path = repository();
        assertThat(invoke("init", "--repo", path.toString()).code()).isZero();
        return path;
    }

    private Result invoke(String... args) throws Exception {
        return invokeWithEnvironment(Map.of(), args);
    }

    private Result invokeWithEnvironment(Map<String, String> environment, String... args) throws Exception {
        Path stdout = temporary.resolve("stdout-" + invocation);
        Path stderr = temporary.resolve("stderr-" + invocation++);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", Path.of(System.getProperty("oddLab.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(temporary.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private String git(Path root, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Path output = temporary.resolve("git-output");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String text = Files.readString(output);
            // An unborn repository has no log, which is expected in the no-commit assertion.
            assertThat(process.exitValue() == 0 || (args[0].equals("log") && text.isEmpty())).isTrue();
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Map<String, String> snapshot(Path root) throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) {
                    result.put(relative, "LINK:" + Files.readSymbolicLink(path));
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(relative, "DIRECTORY");
                } else {
                    result.put(relative, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
                            + ":" + Files.getLastModifiedTime(path).toMillis());
                }
            }
        }
        result.remove("");
        return result;
    }

    private void assertError(Result result, String code, int exitCode) {
        assertThat(result.code()).isEqualTo(exitCode);
        assertThat(result.stderr()).isEmpty();
        JsonNode document = json.readTree(result.stdout());
        assertThat(document.at("/error/code").asString()).isEqualTo(code);
        assertThat(document.path("pendingDecision").isNull()).isTrue();
    }

    private record Result(int code, String stdout, String stderr) {
    }
}
