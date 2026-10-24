package dev.oddlab.runtime.infrastructure;

import dev.oddlab.runtime.application.*;
import dev.oddlab.policy.domain.RepositoryPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalProcessRuntimeTest {
    private AgentRuntime.Assignment assignment() {
        return new AgentRuntime.Assignment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "literal", "a".repeat(64), "revision",
                RepositoryPolicy.initial(), 3, Map.of("processSeconds", "1"));
    }
    @Test void fixedWorkerWithRealDescendantStopsBeforeReturningReceipt() {
        var identities = new ArrayList<RuntimeControl.ProcessIdentity>();
        var result = new LocalProcessRuntime().execute(assignment(), new RuntimeControl() {
            public Optional<String> requestedAction() { return Optional.empty(); }
            public void started(List<ProcessIdentity> values) { identities.clear(); identities.addAll(values); }
        });
        assertThat(result.status()).isEqualTo(AgentRuntime.Status.SUCCEEDED);
        assertThat(result.stopped()).isTrue();
        assertThat(identities).hasSize(2);
        assertThat(new LocalProcessSupervisor().stopped(identities)).isTrue();
    }
    @Test void cancelStopsParentAndChildAndDoesNotReturnReceipt() {
        var identities = new ArrayList<RuntimeControl.ProcessIdentity>();
        var result = new LocalProcessRuntime().execute(assignment(), new RuntimeControl() {
            public Optional<String> requestedAction() { return Optional.of("CANCEL"); }
            public void started(List<ProcessIdentity> values) { identities.clear(); identities.addAll(values); }
        });
        assertThat(result.status()).isEqualTo(AgentRuntime.Status.STOPPED);
        assertThat(result.stopped()).isTrue();
        assertThat(result.payload()).isNull();
        assertThat(identities).hasSize(2);
        assertThat(new LocalProcessSupervisor().stopped(identities)).isTrue();
    }
    @Test void reusedIdentityIsNeverSignalledOrDeclaredStopped() {
        var fake = new RuntimeControl.ProcessIdentity(ProcessHandle.current().pid(), "2000-01-01T00:00:00Z");
        var supervisor = new LocalProcessSupervisor();
        assertThat(supervisor.stopAndConfirm(List.of(fake))).isFalse();
        assertThat(supervisor.stopped(List.of(fake))).isFalse();
        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }
}
