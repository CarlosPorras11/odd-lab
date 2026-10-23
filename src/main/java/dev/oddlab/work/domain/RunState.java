package dev.oddlab.work.domain;

import java.util.Set;

public enum RunState {
    CREATED, EXPLORING, READY, RUNNING, VERIFYING, COMPLETED, WAITING_INPUT,
    PAUSING, PAUSED, CANCELLING, CANCELLED, RECONCILIATION_REQUIRED, FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    public void requireTransitionTo(RunState target) {
        Set<RunState> available = switch (this) {
            case CREATED -> Set.of(EXPLORING, CANCELLING, FAILED, RECONCILIATION_REQUIRED);
            case EXPLORING -> Set.of(READY, VERIFYING, WAITING_INPUT, PAUSING, CANCELLING, FAILED, RECONCILIATION_REQUIRED);
            case READY -> Set.of(RUNNING, WAITING_INPUT, PAUSING, CANCELLING, FAILED, RECONCILIATION_REQUIRED);
            case RUNNING -> Set.of(VERIFYING, WAITING_INPUT, PAUSING, CANCELLING, FAILED, RECONCILIATION_REQUIRED);
            case VERIFYING -> Set.of(RUNNING, WAITING_INPUT, PAUSING, CANCELLING, FAILED, RECONCILIATION_REQUIRED);
            case WAITING_INPUT, PAUSED -> Set.of(EXPLORING, READY, CANCELLING, FAILED, RECONCILIATION_REQUIRED);
            case PAUSING, CANCELLING -> Set.of(RECONCILIATION_REQUIRED, FAILED);
            case RECONCILIATION_REQUIRED -> Set.of(EXPLORING, READY, FAILED);
            case COMPLETED, CANCELLED, FAILED -> Set.of();
        };
        // Generic transitions cannot attest acceptance or process detachment; completion has a dedicated verified operation.
        if (target == COMPLETED || target == PAUSED || target == CANCELLED) {
            throw new JournalException("STATE_PROOF_REQUIRED", "Este estado requiere un caso de uso con verificación o detención confirmada.", 2);
        }
        if (!available.contains(target)) {
            throw new JournalException("INVALID_STATE_TRANSITION", "Transición de estado no permitida.", 2);
        }
    }
}
