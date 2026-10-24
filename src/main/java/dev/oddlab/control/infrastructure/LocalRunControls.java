package dev.oddlab.control.infrastructure;

import dev.oddlab.control.application.RunControls;
import dev.oddlab.orchestration.domain.ExecutionException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** OS lock is authoritative. Never unlink a lock file or steal ownership by timeout/PID. */
public final class LocalRunControls implements RunControls {
    private final JsonMapper json = JsonMapper.builder().build();

    @Override public Lease claim(Path root, UUID repositoryId, UUID runId) {
        FileChannel channel = null;
        try {
            Path folder = folder(root, runId, true);
            Path lockPath = safeFile(folder.resolve("owner.lock"));
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException exception) { lock = null; }
            if (lock == null) { channel.close(); throw new ExecutionException("RUN_OWNED", "Otra instancia tiene la propiedad de esta ejecución.", 5); }
            var held = channel;
            try {
                var self = ProcessHandle.current();
                byte[] owner = json.writeValueAsBytes(Map.of("repositoryId", repositoryId.toString(), "runId", runId.toString(),
                        "token", UUID.randomUUID().toString(), "pid", self.pid(), "startedAt", self.info().startInstant().orElseThrow().toString(),
                        "acquiredAt", Instant.now().toString()));
                channel.truncate(0); var buffer = ByteBuffer.wrap(owner); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            } catch (Exception exception) { lock.release(); throw exception; }
            var acquired = lock;
            return () -> { try { acquired.release(); held.close(); } catch (Exception exception) { throw failure(); } };
        } catch (ExecutionException exception) { throw exception; }
        catch (Exception exception) {
            if (channel != null) try { channel.close(); } catch (Exception ignored) { }
            throw failure();
        }
    }

    @Override public Request request(Path root, UUID repositoryId, UUID runId, Action action) {
        var request = new Request(UUID.randomUUID(), repositoryId, runId, action, Instant.now());
        Path temp = null;
        try {
            Path folder = folder(root, runId, true);
            temp = Files.createTempFile(folder, ".request-", ".tmp");
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(json.writeValueAsBytes(request)); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temp, safeFile(folder.resolve(request.id() + ".json")), StandardCopyOption.ATOMIC_MOVE);
            return request;
        } catch (Exception exception) { throw failure(); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
    }

    @Override public List<Request> requests(Path root, UUID repositoryId, UUID runId) {
        var result = new ArrayList<Request>();
        try {
            Path folder = folder(root, runId, false);
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
            try (var paths = Files.newDirectoryStream(folder, "*.json")) {
            for (Path path : paths) {
                safeFile(path);
                if (Files.size(path) > 4096) throw failure();
                Request request = json.readValue(Files.readAllBytes(path), Request.class);
                if (!request.repositoryId().equals(repositoryId) || !request.runId().equals(runId)
                        || !path.getFileName().toString().equals(request.id() + ".json") || request.action() == null
                        || request.createdAt() == null) throw failure();
                result.add(request);
            }
            }
        } catch (Exception exception) { throw failure(); }
        return result.stream().sorted(Comparator.comparing(Request::createdAt).thenComparing(Request::id)).toList();
    }

    private Path folder(Path root, UUID runId, boolean create) throws java.io.IOException {
        Path folder = root;
        for (String part : List.of(".odd-lab", "state", "control", runId.toString())) {
            folder = folder.resolve(part);
            if (Files.isSymbolicLink(folder)) throw failure();
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                if (part.equals(".odd-lab") || part.equals("state")) throw failure();
                if (!create) return folder;
                try { Files.createDirectory(folder); } catch (FileAlreadyExistsException ignored) { }
            }
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw failure();
        }
        return folder;
    }
    private Path safeFile(Path path) {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw failure();
        return path;
    }
    private ExecutionException failure() { return new ExecutionException("CONTROL_STORAGE_INVALID", "No se puede comprobar el buzón o bloqueo de control; revisa sus rutas y contenido.", 5); }
}
