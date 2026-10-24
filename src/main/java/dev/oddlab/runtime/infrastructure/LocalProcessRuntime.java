package dev.oddlab.runtime.infrastructure;

import dev.oddlab.runtime.application.*;
import dev.oddlab.runtime.application.RuntimeControl.ProcessIdentity;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Process lifecycle fixture, not a provider or an arbitrary shell executor. */
public final class LocalProcessRuntime implements AgentRuntime {
    private final LocalProcessSupervisor supervisor = new LocalProcessSupervisor();
    @Override public Capabilities capabilities() { return new Capabilities("process", true, true, false, false); }
    @Override public Result execute(Assignment assignment) { throw new IllegalStateException("Se requiere control de procesos"); }
    @Override public Result execute(Assignment assignment, RuntimeControl control) {
        Process process = null;
        var identities = new CopyOnWriteArrayList<ProcessIdentity>();
        Thread hook = null;
        try {
            int seconds = Integer.parseInt(assignment.runtimeOptions().getOrDefault("processSeconds", "15"));
            if (seconds < 1 || seconds > 60) throw new IllegalArgumentException();
            process = new ProcessBuilder(ProcessFixture.command("parent", Integer.toString(seconds)))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            identities.add(LocalProcessSupervisor.identity(process.toHandle()));
            var held = process;
            hook = new Thread(() -> supervisor.stopAndConfirm(List.copyOf(identities)));
            Runtime.getRuntime().addShutdownHook(hook);
            // Persist the root before waiting for its descendant handshake. Missing handshake stays unknown on recovery.
            control.started(List.copyOf(identities));
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            var handshake = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try { handshake.complete(reader.readLine()); }
                catch (Exception exception) { handshake.completeExceptionally(exception); }
            });
            line = handshake.get(10, TimeUnit.SECONDS);
            String[] fields = Objects.requireNonNull(line).split(" ");
            if (fields.length != 2) throw new IOException("Handshake inválido");
            var child = new ProcessIdentity(Long.parseLong(fields[0]), fields[1]);
            if (held.toHandle().descendants().noneMatch(handle -> handle.pid() == child.pid()
                    && LocalProcessSupervisor.identity(handle).equals(child))) throw new IOException("Descendiente no comprobado");
            identities.add(child);
            control.started(List.copyOf(identities));
            process.getOutputStream().write("GO\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            long deadline = System.nanoTime() + Duration.ofSeconds(seconds + 5).toNanos();
            while (process.isAlive() && System.nanoTime() < deadline) {
                var action = control.requestedAction();
                if (action.isPresent()) {
                    boolean stopped = supervisor.stopAndConfirm(identities);
                    process.waitFor(1, TimeUnit.SECONDS);
                    return new Result(Status.STOPPED, null, "Control sintético solicitado", stopped, action.get());
                }
                process.waitFor(50, TimeUnit.MILLISECONDS);
            }
            process.waitFor(1, TimeUnit.SECONDS);
            if (!supervisor.stopped(identities)) return new Result(Status.FAILED, null, "Cierre no confirmado", false, null);
            if (process.exitValue() != 0) return new Result(Status.FAILED, null, "Proceso sintético fallido", true, null);
            return new SimulatedRuntime().execute(assignment);
        } catch (Exception exception) {
            boolean stopped = !identities.isEmpty() && supervisor.stopAndConfirm(identities);
            return new Result(Status.FAILED, null, "Fallo del proceso sintético", stopped, null);
        } finally {
            if (process != null && process.isAlive()) supervisor.stopAndConfirm(identities);
            if (hook != null) try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
        }
    }
}
