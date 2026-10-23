package dev.oddlab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.oddlab.memory.infrastructure.SqliteRunJournal;
import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.work.domain.*;
import dev.oddlab.workspace.infrastructure.LocalProjectAccess;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class JournalCommandsIT {
    @TempDir
    Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SqliteRunJournal journal = new SqliteRunJournal();
    private int sequence;

    @Test
    void initializedProjectHasEmptyReadOnlyStatusAndHealthyStorage() throws Exception {
        Path root = repository();
        var before = snapshot(root);
        var result = invoke("status", "--repo", root.toString(), "--json");
        assertThat(result.exitCode()).isZero();
        assertThat(json.readTree(result.stdout()).at("/result/runs").size()).isZero();
        assertThat(json.readTree(result.stdout()).path("error").isNull()).isTrue();
        assertThat(invoke("status", "--repo", root.toString()).stdout()).contains("Sin ejecuciones");
        var doctor = invoke("doctor", "--repo", root.toString(), "--json");
        assertThat(doctor.exitCode()).isZero();
        assertThat(doctor.stdout()).contains("STORAGE", "SQLite v1", "NOT_CONFIGURED").doesNotContain("NOT_IMPLEMENTED");
        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void statusOfFailedRunSucceedsAndInspectShowsRegisteredDataWithoutWrites() throws Exception {
        Path root = repository();
        RunRecord run = register(root, "Petición original\ncon acentos y salto final.\n");
        long version = 0;
        for (var kind : RunEntry.Kind.values()) {
            Map<String, String> data = kind == RunEntry.Kind.EVIDENCE ? Map.of("location", "artifacts/test.txt",
                    "revision", "revision-sintetica", "sha256", "b".repeat(64)) : Map.of("text", "dato de " + kind);
            journal.append(root, run.repositoryId(), run.runId(), version++, new RunEntry(UUID.randomUUID(), kind, 1, "Título " + kind, data), "test");
        }
        journal.transition(root, run.repositoryId(), run.runId(), version, RunState.FAILED, "test", "fallo controlado");
        var before = snapshot(root);
        var status = invoke("status", "--repo", root.toString(), "--run", run.runId().toString(), "--json");
        assertThat(status.exitCode()).isZero();
        var statusJson = json.readTree(status.stdout());
        assertThat(statusJson.path("state").asString()).isEqualTo("FAILED");
        assertThat(statusJson.path("runId").asString()).isEqualTo(run.runId().toString());
        assertThat(statusJson.at("/result/version").asInt()).isEqualTo(5);
        var inspected = invoke("inspect", "--repo", root.toString(), "--run", run.runId().toString(), "--json");
        assertThat(inspected.exitCode()).isZero();
        var result = json.readTree(inspected.stdout()).path("result");
        assertThat(result.path("request").asString()).isEqualTo(run.request());
        assertThat(result.path("requestSha256").asString()).isEqualTo(run.requestSha256());
        for (String key : List.of("requirements", "tasks", "decisions", "evidence")) assertThat(result.path(key).size()).isEqualTo(1);
        assertThat(result.path("events").size()).isEqualTo(6);
        assertThat(result.path("policy").path("allowedProviders").size()).isZero();
        assertThat(invoke("inspect", "--repo", root.toString(), "--run", run.runId().toString()).stdout())
                .contains(run.request(), "REQUIREMENT", "TASK", "DECISION", "EVIDENCE", "fallo controlado");
        assertThat(invoke("status", "--repo", root.toString()).stdout()).contains(run.runId().toString(), "FAILED");
        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void queriesAndDoctorDoNotMigrateE02ProjectUntilInitIsExplicit() throws Exception {
        Path root = repository();
        Path database = database(root);
        Files.delete(database);
        var before = snapshot(root);
        assertError(invoke("status", "--repo", root.toString(), "--json"), "STORAGE_NOT_INITIALIZED", 2);
        var doctor = invoke("doctor", "--repo", root.toString(), "--json");
        assertThat(doctor.exitCode()).isEqualTo(2);
        assertThat(doctor.stdout()).contains("STORAGE_NOT_INITIALIZED");
        assertThat(snapshot(root)).isEqualTo(before);
        assertThat(invoke("init", "--repo", root.toString()).exitCode()).isZero();
        assertThat(database).isRegularFile();
        assertThat(invoke("status", "--repo", root.toString()).exitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "inspect"})
    void usageAndMissingRunAreStructuredAndDoNotCreateStorage(String command) throws Exception {
        assertError(invoke(command, "--json"), "USAGE_INVALID", 2);
        var help = invoke(command, "--help");
        assertThat(help.exitCode()).isZero();
        assertThat(help.stdout()).contains("--run", "--repo", "--json");
        Path root = repository();
        var before = snapshot(root);
        assertError(invoke(command, "--repo", root.toString(), "--run", "no-es-uuid", "--json"), "USAGE_INVALID", 2);
        assertError(invoke(command, "--repo", root.toString(), "--run", UUID.randomUUID().toString(), "--json"), "RUN_NOT_FOUND", 2);
        if (command.equals("inspect")) assertError(invoke(command, "--repo", root.toString(), "--json"), "USAGE_INVALID", 2);
        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void databasesAndRunIdsRemainScopedToTheirRepository() throws Exception {
        Path first = repository();
        Path second = repository();
        var firstRun = register(first, "Solo primer repositorio");
        register(second, "Solo segundo repositorio");
        var result = invoke("status", "--repo", second.toString(), "--json");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).doesNotContain(firstRun.runId().toString());
        assertError(invoke("inspect", "--repo", second.toString(), "--run", firstRun.runId().toString(), "--json"), "RUN_NOT_FOUND", 2);
        Files.copy(database(first), database(second), StandardCopyOption.REPLACE_EXISTING);
        var before = snapshot(second);
        assertError(invoke("status", "--repo", second.toString(), "--json"), "STORAGE_SCOPE_MISMATCH", 2);
        assertError(invoke("init", "--repo", second.toString(), "--json"), "STORAGE_SCOPE_MISMATCH", 2);
        assertThat(snapshot(second)).isEqualTo(before);
    }

    @Test
    void refusesUnsupportedVersionWithoutModifyingDatabase() throws Exception {
        Path root = repository();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database(root)); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }
        var before = snapshot(root);
        assertError(invoke("status", "--repo", root.toString(), "--json"), "STORAGE_VERSION_UNSUPPORTED", 2);
        assertError(invoke("init", "--repo", root.toString(), "--json"), "STORAGE_VERSION_UNSUPPORTED", 2);
        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void corruptStorageIsReportedWithoutOverwritingIt() throws Exception {
        Path root = repository();
        Files.writeString(database(root), "registro dañado");
        var before = snapshot(root);
        assertError(invoke("status", "--repo", root.toString(), "--json"), "STORAGE_FAILURE", 1);
        assertThat(invoke("doctor", "--repo", root.toString()).exitCode()).isEqualTo(2);
        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void refusesSymbolicDatabaseWithoutChangingItsTarget() throws Exception {
        Path root = repository();
        Path outside = temporary.resolve("outside.sqlite");
        Files.move(database(root), outside);
        Files.createSymbolicLink(database(root), outside);
        byte[] before = Files.readAllBytes(outside);
        assertError(invoke("status", "--repo", root.toString(), "--json"), "UNSAFE_STORAGE_PATH", 2);
        assertError(invoke("init", "--repo", root.toString(), "--json"), "UNSAFE_STORAGE_PATH", 2);
        assertThat(Files.readAllBytes(outside)).isEqualTo(before);
    }

    private RunRecord register(Path root, String request) {
        var configuration = new LocalProjectAccess().load(root).orElseThrow();
        Instant now = Instant.now();
        var run = new RunRecord(UUID.randomUUID(), configuration.repositoryId(), "test", request, "revision-sintetica",
                RepositoryPolicy.initial(), RunState.CREATED, 0, now, now);
        journal.create(root, run);
        return run;
    }

    private Path repository() throws Exception {
        Path root = Files.createTempDirectory(temporary, "repo con espacios #");
        var process = new ProcessBuilder("git", "-C", root.toString(), "init", "--quiet", "--initial-branch=main")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(invoke("init", "--repo", root.toString()).exitCode()).isZero();
        return root;
    }

    private Path database(Path root) {
        return root.resolve(".odd-lab/state").resolve(SqliteRunJournal.DATABASE);
    }

    private Result invoke(String... arguments) throws Exception {
        Path stdout = temporary.resolve("stdout-" + sequence);
        Path stderr = temporary.resolve("stderr-" + sequence++);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", Path.of(System.getProperty("oddLab.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(temporary.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("El comando debe finalizar").isTrue();
            String error = Files.readString(stderr);
            if (List.of(arguments).contains("--json")) assertThat(error).isEmpty();
            return new Result(process.exitValue(), Files.readString(stdout), error);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Map<String, String> snapshot(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(root.relativize(path).toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path))) + ":" + Files.getLastModifiedTime(path).toMillis());
                } else {
                    result.put(root.relativize(path).toString(), Files.isSymbolicLink(path) ? Files.readSymbolicLink(path).toString() : "DIRECTORY");
                }
            }
        }
        return result;
    }

    private void assertError(Result result, String code, int expectedExitCode) {
        assertThat(result.exitCode()).isEqualTo(expectedExitCode);
        assertThat(json.readTree(result.stdout()).at("/error/code").asString()).isEqualTo(code);
        assertThat(result.stderr()).isEmpty();
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
