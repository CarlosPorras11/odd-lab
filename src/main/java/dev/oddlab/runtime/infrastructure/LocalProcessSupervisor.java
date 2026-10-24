package dev.oddlab.runtime.infrastructure;

import dev.oddlab.runtime.application.ProcessSupervisor;
import dev.oddlab.runtime.application.RuntimeControl.ProcessIdentity;
import java.time.Instant;
import java.util.*;

public final class LocalProcessSupervisor implements ProcessSupervisor {
    public static ProcessIdentity identity(ProcessHandle handle) {
        return new ProcessIdentity(handle.pid(), handle.info().startInstant().orElseThrow().toString());
    }
    private boolean matches(ProcessHandle handle, ProcessIdentity identity) {
        return handle.info().startInstant().map(value -> value.equals(Instant.parse(identity.startedAt()))).orElse(false);
    }
    @Override public boolean stopped(List<ProcessIdentity> identities) {
        if (identities.isEmpty()) return false;
        try {
            for (var identity : identities) {
                var handle = ProcessHandle.of(identity.pid());
                if (handle.isPresent() && (handle.get().isAlive() || !matches(handle.get(), identity))) return false;
            }
            return true;
        } catch (RuntimeException exception) { return false; }
    }
    @Override public boolean stopAndConfirm(List<ProcessIdentity> identities) {
        if (identities.isEmpty()) return false;
        var known = new LinkedHashMap<Long, ProcessIdentity>();
        identities.forEach(identity -> known.put(identity.pid(), identity));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        try {
            while (System.nanoTime() < deadline) {
                for (var identity : List.copyOf(known.values())) {
                    var handle = ProcessHandle.of(identity.pid());
                    if (handle.isEmpty()) continue;
                    if (!matches(handle.get(), identity)) return false; // Never signal a reused PID.
                    handle.get().descendants().forEach(child -> known.putIfAbsent(child.pid(), identity(child)));
                }
                var reverse = new ArrayList<>(known.values());
                Collections.reverse(reverse);
                for (var identity : reverse) {
                    var handle = ProcessHandle.of(identity.pid());
                    if (handle.isPresent()) {
                        if (!matches(handle.get(), identity)) return false;
                        if (deadline - System.nanoTime() < java.util.concurrent.TimeUnit.SECONDS.toNanos(2)) handle.get().destroyForcibly();
                        else handle.get().destroy();
                    }
                }
                if (stopped(new ArrayList<>(known.values()))) return true;
                Thread.sleep(40);
            }
            return stopped(new ArrayList<>(known.values()));
        } catch (Exception exception) { return false; }
    }
}
