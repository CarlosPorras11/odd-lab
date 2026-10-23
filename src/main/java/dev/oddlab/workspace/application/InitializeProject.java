package dev.oddlab.workspace.application;

import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.workspace.domain.ProjectConfiguration;
import java.nio.file.Path;
import java.util.UUID;

public final class InitializeProject {
    private final ProjectAccess access;

    public InitializeProject(ProjectAccess access) {
        this.access = access;
    }

    public Result execute(Path path) {
        Path root = access.requireRepositoryRoot(path);
        var existing = access.load(root);
        var configuration = existing.orElseGet(() -> new ProjectConfiguration(1, UUID.randomUUID(),
                root.getFileName().toString(), RepositoryPolicy.initial()));
        access.initialize(root, configuration);
        return new Result(root, configuration, existing.isEmpty());
    }

    public record Result(Path repository, ProjectConfiguration configuration, boolean created) {
    }
}
