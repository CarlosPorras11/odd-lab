package dev.oddlab.runtime.infrastructure;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fixed synthetic worker: no request, repository path, arbitrary command or network is accepted. */
public final class ProcessFixture {
    private ProcessFixture() {}
    public static List<String> command(String mode, String seconds) {
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-cp"); args.add(System.getProperty("java.class.path"));
        boolean packaged = ProcessFixture.class.getClassLoader().getClass().getName().equals("org.springframework.boot.loader.launch.LaunchedClassLoader");
        args.add(packaged ? "org.springframework.boot.loader.launch.JarLauncher" : ProcessFixture.class.getName());
        if (packaged) args.add("--internal-process-fixture");
        args.add(mode); args.add(seconds);
        return args;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("parent", "child").contains(args[0])) throw new IllegalArgumentException();
        int seconds = Integer.parseInt(args[1]);
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException();
        if (args[0].equals("child")) { Thread.sleep(70_000); return; }
        Process child = new ProcessBuilder(command("child", args[1])).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            child.destroy();
            try { if (!child.waitFor(1, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(1, TimeUnit.SECONDS); } }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }));
        System.out.println(child.pid() + " " + child.info().startInstant().orElseThrow());
        System.out.flush();
        if (!"GO".equals(new BufferedReader(new InputStreamReader(System.in)).readLine())) return;
        Thread.sleep(seconds * 1000L);
        child.destroy();
        if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
    }
}
