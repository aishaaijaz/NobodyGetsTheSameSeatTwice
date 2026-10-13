package tickets;

/** FREE | HELD(user, expiresAt) | SOLD(user). Unused fields are null / 0 so equals() works. */
public record SeatStatus(Kind kind, String user, long expiresAt) {
    public enum Kind { FREE, HELD, SOLD }

    public static SeatStatus free() {
        return new SeatStatus(Kind.FREE, null, 0);
    }

    public static SeatStatus held(String user, long expiresAt) {
        return new SeatStatus(Kind.HELD, user, expiresAt);
    }

    public static SeatStatus sold(String user) {
        return new SeatStatus(Kind.SOLD, user, 0);
    }
}
