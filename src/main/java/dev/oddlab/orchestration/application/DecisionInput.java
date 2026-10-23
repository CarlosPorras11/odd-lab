package dev.oddlab.orchestration.application;

import dev.oddlab.work.domain.RunEntry;
import java.util.Optional;

public interface DecisionInput {
    boolean interactive();
    Optional<Boolean> ask(RunEntry decision);
}
