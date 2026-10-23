package dev.oddlab.work.application;

import dev.oddlab.work.domain.RunRecord;
import dev.oddlab.work.domain.RunEntry;
import dev.oddlab.work.domain.RunSnapshot;
import dev.oddlab.work.domain.RunState;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface RunJournal {
    void initialize(Path repository, UUID repositoryId);

    void check(Path repository, UUID repositoryId);

    List<RunRecord> list(Path repository, UUID repositoryId);

    RunSnapshot inspect(Path repository, UUID repositoryId, UUID runId);

    void create(Path repository, RunRecord run);

    void transition(Path repository, UUID repositoryId, UUID runId, long expectedVersion,
                    RunState target, String actor, String reason);

    void append(Path repository, UUID repositoryId, UUID runId, long expectedVersion, RunEntry entry, String actor);
}
