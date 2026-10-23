package dev.oddlab.verification.application;

import dev.oddlab.orchestration.application.ExecutionFiles;
import dev.oddlab.orchestration.domain.ExecutionException;
import dev.oddlab.work.domain.RunEntry;
import dev.oddlab.work.domain.RunSnapshot;
import java.nio.file.Path;
import java.util.List;

public final class EvidenceInspector {
    private final ExecutionFiles files;

    public EvidenceInspector(ExecutionFiles files) { this.files = files; }

    public Assessment assess(Path repository, RunSnapshot snapshot) {
        var evidence = snapshot.entries().stream().filter(entry -> entry.kind() == RunEntry.Kind.EVIDENCE
                && "SIMULATION".equals(entry.data().get("scope")) && "true".equals(entry.data().get("verified"))).toList();
        if (evidence.isEmpty()) return new Assessment("NOT_VERIFIED", List.of());
        try {
            if (!files.revision(repository).equals(snapshot.run().baseRevision())) return new Assessment("STALE", evidence.stream().map(RunEntry::id).map(Object::toString).toList());
            boolean valid = evidence.stream().allMatch(entry -> snapshot.run().baseRevision().equals(entry.data().get("revision"))
                    && snapshot.run().requestSha256().equals(entry.data().get("requestSha256"))
                    && files.receiptMatches(repository, new ExecutionFiles.Artifact(entry.data().get("location"), entry.data().get("sha256")),
                    snapshot.run().requestSha256(), snapshot.run().baseRevision(), java.util.UUID.fromString(entry.data().get("attemptId")))
                    && files.matches(repository, new ExecutionFiles.Artifact(entry.data().get("verificationLocation"), entry.data().get("verificationSha256"))));
            var configuration = snapshot.entries().stream().filter(entry -> "RUN_CONFIGURATION".equals(entry.data().get("recordType"))).findFirst();
            valid = valid && configuration.isPresent() && files.matches(repository, new ExecutionFiles.Artifact(
                    configuration.get().data().get("requestLocation"), configuration.get().data().get("requestSha256")));
            return new Assessment(valid ? "VALID" : "INVALID", evidence.stream().map(RunEntry::id).map(Object::toString).toList());
        } catch (ExecutionException | IllegalArgumentException | NullPointerException exception) {
            return new Assessment("INVALID", evidence.stream().map(RunEntry::id).map(Object::toString).toList());
        }
    }

    public record Assessment(String status, List<String> evidenceIds) {}
}
