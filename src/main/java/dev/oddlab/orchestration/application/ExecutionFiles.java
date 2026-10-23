package dev.oddlab.orchestration.application;

import dev.oddlab.work.domain.RunSnapshot;
import java.nio.file.Path;
import java.util.UUID;

public interface ExecutionFiles {
    String readRequest(Path file);
    String revision(Path repository);
    Artifact save(Path repository, UUID runId, String name, String content);
    boolean matches(Path repository, Artifact artifact);
    boolean receiptMatches(Path repository, Artifact artifact, String requestHash, String revision, UUID attemptId);
    void renderView(Path repository, RunSnapshot snapshot);

    record Artifact(String location, String sha256) {}
}
