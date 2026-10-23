package dev.oddlab.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.oddlab.memory.infrastructure.SqliteRunJournal;
import dev.oddlab.orchestration.application.ExecutionFiles;
import dev.oddlab.orchestration.domain.ExecutionException;
import dev.oddlab.workspace.application.InitializeProject;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalExecutionFilesTest {
    @TempDir Path root;
    private final LocalExecutionFiles files = new LocalExecutionFiles();

    @BeforeEach
    void initialize() throws Exception {
        git("init", "--quiet", "--initial-branch=main");
        new InitializeProject(new LocalProjectAccess(), new SqliteRunJournal()).execute(root);
    }

    @Test
    void fingerprintChangesWithCodeAndIgnoresOperationalArtifacts() throws Exception {
        Files.writeString(root.resolve("code.txt"), "a");
        String original = files.revision(root);
        files.save(root, UUID.randomUUID(), "receipt.json", "{}");
        assertThat(files.revision(root)).isEqualTo(original);
        Files.writeString(root.resolve("code.txt"), "b");
        assertThat(files.revision(root)).isNotEqualTo(original);
        git("add", "code.txt");
        String tracked = files.revision(root);
        Files.delete(root.resolve("code.txt"));
        assertThat(files.revision(root)).isNotEqualTo(tracked);
    }

    @Test
    void publishesThenHashesWithoutOverwritingExistingArtifact() throws Exception {
        UUID runId = UUID.randomUUID();
        var artifact = files.save(root, runId, "receipt.json", "{\"simulated\":true}");
        assertThat(files.matches(root, artifact)).isTrue();
        assertThatThrownBy(() -> files.save(root, runId, "receipt.json", "alterado"))
                .isInstanceOf(ExecutionException.class);
        Files.writeString(root.resolve(artifact.location()), "alterado");
        assertThat(files.matches(root, artifact)).isFalse();
        try (var paths = Files.list(root.resolve(artifact.location()).getParent())) {
            assertThat(paths.map(path -> path.getFileName().toString())).containsExactly("receipt.json");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "/private/tmp/outside.txt", ".odd-lab/views/outside.txt"})
    void rejectsArtifactLocationsOutsideArtifactArea(String location) {
        assertThatThrownBy(() -> files.matches(root, new ExecutionFiles.Artifact(location, "0".repeat(64))))
                .isInstanceOf(ExecutionException.class).extracting(exception -> ((ExecutionException) exception).code()).isEqualTo("UNSAFE_ARTIFACT_PATH");
    }

    @Test
    void refusesArtifactDirectorySymlinkWithoutWritingOutside() throws Exception {
        Path outside = Files.createTempDirectory("odd-artifact-outside-");
        UUID run = UUID.randomUUID();
        Path link = root.resolve(".odd-lab/artifacts").resolve(run.toString());
        try {
            Files.createSymbolicLink(link, outside);
            assertThatThrownBy(() -> files.save(root, run, "receipt.json", "{}"))
                    .isInstanceOf(ExecutionException.class);
            try (var children = Files.list(outside)) { assertThat(children.toList()).isEmpty(); }
        } finally { Files.deleteIfExists(outside); }
    }

    @Test
    void fingerprintDoesNotFollowReplacedAncestorOfTrackedFile() throws Exception {
        Path directory = Files.createDirectory(root.resolve("src"));
        Files.writeString(directory.resolve("code.txt"), "original");
        git("add", "src/code.txt");
        Files.delete(directory.resolve("code.txt"));
        Files.delete(directory);
        Path outside = Files.createTempDirectory("odd-source-outside-");
        try {
            Files.writeString(outside.resolve("code.txt"), "externo");
            Files.createSymbolicLink(directory, outside);
            assertThatThrownBy(() -> files.revision(root)).isInstanceOf(ExecutionException.class);
            assertThat(Files.readString(outside.resolve("code.txt"))).isEqualTo("externo");
        } finally {
            Files.deleteIfExists(outside.resolve("code.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsInvalidUtf8EmptyAndOversizedRequests() throws Exception {
        Path request = root.resolve("request.txt");
        Files.write(request, new byte[]{(byte) 0xff});
        assertThatThrownBy(() -> files.readRequest(request)).isInstanceOf(ExecutionException.class);
        Files.writeString(request, "\uFEFF \n");
        assertThatThrownBy(() -> files.readRequest(request)).isInstanceOf(ExecutionException.class);
        Files.write(request, new byte[1024 * 1024 + 1]);
        assertThatThrownBy(() -> files.readRequest(request)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void fingerprintAlwaysIncludesPolicyDescriptorEvenWhenIgnoredByGit() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "\n/.odd-lab.yaml\n", StandardOpenOption.APPEND);
        String before = files.revision(root);
        Path descriptor = root.resolve(".odd-lab.yaml");
        Files.writeString(descriptor, Files.readString(descriptor).replace("maxCorrectionsPerTask: 2", "maxCorrectionsPerTask: 1"));
        assertThat(files.revision(root)).isNotEqualTo(before);
    }

    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }
}
