package dev.oddlab.workspace.infrastructure;

import dev.oddlab.workspace.application.ProjectAccess;
import dev.oddlab.workspace.domain.ProjectConfiguration;
import dev.oddlab.workspace.domain.ProjectException;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class LocalProjectAccess implements ProjectAccess {
    private static final List<String> DIRECTORIES = List.of("state", "artifacts", "workspaces", "views");
    private static final List<String> IGNORE_RULES = DIRECTORIES.stream().map(name -> "/.odd-lab/" + name + "/").toList();
    private final YamlProjectConfiguration yaml = new YamlProjectConfiguration();

    @Override
    public Path requireRepositoryRoot(Path path) {
        try {
            Path root = path.toRealPath();
            if (!Files.isDirectory(root)) throw invalidRepository();
            var result = git(root, "rev-parse", "--show-toplevel");
            if (result.exitCode() != 0) throw invalidRepository();
            String topLevel = result.output().replaceFirst("[\\r\\n]+$", "");
            if (!Files.isSameFile(root, Path.of(topLevel))) {
                throw new ProjectException("REPOSITORY_ROOT_REQUIRED", "--repo debe señalar la raíz del repositorio Git.", 2);
            }
            return root;
        } catch (IOException exception) {
            throw invalidRepository();
        }
    }

    @Override
    public Optional<ProjectConfiguration> load(Path root) {
        Path descriptor = root.resolve(".odd-lab.yaml");
        regularFileOrAbsent(descriptor);
        if (!Files.exists(descriptor)) return Optional.empty();
        try {
            if (Files.size(descriptor) > 65536) {
                throw new ProjectException("CONFIG_INVALID", "El descriptor supera el límite de 64 KiB.", 2);
            }
            return Optional.of(yaml.decode(Files.readString(descriptor, StandardCharsets.UTF_8)));
        } catch (MalformedInputException exception) {
            throw new ProjectException("CONFIG_INVALID", "El descriptor debe estar codificado en UTF-8.", 2);
        } catch (IOException exception) {
            throw ioFailure();
        }
    }

    @Override
    public void initialize(Path root, ProjectConfiguration configuration) {
        validateLayout(root);
        regularFileOrAbsent(root.resolve(".gitignore"));
        var existing = load(root);
        if (existing.isPresent() && !existing.get().equals(configuration)) {
            throw new ProjectException("CONFIG_CHANGED", "La configuración ha cambiado; vuelve a ejecutar init.", 2);
        }
        if (trackedOperationalData(root)) {
            throw new ProjectException("OPERATIONAL_DATA_TRACKED", "Hay datos de .odd-lab versionados; revísalos antes de inicializar.", 2);
        }
        try {
            Files.createDirectories(root.resolve(".odd-lab"));
            for (String name : DIRECTORIES) Files.createDirectories(root.resolve(".odd-lab").resolve(name));
            updateIgnore(root);
            if (existing.isEmpty()) {
                Files.writeString(root.resolve(".odd-lab.yaml"), yaml.encode(configuration), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (IOException exception) {
            throw ioFailure();
        }
    }

    @Override
    public List<Check> inspect(Path root) {
        validateLayout(root);
        regularFileOrAbsent(root.resolve(".gitignore"));
        var checks = new ArrayList<Check>();
        for (String name : DIRECTORIES) {
            Path directory = root.resolve(".odd-lab").resolve(name);
            boolean present = Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
            checks.add(new Check("DIRECTORY_" + name.toUpperCase(java.util.Locale.ROOT), present ? "OK" : "ERROR",
                    present ? ".odd-lab/" + name + " disponible." : "Falta .odd-lab/" + name + "; ejecuta init para repararlo."));
        }
        boolean ignored = ignoredOperationalData(root);
        checks.add(new Check("GIT_IGNORE", ignored ? "OK" : "ERROR", ignored
                ? "Datos operativos excluidos de Git." : "Exclusiones incompletas; ejecuta init para repararlas."));
        boolean tracked = trackedOperationalData(root);
        checks.add(new Check("TRACKED_DATA", tracked ? "ERROR" : "OK", tracked
                ? "Hay datos operativos versionados; revisa el índice Git." : "No hay datos operativos versionados."));
        return List.copyOf(checks);
    }

    private void validateLayout(Path root) {
        directoryOrAbsent(root.resolve(".odd-lab"));
        for (String name : DIRECTORIES) directoryOrAbsent(root.resolve(".odd-lab").resolve(name));
    }

    private void regularFileOrAbsent(Path path) {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw unsafePath();
    }

    private void directoryOrAbsent(Path path) {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) throw unsafePath();
    }

    private void updateIgnore(Path root) throws IOException {
        Path ignore = root.resolve(".gitignore");
        byte[] original = Files.exists(ignore) ? Files.readAllBytes(ignore) : new byte[0];
        String content = new String(original, StandardCharsets.UTF_8);
        if (content.lines().toList().containsAll(IGNORE_RULES) && ignoredOperationalData(root)) return;
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String prefix = original.length == 0 || original[original.length - 1] == '\n' ? "" : newline;
        byte[] addition = (prefix + "# Datos operativos de odd-lab" + newline
                + String.join(newline, IGNORE_RULES) + newline).getBytes(StandardCharsets.UTF_8);
        byte[] updated = Arrays.copyOf(original, original.length + addition.length);
        System.arraycopy(addition, 0, updated, original.length, addition.length);
        Path temporary = Files.createTempFile(root, ".odd-lab-ignore-", ".tmp");
        try {
            Files.write(temporary, updated);
            if (Files.exists(ignore) && Files.getFileAttributeView(ignore,
                    java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(ignore));
            }
            regularFileOrAbsent(ignore);
            byte[] current = Files.exists(ignore) ? Files.readAllBytes(ignore) : new byte[0];
            if (!Arrays.equals(original, current)) {
                throw new ProjectException("CONFIG_CHANGED", ".gitignore ha cambiado; vuelve a ejecutar init.", 2);
            }
            try {
                Files.move(temporary, ignore, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, ignore, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        if (!ignoredOperationalData(root)) {
            throw new ProjectException("IGNORE_RULES_INEFFECTIVE", "Reglas Git anidadas impiden excluir datos operativos; revísalas.", 2);
        }
    }

    private boolean ignoredOperationalData(Path root) {
        for (String directory : DIRECTORIES) {
            var result = git(root, "check-ignore", "--no-index", "--quiet", "--", ".odd-lab/" + directory + "/.odd-lab-probe");
            if (result.exitCode() == 1) return false;
            if (result.exitCode() != 0) throw new ProjectException("GIT_FAILED", "No se pudieron comprobar exclusiones Git.", 1);
        }
        return true;
    }

    private boolean trackedOperationalData(Path root) {
        var paths = DIRECTORIES.stream().map(name -> ".odd-lab/" + name + "/").toList();
        var arguments = new ArrayList<>(List.of("ls-files", "--"));
        arguments.addAll(paths);
        var result = git(root, arguments.toArray(String[]::new));
        if (result.exitCode() != 0) throw new ProjectException("GIT_FAILED", "No se pudo consultar el índice Git.", 1);
        return !result.output().isEmpty();
    }

    private GitResult git(Path root, String... arguments) {
        var command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = null;
        try {
            process = builder.start();
            // Drain stdout concurrently: a large index must not block ls-files on a full pipe.
            final Process active = process;
            var output = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var input = active.getInputStream()) {
                    output.write(input.readNBytes(65536));
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // Exit status and timeout are checked by the owning thread.
                }
            });
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new ProjectException("GIT_TIMEOUT", "La comprobación Git ha agotado el tiempo de espera.", 1);
            }
            reader.join(1000);
            if (reader.isAlive()) throw new ProjectException("GIT_TIMEOUT", "La salida Git no se ha cerrado.", 1);
            return new GitResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ProjectException("GIT_UNAVAILABLE", "No se pudo ejecutar Git; comprueba su instalación y permisos.", 2);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProjectException("GIT_INTERRUPTED", "Comprobación Git interrumpida.", 1);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private ProjectException invalidRepository() {
        return new ProjectException("INVALID_REPOSITORY", "--repo debe señalar un repositorio Git existente, no bare.", 2);
    }

    private ProjectException unsafePath() {
        return new ProjectException("UNSAFE_PROJECT_PATH", "Descriptor, .gitignore y rutas operativas deben ser archivos o directorios locales, sin enlaces simbólicos.", 2);
    }

    private ProjectException ioFailure() {
        return new ProjectException("PROJECT_IO_ERROR", "No se pudo leer o preparar el proyecto. Revisa permisos y espacio; init permite reparar una preparación parcial.", 1);
    }

    private record GitResult(int exitCode, String output) {
    }
}
