package dev.oddlab.workspace.domain;

public final class ProjectException extends RuntimeException {
    private final String code;
    private final int exitCode;

    public ProjectException(String code, String message, int exitCode) {
        super(message);
        this.code = code;
        this.exitCode = exitCode;
    }

    public String code() {
        return code;
    }

    public int exitCode() {
        return exitCode;
    }
}
