package dev.oddlab.runtime.application;

import java.util.List;

public interface ProcessSupervisor {
    /** False includes inaccessible/reused identities or unknown descendants. */
    boolean stopAndConfirm(List<RuntimeControl.ProcessIdentity> processes);
    boolean stopped(List<RuntimeControl.ProcessIdentity> processes);
}
