package dev.oddlab.workspace.application;

import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.work.application.RunJournal;
import dev.oddlab.workspace.domain.ProjectConfiguration;
import java.nio.file.Path;
import java.util.UUID;

public final class InitializeProject {
    private final ProjectAccess access;
    private final RunJournal journal;

    public InitializeProject(ProjectAccess access, RunJournal journal) {
        this.access = access;
        this.journal = journal;
    }

    public Result execute(Path path) {
        Path root = access.requireRepositoryRoot(path);
        var existing = access.load(root);
        var configuration = existing.orElseGet(() -> new ProjectConfiguration(1, UUID.randomUUID(),
                root.getFileName().toString(), RepositoryPolicy.initial()));
        access.initialize(root, configuration);
        journal.initialize(root, configuration.repositoryId());
        return new Result(root, configuration, existing.isEmpty());
    }

    public record Result(Path repository, ProjectConfiguration configuration, boolean created) {
    }
}
