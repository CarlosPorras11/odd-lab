package dev.oddlab.control.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operational mailbox; acknowledgements and business state belong to RunJournal. */
public interface RunControls {
    Lease claim(Path repository, UUID repositoryId, UUID runId);
    Request request(Path repository, UUID repositoryId, UUID runId, Action action);
    List<Request> requests(Path repository, UUID repositoryId, UUID runId);
    enum Action { PAUSE, CANCEL }
    record Request(UUID id, UUID repositoryId, UUID runId, Action action, Instant createdAt) {}
    interface Lease extends AutoCloseable { @Override void close(); }
}
