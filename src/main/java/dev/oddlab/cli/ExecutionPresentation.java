package dev.oddlab.cli;

import dev.oddlab.orchestration.application.SimulationCoordinator;
import dev.oddlab.work.domain.RunSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

final class ExecutionPresentation {
    private ExecutionPresentation() {}

    static Map<String, Object> pending(RunSnapshot snapshot) {
        var entry = snapshot.pendingDecision().orElse(null);
        if (entry == null) return null;
        var result = new LinkedHashMap<String, Object>();
        result.put("id", entry.id().toString()); result.put("question", entry.title());
        result.put("scope", entry.data().get("scope")); result.put("revision", entry.data().get("revision"));
        result.put("expectedVersion", entry.data().get("expectedVersion"));
        return result;
    }

    static Map<String, Object> result(SimulationCoordinator.Outcome outcome) {
        var result = new LinkedHashMap<String, Object>(StatusCommand.summary(outcome.snapshot().run()));
        result.put("simulated", true);
        result.put("verificationStatus", outcome.verificationStatus());
        result.put("viewStatus", outcome.viewStatus());
        result.put("pendingDecision", pending(outcome.snapshot()));
        result.put("message", outcome.message());
        return result;
    }

    static String human(SimulationCoordinator.Outcome outcome) {
        return outcome.snapshot().run().runId() + " " + outcome.snapshot().run().state() + System.lineSeparator()
                + outcome.message() + System.lineSeparator() + "Verificación: " + outcome.verificationStatus()
                + (outcome.viewStatus().equals("CURRENT") ? "" : System.lineSeparator() + "Vista Markdown no disponible; registro conservado.");
    }
}
