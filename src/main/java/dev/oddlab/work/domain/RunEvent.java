package dev.oddlab.work.domain;

import java.time.Instant;
import java.util.UUID;

public record RunEvent(UUID eventId, long version, String type, RunState previousState, RunState state,
                       String actor, String detail, Instant occurredAt) {
}
