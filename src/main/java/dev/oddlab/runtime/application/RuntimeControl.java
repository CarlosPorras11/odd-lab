package dev.oddlab.runtime.application;

import java.util.List;
import java.util.Optional;

public interface RuntimeControl {
    Optional<String> requestedAction();
    void started(List<ProcessIdentity> processes);
    record ProcessIdentity(long pid, String startedAt) {}
}
