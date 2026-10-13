package tickets;

/**
 * Audit-log entry. Appended while holding the engine lock, so list order IS the order
 * operations took effect. The invariant checker replays it independently of engine code.
 */
record Event(Type type, long holdId, String seat, String user, long time, long expiresAt) {
    enum Type { HOLD_CREATED, CONFIRMED, RELEASED }
}
