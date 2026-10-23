package dev.oddlab.workspace.application;

import dev.oddlab.workspace.domain.ProjectConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ProjectAccess {
    Path requireRepositoryRoot(Path path);

    Optional<ProjectConfiguration> load(Path root);

    void initialize(Path root, ProjectConfiguration configuration);

    List<Check> inspect(Path root);

    record Check(String code, String status, String message) {
    }
}
