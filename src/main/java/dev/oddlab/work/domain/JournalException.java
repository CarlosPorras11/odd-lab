package dev.oddlab.work.domain;

public final class JournalException extends RuntimeException {
    private final String code;
    private final int exitCode;

    public JournalException(String code, String message, int exitCode) {
        super(message);
        this.code = code;
        this.exitCode = exitCode;
    }

    public String code() { return code; }
    public int exitCode() { return exitCode; }
}
