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

    static java.util.List<Map<String, Object>> controls(dev.oddlab.control.application.RunControls controls, java.nio.file.Path repository, RunSnapshot snapshot) {
        return controls.requests(repository, snapshot.run().repositoryId(), snapshot.run().runId()).stream().map(request -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", request.id().toString()); result.put("action", request.action()); result.put("createdAt", request.createdAt());
            var ack = snapshot.entries().stream().filter(entry -> "CONTROL_ACK".equals(entry.data().get("recordType"))
                    && request.id().toString().equals(entry.data().get("requestId"))).findFirst();
            long ackVersion = ack.map(entry -> snapshot.events().stream().filter(event -> entry.id().toString().equals(event.detail()))
                    .mapToLong(event -> event.version()).findFirst().orElse(Long.MAX_VALUE)).orElse(Long.MAX_VALUE);
            boolean confirmed = ack.isPresent() && snapshot.events().stream().anyMatch(event -> event.version() > ackVersion
                    && event.type().equals("STATE_CHANGED") && event.state().name().equals("CANCEL".equals(ack.get().data().get("resolution")) ? "CANCELLED" : "PAUSED"));
            result.put("status", confirmed ? "CONFIRMED" : ack.isPresent() ? "PROCESSED" : "PENDING");
            result.put("resolution", ack.map(entry -> entry.data().get("resolution")).orElse(null));
            return (Map<String, Object>) result;
        }).toList();
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
                + (!outcome.viewStatus().equals("UNAVAILABLE") ? "" : System.lineSeparator() + "Vista Markdown no disponible; registro conservado.");
    }
}
