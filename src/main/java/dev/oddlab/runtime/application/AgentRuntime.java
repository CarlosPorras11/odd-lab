package dev.oddlab.runtime.application;

import dev.oddlab.policy.domain.RepositoryPolicy;
import java.util.Map;
import java.util.UUID;

public interface AgentRuntime {
    Capabilities capabilities();
    Result execute(Assignment assignment);

    record Capabilities(String id, boolean simulated, boolean externalProcesses, boolean tools, boolean network) {}
    record Assignment(UUID runId, UUID taskId, UUID attemptId, int ordinal, String request, String requestSha256,
                      String revision, RepositoryPolicy policy, int maxAttempts, Map<String, String> runtimeOptions) {
        public Assignment {
            runtimeOptions = Map.copyOf(runtimeOptions);
            if (maxAttempts < 1 || maxAttempts > 3 || ordinal < 1 || ordinal > maxAttempts) {
                throw new IllegalArgumentException("Presupuesto de encargo inválido");
            }
        }
    }
    enum Status { SUCCEEDED, FAILED, PERMISSION_REQUESTED }
    record Result(Status status, String payload, String message, boolean stopped, String requestedAction) {}
}
