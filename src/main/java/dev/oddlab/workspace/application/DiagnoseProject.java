package dev.oddlab.workspace.application;

import dev.oddlab.workspace.domain.ProjectConfiguration;
import dev.oddlab.workspace.domain.ProjectException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DiagnoseProject {
    private final ProjectAccess access;

    public DiagnoseProject(ProjectAccess access) {
        this.access = access;
    }

    public Result execute(Path path) {
        Path root = access.requireRepositoryRoot(path);
        var configuration = access.load(root).orElseThrow(() -> new ProjectException(
                "CONFIG_NOT_FOUND", "Falta .odd-lab.yaml; ejecuta init --repo <ruta>.", 2));
        var checks = new ArrayList<ProjectAccess.Check>();
        checks.add(new ProjectAccess.Check("GIT_ROOT", "OK", "Raíz de repositorio Git comprobada."));
        checks.add(new ProjectAccess.Check("CONFIGURATION", "OK", "Identidad y política v1 válidas."));
        checks.addAll(access.inspect(root));
        checks.add(new ProjectAccess.Check("STORAGE", "NOT_IMPLEMENTED", "Registro transaccional pendiente de E-03."));
        checks.add(new ProjectAccess.Check("RUNTIME", "NOT_CONFIGURED", "Sin runtime conectado; no se ha iniciado inferencia."));
        return new Result(root, configuration, List.copyOf(checks));
    }

    public record Result(Path repository, ProjectConfiguration configuration, List<ProjectAccess.Check> checks) {
        public boolean healthy() {
            return checks.stream().noneMatch(check -> check.status().equals("ERROR"));
        }
    }
}
