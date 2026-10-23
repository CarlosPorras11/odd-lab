package dev.oddlab.work.application;

import dev.oddlab.work.domain.RunRecord;
import dev.oddlab.work.domain.RunSnapshot;
import dev.oddlab.workspace.application.ProjectAccess;
import dev.oddlab.workspace.domain.ProjectException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class QueryRuns {
    private final ProjectAccess projects;
    private final RunJournal journal;

    public QueryRuns(ProjectAccess projects, RunJournal journal) {
        this.projects = projects;
        this.journal = journal;
    }

    public List<RunRecord> list(Path repository) {
        Path root = projects.requireRepositoryRoot(repository);
        var configuration = projects.load(root).orElseThrow(this::missingConfiguration);
        return journal.list(root, configuration.repositoryId());
    }

    public RunSnapshot inspect(Path repository, UUID runId) {
        Path root = projects.requireRepositoryRoot(repository);
        var configuration = projects.load(root).orElseThrow(this::missingConfiguration);
        return journal.inspect(root, configuration.repositoryId(), runId);
    }

    private ProjectException missingConfiguration() {
        return new ProjectException("CONFIG_NOT_FOUND", "Falta .odd-lab.yaml; ejecuta init --repo <ruta>.", 2);
    }
}
