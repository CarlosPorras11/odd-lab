package dev.oddlab.control.infrastructure;

import dev.oddlab.control.application.RunControls;
import dev.oddlab.orchestration.domain.ExecutionException;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalRunControlsTest {
    @TempDir Path root;
    @Test void ownershipExcludesAnotherInstanceAndReleasesWithoutDeletingLock() throws Exception {
        Files.createDirectories(root.resolve(".odd-lab/state"));
        var id = UUID.randomUUID(); var repo = UUID.randomUUID();
        var controls = new LocalRunControls();
        try (var lease = controls.claim(root, repo, id)) {
            assertThatThrownBy(() -> new LocalRunControls().claim(root, repo, id)).isInstanceOf(ExecutionException.class)
                    .extracting(value -> ((ExecutionException) value).code()).isEqualTo("RUN_OWNED");
        }
        Path lock = root.resolve(".odd-lab/state/control/" + id + "/owner.lock");
        assertThat(lock).exists();
        try (var lease = controls.claim(root, repo, id)) { assertThat(Files.readString(lock)).contains("startedAt", "token", repo.toString()); }
    }
    @Test void requestsPersistWithIdentityAndScopeAndDoNotNeedOwnership() throws Exception {
        Files.createDirectories(root.resolve(".odd-lab/state"));
        var id = UUID.randomUUID(); var repo = UUID.randomUUID(); var controls = new LocalRunControls();
        try (var lease = controls.claim(root, repo, id)) {
            var request = controls.request(root, repo, id, RunControls.Action.CANCEL);
            assertThat(new LocalRunControls().requests(root, repo, id)).containsExactly(request);
            assertThatThrownBy(() -> controls.requests(root, UUID.randomUUID(), id)).isInstanceOf(ExecutionException.class);
        }
    }
    @Test void symbolicControlDirectoryCannotRedirectWrites() throws Exception {
        Files.createDirectories(root.resolve(".odd-lab/state"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve(".odd-lab/state/control"), outside);
        assertThatThrownBy(() -> new LocalRunControls().claim(root, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(ExecutionException.class);
        try (var files = Files.list(outside)) { assertThat(files).isEmpty(); }
    }
    @Test void readingAbsentMailboxDoesNotInitializeIt() throws Exception {
        Files.createDirectories(root.resolve(".odd-lab/state"));
        assertThat(new LocalRunControls().requests(root, UUID.randomUUID(), UUID.randomUUID())).isEmpty();
        assertThat(root.resolve(".odd-lab/state/control")).doesNotExist();
    }
    @Test void malformedMailboxFailsClosed() throws Exception {
        Files.createDirectories(root.resolve(".odd-lab/state"));
        var id = UUID.randomUUID(); var repo = UUID.randomUUID(); var controls = new LocalRunControls();
        var request = controls.request(root, repo, id, RunControls.Action.PAUSE);
        Files.writeString(root.resolve(".odd-lab/state/control/" + id + "/" + request.id() + ".json"), "{}");
        assertThatThrownBy(() -> controls.requests(root, repo, id)).isInstanceOf(ExecutionException.class);
    }
}
