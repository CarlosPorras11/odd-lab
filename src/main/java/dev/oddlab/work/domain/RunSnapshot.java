package dev.oddlab.work.domain;

import java.util.List;
import java.util.Optional;

public record RunSnapshot(RunRecord run, List<RunEntry> entries, List<RunEvent> events) {
    public RunSnapshot {
        entries = List.copyOf(entries);
        events = List.copyOf(events);
    }

    public Optional<RunEntry> pendingDecision() {
        return entries.stream().filter(entry -> entry.kind() == RunEntry.Kind.DECISION && "PENDING".equals(entry.data().get("status")))
                .filter(pending -> entries.stream().noneMatch(entry -> entry.kind() == RunEntry.Kind.DECISION
                        && pending.id().toString().equals(entry.data().get("decisionId"))
                        && List.of("APPROVED", "DENIED").contains(entry.data().getOrDefault("status", ""))))
                .findFirst();
    }
}
