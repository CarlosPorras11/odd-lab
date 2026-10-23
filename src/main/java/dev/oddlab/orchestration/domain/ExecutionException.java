package dev.oddlab.orchestration.domain;

public final class ExecutionException extends RuntimeException {
    private final String code;
    private final int exitCode;

    public ExecutionException(String code, String message, int exitCode) {
        super(message);
        this.code = code;
        this.exitCode = exitCode;
    }

    public String code() { return code; }
    public int exitCode() { return exitCode; }
}
