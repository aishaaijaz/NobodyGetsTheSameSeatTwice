package tickets;

import java.util.List;
import java.util.Objects;

/** One test per rule / per rule-conflict decision in DECISIONS.md. */
final class RuleTests {
    static void check(boolean c, String m) {
        if (!c) throw new AssertionError(m);
    }

    static void eq(Object actual, Object expected, String m) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(m + ": expected " + expected + " but got " + actual);
        }
    }

    static long ok(Result<Long> r) {
        check(r.isOk(), "expected ok but got " + r.error());
        return r.value();
    }

    static void err(Result<?> r, Err expected) {
        eq(r.error(), expected, "error code");
    }

    private static HoldEngine engine(FakeClock c) {
        return new HoldEngine(c, true);
    }

    /** R1 */
    static void r1_oneHolderAndOneSale() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        long a = ok(e.hold("S1", "alice", 10));
        err(e.hold("S1", "bob", 10), Err.SEAT_HELD);
        check(e.confirm(a).isOk(), "confirm should work");
        err(e.hold("S1", "bob", 10), Err.SEAT_SOLD);
        err(e.confirm(a), Err.ALREADY_CONFIRMED);
        eq(e.seatStatus("S1"), SeatStatus.sold("alice"), "status");
    }

    /** R2 */
    static void r2_maxFourLiveHolds() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        long first = ok(e.hold("S1", "alice", 10));
        for (int i = 2; i <= 4; i++) ok(e.hold("S" + i, "alice", 10));
        err(e.hold("S5", "alice", 10), Err.LIMIT_EXCEEDED);
        ok(e.hold("S6", "bob", 10)); // other users unaffected

        check(e.confirm(first).isOk(), "confirm");        // a SOLD seat is not a hold anymore
        ok(e.hold("S5", "alice", 10));                     // 3 live + 1 = 4
        err(e.hold("S7", "alice", 10), Err.LIMIT_EXCEEDED);

        c.advance(10);                                     // all of alice's holds expire (now == expiresAt)
        for (int i = 7; i <= 10; i++) ok(e.hold("S" + i, "alice", 10)); // expired ones do not count
    }

    /** R3, happy side: expired but untouched seat can still be confirmed. */
    static void r3_graceConfirmWhenUntouched() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        long a = ok(e.hold("S1", "alice", 10)); // expires at 110
        c.advance(9);
        eq(e.seatStatus("S1"), SeatStatus.held("alice", 110), "live at 109");
        c.advance(1);
        eq(e.seatStatus("S1"), SeatStatus.free(), "expired exactly at 110 -> FREE");
        check(e.confirm(a).isOk(), "grace confirm should work, nobody took the seat");
        eq(e.seatStatus("S1"), SeatStatus.sold("alice"), "now sold");
    }

    /** R3, sad side: once someone else takes the seat, the old hold is dead forever. */
    static void r3_noConfirmOnceRetaken() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        long a = ok(e.hold("S1", "alice", 10));
        c.advance(20);
        long b = ok(e.hold("S1", "bob", 10));      // bob takes the expired seat
        err(e.confirm(a), Err.HOLD_NOT_ACTIVE);
        check(e.release(b).isOk(), "bob releases");
        err(e.confirm(a), Err.HOLD_NOT_ACTIVE);    // still dead: it was taken once
        eq(e.seatStatus("S1"), SeatStatus.free(), "free");
    }

    /** R4: nothing ever extends a hold (retry, late confirm attempts, etc.). */
    static void r4_neverExtended() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        long a = ok(e.hold("S1", "alice", 10)); // expires 110
        c.advance(5);
        eq(ok(e.hold("S1", "alice", 1000)), a, "retry returns same hold");
        eq(e.seatStatus("S1"), SeatStatus.held("alice", 110), "retry with longer ttl must NOT extend");
        c.advance(5);
        eq(e.seatStatus("S1"), SeatStatus.free(), "expired at original time");
    }

    /** R5 + R3/R4: retry on live = same hold. Retry after expiry = NEW hold, old one is dead. */
    static void r5_retryIdempotentWhileLive_newHoldAfterExpiry() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        long id1 = ok(e.hold("S1", "alice", 10));
        for (int i = 2; i <= 4; i++) ok(e.hold("S" + i, "alice", 10));
        eq(ok(e.hold("S1", "alice", 10)), id1, "retry at the 4-seat limit is still fine");
        err(e.hold("S5", "alice", 10), Err.LIMIT_EXCEEDED); // retry did not use up a slot

        c.advance(10);
        long id2 = ok(e.hold("S1", "alice", 10));
        check(id2 != id1, "after expiry a retry is a brand new hold");
        err(e.confirm(id1), Err.HOLD_NOT_ACTIVE);
        eq(e.seatStatus("S1"), SeatStatus.held("alice", 120), "fresh ttl on the new hold");
        check(e.confirm(id2).isOk(), "new hold confirms");
    }

    /** R6 + R2: all-or-nothing, dedupe, idempotent seats cost nothing. */
    static void r6_holdManyIsAllOrNothing() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        ok(e.hold("S1", "alice", 10));
        ok(e.hold("S2", "alice", 10));
        long id3 = ok(e.hold("S3", "alice", 10));

        err(e.holdMany("alice", List.of("S4", "S5"), 10), Err.LIMIT_EXCEEDED); // 3 + 2 > 4
        eq(e.seatStatus("S4"), SeatStatus.free(), "S4 untouched after failure");
        eq(e.seatStatus("S5"), SeatStatus.free(), "S5 untouched after failure");

        Result<List<Long>> r = e.holdMany("alice", List.of("S3", "S4"), 10);  // S3 already mine -> 3 + 1
        check(r.isOk(), "should fit: " + r.error());
        eq(r.value().get(0), id3, "already-held seat returns its existing hold");
        eq(r.value().size(), 2, "two ids");

        // one blocked seat poisons the whole request
        err(e.holdMany("bob", List.of("S9", "S1"), 10), Err.SEAT_HELD);
        eq(e.seatStatus("S9"), SeatStatus.free(), "S9 untouched because S1 was blocked");

        // duplicates collapse
        Result<List<Long>> d = e.holdMany("carol", List.of("S20", "S20", "S21"), 10);
        check(d.isOk() && d.value().size() == 2, "dedupe");

        err(e.holdMany("carol", List.of(), 10), Err.EMPTY_REQUEST);
        err(e.holdMany("carol", List.of("S30"), 0), Err.INVALID_TTL);
    }

    /** R6 + R2: expired holds do not count toward the limit in hold_many either. */
    static void r6_expiredHoldsDontCount() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        e.holdMany("alice", List.of("S1", "S2", "S3", "S4"), 5);
        err(e.holdMany("alice", List.of("S5"), 5), Err.LIMIT_EXCEEDED);
        c.advance(5);
        check(e.holdMany("alice", List.of("S5", "S6", "S7", "S8"), 5).isOk(), "old holds expired");
    }

    /** Unknown ids / bad input. */
    static void misc_errors() {
        FakeClock c = new FakeClock(100);
        HoldEngine e = engine(c);
        err(e.confirm(999), Err.UNKNOWN_HOLD);
        err(e.release(999), Err.UNKNOWN_HOLD);
        err(e.hold("S1", "alice", 0), Err.INVALID_TTL);
        err(e.hold("S1", "alice", -5), Err.INVALID_TTL);
        long a = ok(e.hold("S1", "alice", 10));
        check(e.release(a).isOk(), "release");
        err(e.release(a), Err.HOLD_NOT_ACTIVE);
        err(e.confirm(a), Err.HOLD_NOT_ACTIVE);
        long b = ok(e.hold("S1", "bob", 10)); // released seat is takeable immediately
        check(e.confirm(b).isOk(), "bob buys");
        err(e.release(b), Err.ALREADY_CONFIRMED);
    }

    /** The checker itself must be able to FAIL, otherwise the stress test proves nothing. */
    static void checker_catchesBrokenLogs() {
        Event h1 = new Event(Event.Type.HOLD_CREATED, 1, "S", "a", 0, 10);
        Event c1 = new Event(Event.Type.CONFIRMED, 1, "S", "a", 1, 10);

        InvariantChecker.check(List.of(h1, c1)); // sane log passes

        mustFail("double sale", List.of(h1, c1, new Event(Event.Type.CONFIRMED, 1, "S", "a", 2, 10)));
        mustFail("hold on sold seat", List.of(h1, c1, new Event(Event.Type.HOLD_CREATED, 2, "S", "b", 20, 30)));
        mustFail("two live holds", List.of(h1, new Event(Event.Type.HOLD_CREATED, 2, "S", "b", 5, 15)));
        mustFail("confirm after retaken", List.of(
                new Event(Event.Type.HOLD_CREATED, 1, "S", "a", 0, 5),
                new Event(Event.Type.HOLD_CREATED, 2, "S", "b", 10, 20),
                new Event(Event.Type.CONFIRMED, 1, "S", "a", 11, 5)));
        mustFail("5 live holds", List.of(
                new Event(Event.Type.HOLD_CREATED, 1, "S1", "a", 0, 10),
                new Event(Event.Type.HOLD_CREATED, 2, "S2", "a", 0, 10),
                new Event(Event.Type.HOLD_CREATED, 3, "S3", "a", 0, 10),
                new Event(Event.Type.HOLD_CREATED, 4, "S4", "a", 0, 10),
                new Event(Event.Type.HOLD_CREATED, 5, "S5", "a", 0, 10)));
    }

    private static void mustFail(String what, List<Event> log) {
        try {
            InvariantChecker.check(log);
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("checker failed to detect: " + what);
    }
}
