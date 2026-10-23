package dev.oddlab.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

public final class CommandOutput {
    private final JsonMapper json = JsonMapper.builder().build();

    public int write(CommandLine command, boolean structured, Object result, String humanMessage,
                     String errorCode, int exitCode) {
        if (structured) {
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("schemaVersion", 1);
            envelope.put("command", command.getCommandName());
            if (result instanceof Map<?, ?> map) {
                if (map.containsKey("runId")) envelope.put("runId", map.get("runId"));
                if (map.containsKey("state")) envelope.put("state", map.get("state"));
            }
            envelope.put("result", result);
            envelope.put("pendingDecision", result instanceof Map<?, ?> map ? map.get("pendingDecision") : null);
            envelope.put("error", errorCode == null ? null : Map.of("code", errorCode, "message", humanMessage));
            command.getOut().println(json.writeValueAsString(envelope));
        } else if (exitCode == 0) {
            command.getOut().println(humanMessage);
        } else {
            command.getErr().println(errorCode + ": " + humanMessage);
        }
        return exitCode;
    }
}
