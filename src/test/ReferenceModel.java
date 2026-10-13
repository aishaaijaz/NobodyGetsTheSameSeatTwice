package tickets;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately dumb single-threaded reference. No slots, no per-user index, no state machine
 * beyond a status string: it keeps every hold ever made in a list and DERIVES everything by
 * linear scan ("a hold is current iff it is the newest hold on its seat and still ACTIVE").
 * Different formulation from HoldEngine on purpose, so a shared misunderstanding is less likely.
 */
final class ReferenceModel {
    private static final class H {
        long id;
        String seat, user;
        long expires;
        String status = "ACTIVE"; // ACTIVE | CONFIRMED | RELEASED
    }

    private final Clock clock;
    private final List<H> all = new ArrayList<>();
    private long nextId = 1;

    ReferenceModel(Clock clock) {
        this.clock = clock;
    }

    long lastId() {
        return nextId - 1;
    }

    private H newest(String seat) {
        H r = null;
        for (H h : all) if (h.seat.equals(seat)) r = h;
        return r;
    }

    private H soldHold(String seat) {
        for (H h : all) if (h.seat.equals(seat) && h.status.equals("CONFIRMED")) return h;
        return null;
    }

    private boolean isCurrent(H h) {
        return h.status.equals("ACTIVE") && newest(h.seat) == h;
    }

    private Err blocker(String seat, String user, long now) {
        if (soldHold(seat) != null) return Err.SEAT_SOLD;
        H n = newest(seat);
        if (n != null && n.status.equals("ACTIVE") && n.expires > now && !n.user.equals(user)) return Err.SEAT_HELD;
        return null;
    }

    private H ownLive(String seat, String user, long now) {
        H n = newest(seat);
        return (n != null && n.status.equals("ACTIVE") && n.user.equals(user) && n.expires > now) ? n : null;
    }

    private int liveCount(String user, long now) {
        int n = 0;
        for (H h : all) if (h.user.equals(user) && isCurrent(h) && h.expires > now) n++;
        return n;
    }

    private H make(String seat, String user, long now, long ttl) {
        H h = new H();
        h.id = nextId++;
        h.seat = seat;
        h.user = user;
        h.expires = now + ttl;
        all.add(h);
        return h;
    }

    Result<Long> hold(String seat, String user, long ttl) {
        long now = clock.nowSeconds();
        if (ttl <= 0) return Result.fail(Err.INVALID_TTL);
        Err b = blocker(seat, user, now);
        if (b != null) return Result.fail(b);
        H own = ownLive(seat, user, now);
        if (own != null) return Result.ok(own.id);
        if (liveCount(user, now) >= HoldEngine.MAX_LIVE_HOLDS) return Result.fail(Err.LIMIT_EXCEEDED);
        return Result.ok(make(seat, user, now, ttl).id);
    }

    Result<List<Long>> holdMany(String user, List<String> seats, long ttl) {
        long now = clock.nowSeconds();
        if (ttl <= 0) return Result.fail(Err.INVALID_TTL);
        List<String> want = new ArrayList<>();
        for (String s : seats) if (!want.contains(s)) want.add(s);
        if (want.isEmpty()) return Result.fail(Err.EMPTY_REQUEST);
        int fresh = 0;
        for (String s : want) {
            Err b = blocker(s, user, now);
            if (b != null) return Result.fail(b);
            if (ownLive(s, user, now) == null) fresh++;
        }
        if (liveCount(user, now) + fresh > HoldEngine.MAX_LIVE_HOLDS) return Result.fail(Err.LIMIT_EXCEEDED);
        List<Long> ids = new ArrayList<>();
        for (String s : want) {
            H own = ownLive(s, user, now);
            ids.add(own != null ? own.id : make(s, user, now, ttl).id);
        }
        return Result.ok(ids);
    }

    private H find(long id) {
        for (H h : all) if (h.id == id) return h;
        return null;
    }

    Result<Void> confirm(long id) {
        H h = find(id);
        if (h == null) return Result.fail(Err.UNKNOWN_HOLD);
        if (h.status.equals("CONFIRMED")) return Result.fail(Err.ALREADY_CONFIRMED);
        if (!isCurrent(h)) return Result.fail(Err.HOLD_NOT_ACTIVE);
        h.status = "CONFIRMED";
        return Result.ok(null);
    }

    Result<Void> release(long id) {
        H h = find(id);
        if (h == null) return Result.fail(Err.UNKNOWN_HOLD);
        if (h.status.equals("CONFIRMED")) return Result.fail(Err.ALREADY_CONFIRMED);
        if (!isCurrent(h)) return Result.fail(Err.HOLD_NOT_ACTIVE);
        h.status = "RELEASED";
        return Result.ok(null);
    }

    SeatStatus seatStatus(String seat) {
        long now = clock.nowSeconds();
        H sold = soldHold(seat);
        if (sold != null) return SeatStatus.sold(sold.user);
        H n = newest(seat);
        if (n != null && n.status.equals("ACTIVE") && n.expires > now) return SeatStatus.held(n.user, n.expires);
        return SeatStatus.free();
    }
}
