package dev.oddlab.workspace.infrastructure;

import dev.oddlab.orchestration.application.ExecutionFiles;
import dev.oddlab.orchestration.domain.ExecutionException;
import dev.oddlab.work.domain.RunSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

public final class LocalExecutionFiles implements ExecutionFiles {
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String readRequest(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024) throw invalidRequest();
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(1024 * 1024 + 1); }
            if (bytes.length > 1024 * 1024) throw invalidRequest();
            String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (content.replace("\uFEFF", "").isBlank()) throw invalidRequest();
            return content;
        } catch (IOException exception) {
            throw invalidRequest();
        }
    }

    @Override
    public String revision(Path repository) {
        try {
            Path root = repository.toRealPath();
            var head = git(root, "rev-parse", "--verify", "--quiet", "HEAD");
            String identity;
            if (head.code() == 0) identity = new String(head.output(), StandardCharsets.UTF_8).strip();
            else {
                var branch = git(root, "symbolic-ref", "--quiet", "HEAD");
                if (head.code() != 1 || branch.code() != 0) throw gitFailure();
                identity = "UNBORN:" + new String(branch.output(), StandardCharsets.UTF_8).strip();
            }
            var listed = git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z");
            if (listed.code() != 0) throw gitFailure();
            var paths = new TreeSet<>(Arrays.asList(new String(listed.output(), StandardCharsets.UTF_8).split("\u0000")));
            paths.add(".odd-lab.yaml");
            var digest = digest();
            update(digest, identity);
            for (String name : paths) {
                if (name.isEmpty() || name.startsWith(".odd-lab/")) continue;
                Path path = root.resolve(name).normalize();
                if (!path.startsWith(root) || name.equals(".git") || name.startsWith(".git/")) throw gitFailure();
                Path parent = root;
                for (Path part : root.relativize(path.getParent())) {
                    parent = parent.resolve(part);
                    if (Files.isSymbolicLink(parent)) throw unsafe();
                }
                update(digest, name);
                if (Files.isSymbolicLink(path)) {
                    update(digest, "LINK:" + Files.readSymbolicLink(path));
                } else if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    update(digest, "MISSING");
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    update(digest, Files.isExecutable(path) ? "EXECUTABLE" : "FILE");
                    update(digest, hash(path));
                } else {
                    throw new ExecutionException("UNSUPPORTED_REVISION_PATH", "No se puede verificar esta revisión: contiene una ruta no regular o un submódulo.", 2);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw gitFailure();
        }
    }

    @Override
    public Artifact save(Path repository, UUID runId, String name, String content) {
        if (!name.matches("[a-zA-Z0-9-]+\\.(txt|json)")) throw unsafe();
        try {
            Path base = area(repository, "artifacts");
            Path directory = base.resolve(runId.toString());
            directoryOrAbsent(directory);
            if (!Files.exists(directory)) Files.createDirectory(directory);
            Path destination = directory.resolve(name);
            regularOrAbsent(destination);
            if (Files.exists(destination)) throw new ExecutionException("ARTIFACT_EXISTS", "El artefacto ya existe; no se sobrescribirá.", 1);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1024 * 1024) throw new ExecutionException("ARTIFACT_TOO_LARGE", "El artefacto supera el presupuesto local.", 1);
            Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
            try {
                Files.write(temporary, bytes);
                String checksum = hash(temporary);
                Files.move(temporary, destination);
                return new Artifact(repository.toAbsolutePath().normalize().relativize(destination.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'), checksum);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw ioFailure();
        }
    }

    @Override
    public boolean matches(Path repository, Artifact artifact) {
        try {
            Path path = artifactPath(repository, artifact.location());
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1024 * 1024
                    && artifact.sha256().equals(hash(path));
        } catch (IOException exception) {
            return false;
        }
    }

    @Override
    public boolean receiptMatches(Path repository, Artifact artifact, String requestHash, String revision, UUID attemptId) {
        if (!matches(repository, artifact)) return false;
        try {
            var document = json.readTree(Files.readString(artifactPath(repository, artifact.location()), StandardCharsets.UTF_8));
            return document.path("schemaVersion").asInt() == 1 && document.path("simulated").asBoolean()
                    && document.path("kind").asString().equals("SIMULATED_RECEIPT")
                    && document.path("requestSha256").asString().equals(requestHash)
                    && document.path("revision").asString().equals(revision)
                    && document.path("attemptId").asString().equals(attemptId.toString());
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            return false;
        }
    }

    @Override
    public void renderView(Path repository, RunSnapshot snapshot) {
        try {
            Path base = area(repository, "views");
            Path destination = base.resolve(snapshot.run().runId() + ".md");
            regularOrAbsent(destination);
            String content = "# Vista de ejecución " + snapshot.run().runId() + "\n\n"
                    + "Vista regenerable; el registro SQLite es autoritativo.\n\n"
                    + "- Versión: " + snapshot.run().version() + "\n- Estado: " + snapshot.run().state()
                    + "\n- Revisión: " + snapshot.run().baseRevision() + "\n- Entradas: " + snapshot.entries().size()
                    + "\n- Eventos: " + snapshot.events().size() + "\n\nSimulación local; no acredita implementación de la petición.\n";
            Path temporary = Files.createTempFile(base, ".view-", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw ioFailure();
        }
    }

    private Path artifactPath(Path repository, String location) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        Path base = area(root, "artifacts");
        Path path = root.resolve(location).normalize();
        if (!path.startsWith(base) || path.equals(base)) throw unsafe();
        Path parent = path.getParent();
        for (Path part : base.relativize(parent)) {
            base = base.resolve(part);
            directoryOrAbsent(base);
        }
        regularOrAbsent(path);
        return path;
    }

    private Path area(Path repository, String name) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        for (Path directory : List.of(root.resolve(".odd-lab"), root.resolve(".odd-lab").resolve(name))) {
            directoryOrAbsent(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unsafe();
        }
        return root.resolve(".odd-lab").resolve(name);
    }

    private void directoryOrAbsent(Path path) {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) throw unsafe();
    }

    private void regularOrAbsent(Path path) {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw unsafe();
    }

    private String hash(Path path) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private GitResult git(Path root, String... arguments) throws IOException {
        Path output = Files.createTempFile("odd-revision-", ".txt");
        Process process = null;
        try {
            var command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false"));
            command.addAll(List.of(arguments));
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || Files.size(output) > 16 * 1024 * 1024) throw gitFailure();
            return new GitResult(process.exitValue(), Files.readAllBytes(output));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw gitFailure();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private ExecutionException invalidRequest() { return new ExecutionException("REQUEST_INVALID", "La petición debe ser un archivo UTF-8 no vacío de hasta 1 MiB.", 2); }
    private ExecutionException gitFailure() { return new ExecutionException("REVISION_UNAVAILABLE", "No se pudo obtener una revisión verificable del repositorio.", 2); }
    private ExecutionException unsafe() { return new ExecutionException("UNSAFE_ARTIFACT_PATH", "Artefactos y vistas deben permanecer en sus áreas locales, sin enlaces simbólicos.", 2); }
    private ExecutionException ioFailure() { return new ExecutionException("ARTIFACT_IO_ERROR", "No se pudo publicar o leer el artefacto; conserva el registro para revisión.", 1); }
    private record GitResult(int code, byte[] output) {}
}
