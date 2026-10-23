package dev.oddlab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SimulationCommandsIT {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private Path root;
    private Path request;
    private int sequence;

    @BeforeEach
    void initialize() throws Exception {
        root = Files.createDirectory(temporary.resolve("repo simulado # con espacios"));
        var process = new ProcessBuilder("git", "-C", root.toString(), "init", "--quiet", "--initial-branch=main").start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(invoke("init", "--repo", root.toString(), "--json").code()).isZero();
        request = temporary.resolve("petición.md");
        Files.writeString(request, "Petición literal\nSin ejecución de comandos.\n");
        Files.writeString(root.resolve("code.txt"), "original");
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "retry"})
    void runCompletesSyntheticVerificationAndConsultationsRemainReadOnly(String scenario) throws Exception {
        var result = run(scenario);
        assertThat(result.code()).isZero();
        var document = json.readTree(result.stdout());
        assertThat(document.path("state").asString()).isEqualTo("COMPLETED");
        assertThat(document.at("/result/simulated").asBoolean()).isTrue();
        assertThat(document.at("/result/verificationStatus").asString()).isEqualTo("VALID");
        assertThat(document.at("/result/message").asString()).contains("no se ha implementado");
        String id = document.path("runId").asString();
        var before = snapshot();
        var inspected = invoke("inspect", "--repo", root.toString(), "--run", id, "--json");
        assertThat(inspected.code()).isZero();
        var detail = json.readTree(inspected.stdout()).path("result");
        assertThat(detail.path("request").asString()).isEqualTo(Files.readString(request));
        assertThat(detail.path("evidence").size()).isEqualTo(1);
        long starts = detail.path("tasks").valueStream().filter(entry -> "ATTEMPT_START".equals(entry.at("/data/recordType").asString())).count();
        assertThat(starts).isEqualTo(scenario.equals("retry") ? 2 : 1);
        assertThat(invoke("status", "--repo", root.toString(), "--run", id, "--json").code()).isZero();
        assertThat(invoke("doctor", "--repo", root.toString(), "--json").code()).isZero();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "unverified", "permission"})
    void failingScenarioDoesNotBecomeCompleted(String scenario) throws Exception {
        var result = run(scenario);
        assertThat(result.code()).isEqualTo(1);
        var document = json.readTree(result.stdout());
        assertThat(document.path("state").asString()).isEqualTo("FAILED");
        assertThat(document.at("/result/verificationStatus").asString()).isEqualTo("NOT_VERIFIED");
        String id = document.path("runId").asString();
        var detail = json.readTree(invoke("inspect", "--repo", root.toString(), "--run", id, "--json").stdout()).path("result");
        long starts = detail.path("tasks").valueStream().filter(entry -> "ATTEMPT_START".equals(entry.at("/data/recordType").asString())).count();
        assertThat(starts).isEqualTo(scenario.equals("permission") ? 1 : 3);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
    }

    @Test
    void jsonDecisionPersistsWithoutPromptOrAutomaticAuthorization() throws Exception {
        var pending = run("decision");
        assertThat(pending.code()).isEqualTo(3);
        var document = json.readTree(pending.stdout());
        String id = document.path("runId").asString();
        assertThat(document.path("state").asString()).isEqualTo("WAITING_INPUT");
        assertThat(document.path("pendingDecision").path("question").asString()).contains("Continuar");
        String version = document.at("/result/version").toString();
        var resumed = invoke("resume", "--repo", root.toString(), "--run", id, "--json");
        assertThat(resumed.code()).isEqualTo(3);
        var again = json.readTree(resumed.stdout());
        assertThat(again.path("pendingDecision")).isEqualTo(document.path("pendingDecision"));
        assertThat(again.at("/result/version").toString()).isEqualTo(version);
        var inspected = json.readTree(invoke("inspect", "--repo", root.toString(), "--run", id, "--json").stdout());
        assertThat(inspected.path("pendingDecision")).isEqualTo(document.path("pendingDecision"));
        assertThat(inspected.at("/result/tasks").valueStream().filter(entry -> "ATTEMPT_START".equals(entry.at("/data/recordType").asString())).count()).isZero();
        assertThat(invoke("resume", "--repo", root.toString(), "--run", id, "--answer", "yes", "--json").code()).isEqualTo(2);
    }

    @Test
    void runtimeMustBeExplicitAndUnavailableProvidersAreRejectedBeforeCreatingRun() throws Exception {
        assertThat(invoke("run", "--repo", root.toString(), "--request-file", request.toString(), "--json").code()).isEqualTo(2);
        assertThat(invoke("run", "--repo", root.toString(), "--request-file", request.toString(), "--runtime", "codex", "--json").code()).isEqualTo(2);
        assertThat(json.readTree(invoke("status", "--repo", root.toString(), "--json").stdout()).at("/result/runs").size()).isZero();
    }

    @Test
    void completedResumeRechecksWithoutAppendingAttemptsAndRepairsView() throws Exception {
        var document = json.readTree(run("success").stdout());
        String id = document.path("runId").asString();
        String version = document.at("/result/version").toString();
        Path view = root.resolve(".odd-lab/views/" + id + ".md");
        Files.writeString(view, "vista antigua");
        var resumed = invoke("resume", "--repo", root.toString(), "--run", id, "--json");
        assertThat(resumed.code()).isZero();
        assertThat(json.readTree(resumed.stdout()).at("/result/version").toString()).isEqualTo(version);
        assertThat(Files.readString(view)).contains("Versión: " + version).doesNotContain("vista antigua");
    }

    @Test
    void codeChangeMakesEvidenceStaleWithoutRewritingHistoricState() throws Exception {
        String id = json.readTree(run("success").stdout()).path("runId").asString();
        Files.writeString(root.resolve("code.txt"), "cambió después");
        var before = snapshot();
        var status = json.readTree(invoke("status", "--repo", root.toString(), "--run", id, "--json").stdout());
        assertThat(status.path("state").asString()).isEqualTo("COMPLETED");
        assertThat(status.at("/result/verificationStatus").asString()).isEqualTo("STALE");
        assertThat(invoke("status", "--repo", root.toString()).stdout()).contains("STALE");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(invoke("resume", "--repo", root.toString(), "--run", id, "--json").code()).isEqualTo(5);
    }

    @Test
    void changedReceiptIsNotAcceptedAsCurrentVerification() throws Exception {
        String id = json.readTree(run("success").stdout()).path("runId").asString();
        var inspected = json.readTree(invoke("inspect", "--repo", root.toString(), "--run", id, "--json").stdout());
        Path receipt = root.resolve(inspected.at("/result/evidence/0/data/location").asString());
        Files.writeString(receipt, "falso resultado");
        var status = json.readTree(invoke("status", "--repo", root.toString(), "--run", id, "--json").stdout());
        assertThat(status.at("/result/verificationStatus").asString()).isEqualTo("INVALID");
        assertThat(invoke("resume", "--repo", root.toString(), "--run", id, "--json").code()).isEqualTo(1);
    }

    @Test
    void invalidRequestDoesNotCreateAnExecution() throws Exception {
        Files.write(request, new byte[]{(byte) 0xff});
        var result = run("success");
        assertThat(result.code()).isEqualTo(2);
        assertThat(json.readTree(result.stdout()).at("/error/code").asString()).isEqualTo("REQUEST_INVALID");
        assertThat(json.readTree(invoke("status", "--repo", root.toString(), "--json").stdout()).at("/result/runs").size()).isZero();
    }

    private Result run(String scenario) throws Exception {
        return invoke("run", "--repo", root.toString(), "--request-file", request.toString(), "--runtime", "simulated", "--scenario", scenario, "--json");
    }

    private Result invoke(String... arguments) throws Exception {
        Path stdout = temporary.resolve("stdout-" + sequence);
        Path stderr = temporary.resolve("stderr-" + sequence++);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                Path.of(System.getProperty("oddLab.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(temporary.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).isTrue();
            String output = Files.readString(stdout);
            String error = Files.readString(stderr);
            if (List.of(arguments).contains("--json")) {
                assertThat(error).isEmpty();
                assertThat(output.lines().count()).isEqualTo(1);
                assertThat(json.readTree(output).path("schemaVersion").asInt()).isEqualTo(1);
            }
            return new Result(process.exitValue(), output, error);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private Map<String, String> snapshot() throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result.put(root.relativize(path).toString(),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) + ":" + Files.getLastModifiedTime(path).toMillis());
                else result.put(root.relativize(path).toString(), "DIRECTORY");
            }
        }
        return result;
    }

    private record Result(int code, String stdout, String stderr) {}
}
