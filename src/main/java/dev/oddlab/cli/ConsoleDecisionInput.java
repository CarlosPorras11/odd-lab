package dev.oddlab.cli;

import dev.oddlab.orchestration.application.DecisionInput;
import dev.oddlab.work.domain.RunEntry;
import java.util.Locale;
import java.util.Optional;

public final class ConsoleDecisionInput implements DecisionInput {
    @Override
    public boolean interactive() { return System.console() != null; }

    @Override
    public Optional<Boolean> ask(RunEntry decision) {
        var console = System.console();
        if (console == null) return Optional.empty();
        String answer = console.readLine("%s [s/n]: ", decision.title());
        if (answer == null) return Optional.empty();
        return switch (answer.strip().toLowerCase(Locale.ROOT)) {
            case "s", "si", "sí" -> Optional.of(true);
            case "n", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }
}
