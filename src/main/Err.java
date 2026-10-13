package tickets;

/** Every way an operation can fail. See DECISIONS.md for check order. */
public enum Err {
    INVALID_TTL,       // ttl <= 0
    EMPTY_REQUEST,     // hold_many with no seats
    SEAT_SOLD,         // seat already sold
    SEAT_HELD,         // seat held (live) by another user
    LIMIT_EXCEEDED,    // user would exceed 4 live holds
    UNKNOWN_HOLD,      // no such hold id
    HOLD_NOT_ACTIVE,   // hold was released, or lost its seat to someone else
    ALREADY_CONFIRMED  // hold already turned into a sale
}
