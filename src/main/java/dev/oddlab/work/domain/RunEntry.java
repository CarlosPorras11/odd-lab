package dev.oddlab.work.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RunEntry(UUID id, Kind kind, int schemaVersion, String title, Map<String, String> data) {
    public enum Kind { REQUIREMENT, TASK, DECISION, EVIDENCE }

    public RunEntry {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        data = Map.copyOf(data);
        if (schemaVersion != 1 || title == null || title.isBlank()) throw new IllegalArgumentException("Entrada inválida");
        if (kind == Kind.EVIDENCE && (!data.containsKey("location") || data.get("location").isBlank()
                || !data.containsKey("revision") || data.get("revision").isBlank()
                || !data.containsKey("sha256") || !data.get("sha256").matches("[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("Evidencia sin procedencia verificable");
        }
    }
}
