package dev.oddlab.work.domain;

import dev.oddlab.policy.domain.RepositoryPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record RunRecord(UUID runId, UUID repositoryId, String actor, String request, String baseRevision,
                        RepositoryPolicy policy, RunState state, long version, Instant createdAt, Instant updatedAt) {
    public RunRecord {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(repositoryId);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(state);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (actor == null || actor.isBlank() || request == null || request.isBlank()
                || baseRevision == null || baseRevision.isBlank() || version < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Registro de ejecución inválido");
        }
    }

    public String requestSha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
