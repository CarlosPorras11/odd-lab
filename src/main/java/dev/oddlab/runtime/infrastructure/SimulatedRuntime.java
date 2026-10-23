package dev.oddlab.runtime.infrastructure;

import dev.oddlab.runtime.application.AgentRuntime;
import dev.oddlab.runtime.domain.SimulationScenario;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public final class SimulatedRuntime implements AgentRuntime {
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public Capabilities capabilities() {
        return new Capabilities("simulated", true, false, false, false);
    }

    @Override
    public Result execute(Assignment assignment) {
        var scenario = SimulationScenario.valueOf(assignment.runtimeOptions().getOrDefault("scenario", "SUCCESS"));
        if (scenario == SimulationScenario.PERMISSION) {
            return new Result(Status.PERMISSION_REQUESTED, null, "Solicitud sintética fuera del encargo", true, "PUBLISH");
        }
        if (scenario == SimulationScenario.FAILURE) {
            return new Result(Status.FAILED, null, "Fallo sintético", true, null);
        }
        if (scenario == SimulationScenario.UNVERIFIED) {
            return new Result(Status.SUCCEEDED, null, "Afirmación sin artefacto", true, null);
        }
        String hash = scenario == SimulationScenario.RETRY && assignment.ordinal() == 1
                ? "0".repeat(64) : assignment.requestSha256();
        String payload = json.writeValueAsString(Map.of("schemaVersion", 1, "kind", "SIMULATED_RECEIPT", "simulated", true,
                "requestSha256", hash, "revision", assignment.revision(), "attemptId", assignment.attemptId().toString()));
        return new Result(Status.SUCCEEDED, payload, "Recibo sintético; petición no implementada", true, null);
    }
}
