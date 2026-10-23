package dev.oddlab.workspace.domain;

import dev.oddlab.policy.domain.RepositoryPolicy;
import java.util.Objects;
import java.util.UUID;

public record ProjectConfiguration(int schemaVersion, UUID repositoryId, String name, RepositoryPolicy policy) {
    public ProjectConfiguration {
        Objects.requireNonNull(repositoryId);
        Objects.requireNonNull(name);
        Objects.requireNonNull(policy);
        if (schemaVersion != 1 || name.isBlank() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Identidad de proyecto inválida");
        }
    }
}
