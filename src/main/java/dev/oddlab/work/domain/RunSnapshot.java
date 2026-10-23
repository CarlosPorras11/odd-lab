package dev.oddlab.work.domain;

import java.util.List;

public record RunSnapshot(RunRecord run, List<RunEntry> entries, List<RunEvent> events) {
    public RunSnapshot {
        entries = List.copyOf(entries);
        events = List.copyOf(events);
    }
}
