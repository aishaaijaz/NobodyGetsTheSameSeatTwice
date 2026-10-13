package tickets;

/** Value or error. Record equality lets the model test compare engine vs reference directly. */
public record Result<T>(T value, Err error) {
    public static <T> Result<T> ok(T value) {
        return new Result<>(value, null);
    }

    public static <T> Result<T> fail(Err error) {
        return new Result<>(null, error);
    }

    public boolean isOk() {
        return error == null;
    }
}
