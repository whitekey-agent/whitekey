package ai.whitekey.cli.client;

public class CliException extends RuntimeException {
    private final int statusCode;

    public CliException(String message) {
        this(message, -1);
    }

    public CliException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
