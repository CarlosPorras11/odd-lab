package dev.oddlab.policy.domain;

import java.util.List;

public record RepositoryPolicy(int schemaVersion, List<String> allowedProviders, boolean networkAllowed,
                               int maxActiveSubagents, int maxCorrectionsPerTask, List<String> reservedActions) {
    public static final List<String> RESERVED_ACTIONS = List.of("PUBLISH", "MODIFY_EXTERNAL_SYSTEMS");

    public RepositoryPolicy {
        allowedProviders = List.copyOf(allowedProviders);
        reservedActions = List.copyOf(reservedActions);
        if (schemaVersion != 1 || maxActiveSubagents < 0 || maxActiveSubagents > 2
                || maxCorrectionsPerTask < 0 || maxCorrectionsPerTask > 2
                || allowedProviders.stream().anyMatch(value -> !value.matches("[a-z][a-z0-9-]{0,63}"))
                || allowedProviders.stream().distinct().count() != allowedProviders.size()
                || !reservedActions.containsAll(RESERVED_ACTIONS)
                || reservedActions.size() != RESERVED_ACTIONS.size()) {
            throw new IllegalArgumentException("Política inválida");
        }
    }

    public static RepositoryPolicy initial() {
        return new RepositoryPolicy(1, List.of(), false, 2, 2, RESERVED_ACTIONS);
    }
}
