package tickets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrency-safe seat hold engine. See DECISIONS.md for every rule interpretation.
 *
 * Locking: ONE lock guards all state. Every public op takes it, reads the clock once
 * inside it, does all checks + mutations, releases. One lock means no deadlock is
 * possible (hold_many of [1,2] vs [2,1] just queue up), and hold_many is atomic for free.
 *
 * Expiry is lazy: nothing runs on a timer. A hold is "live" iff expiresAt > now.
 * An expired hold stays in its seat's slot until someone else takes the seat; that is
 * what makes the R3 grace-confirm possible.
 */
public final class HoldEngine {
    public static final int MAX_LIVE_HOLDS = 4;

    private enum State { ACTIVE, CONFIRMED, RELEASED, SUPERSEDED }

    private static final class Hold {
        final long id;
        final String seat;
        final String user;
        final long expiresAt;
        State state = State.ACTIVE;

        Hold(long id, String seat, String user, long expiresAt) {
            this.id = id;
            this.seat = seat;
            this.user = user;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Seat {
        Hold current; // ACTIVE hold occupying the seat (may be expired = grace). null if none.
        Hold sold;    // the confirmed hold, if sold
    }

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Seat> seats = new HashMap<>();
    private final Map<Long, Hold> holds = new HashMap<>();
    private final Map<String, Set<Hold>> activeByUser = new HashMap<>(); // ACTIVE holds (live or grace)
    private long nextId = 1;
    private final List<Event> audit; // null when auditing is off

    public HoldEngine(Clock clock) {
        this(clock, false);
    }

    /** @param audit keep an event log (tests use it to check invariants; unbounded, so off by default) */
    public HoldEngine(Clock clock, boolean audit) {
        this.clock = Objects.requireNonNull(clock);
        this.audit = audit ? new ArrayList<>() : null;
    }

    // ---------------------------------------------------------------- public API

    public Result<Long> hold(String seatId, String userId, long ttlSeconds) {
        Objects.requireNonNull(seatId);
        Objects.requireNonNull(userId);
        lock.lock();
        try {
            long now = clock.nowSeconds();
            if (ttlSeconds <= 0) return Result.fail(Err.INVALID_TTL);

            Err blocked = blocker(seatId, userId, now);
            if (blocked != null) return Result.fail(blocked);

            Hold own = ownLiveHold(seatId, userId, now);
            if (own != null) return Result.ok(own.id); // R5: idempotent retry, NOT extended (R4)

            if (liveCount(userId, now) + 1 > MAX_LIVE_HOLDS) return Result.fail(Err.LIMIT_EXCEEDED);
            return Result.ok(create(seatId, userId, now, ttlSeconds).id);
        } finally {
            lock.unlock();
        }
    }

    /** All-or-nothing. Returns hold ids in (deduplicated) input order. */
    public Result<List<Long>> holdMany(String userId, List<String> seatIds, long ttlSeconds) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(seatIds);
        lock.lock();
        try {
            long now = clock.nowSeconds();
            if (ttlSeconds <= 0) return Result.fail(Err.INVALID_TTL);
            List<String> wanted = new ArrayList<>(new LinkedHashSet<>(seatIds));
            if (wanted.isEmpty()) return Result.fail(Err.EMPTY_REQUEST);

            // Phase 1: validate everything, mutate nothing.
            int newHolds = 0;
            for (String seatId : wanted) {
                Err blocked = blocker(seatId, userId, now);
                if (blocked != null) return Result.fail(blocked);
                if (ownLiveHold(seatId, userId, now) == null) newHolds++;
            }
            if (liveCount(userId, now) + newHolds > MAX_LIVE_HOLDS) return Result.fail(Err.LIMIT_EXCEEDED);

            // Phase 2: apply. Cannot fail now, and nobody can interleave (we hold the lock).
            List<Long> ids = new ArrayList<>();
            for (String seatId : wanted) {
                Hold own = ownLiveHold(seatId, userId, now);
                ids.add(own != null ? own.id : create(seatId, userId, now, ttlSeconds).id);
            }
            return Result.ok(ids);
        } finally {
            lock.unlock();
        }
    }

    public Result<Void> confirm(long holdId) {
        lock.lock();
        try {
            long now = clock.nowSeconds();
            Hold h = holds.get(holdId);
            if (h == null) return Result.fail(Err.UNKNOWN_HOLD);
            if (h.state == State.CONFIRMED) return Result.fail(Err.ALREADY_CONFIRMED);
            if (h.state != State.ACTIVE) return Result.fail(Err.HOLD_NOT_ACTIVE);

            // ACTIVE means h is still its seat's occupant: nobody took the seat since.
            // Live -> normal confirm. Expired -> R3 grace confirm. Either way expiresAt is untouched.
            Seat s = seats.get(h.seat);
            h.state = State.CONFIRMED;
            s.current = null;
            s.sold = h;
            activeByUser.get(h.user).remove(h);
            log(Event.Type.CONFIRMED, h, now);
            return Result.ok(null);
        } finally {
            lock.unlock();
        }
    }

    public Result<Void> release(long holdId) {
        lock.lock();
        try {
            long now = clock.nowSeconds();
            Hold h = holds.get(holdId);
            if (h == null) return Result.fail(Err.UNKNOWN_HOLD);
            if (h.state == State.CONFIRMED) return Result.fail(Err.ALREADY_CONFIRMED);
            if (h.state != State.ACTIVE) return Result.fail(Err.HOLD_NOT_ACTIVE);

            h.state = State.RELEASED;
            seats.get(h.seat).current = null;
            activeByUser.get(h.user).remove(h);
            log(Event.Type.RELEASED, h, now);
            return Result.ok(null);
        } finally {
            lock.unlock();
        }
    }

    public SeatStatus seatStatus(String seatId) {
        Objects.requireNonNull(seatId);
        lock.lock();
        try {
            long now = clock.nowSeconds();
            Seat s = seats.get(seatId);
            if (s == null) return SeatStatus.free();
            if (s.sold != null) return SeatStatus.sold(s.sold.user);
            if (s.current != null && s.current.expiresAt > now) {
                return SeatStatus.held(s.current.user, s.current.expiresAt);
            }
            return SeatStatus.free(); // expired holds show FREE (grace holder can still confirm)
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of the audit log (package-private: for tests). */
    List<Event> auditLog() {
        if (audit == null) throw new IllegalStateException("audit disabled");
        lock.lock();
        try {
            return new ArrayList<>(audit);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- helpers (lock must be held)

    /** Why `user` cannot take this seat right now, or null if they can. */
    private Err blocker(String seatId, String userId, long now) {
        Seat s = seats.get(seatId);
        if (s == null) return null;
        if (s.sold != null) return Err.SEAT_SOLD;
        Hold c = s.current;
        if (c != null && c.expiresAt > now && !c.user.equals(userId)) return Err.SEAT_HELD;
        return null;
    }

    /** The user's own LIVE hold on this seat, or null. */
    private Hold ownLiveHold(String seatId, String userId, long now) {
        Seat s = seats.get(seatId);
        if (s == null || s.current == null) return null;
        Hold c = s.current;
        return (c.user.equals(userId) && c.expiresAt > now) ? c : null;
    }

    private int liveCount(String userId, long now) {
        Set<Hold> mine = activeByUser.get(userId);
        if (mine == null) return 0;
        int n = 0;
        for (Hold h : mine) if (h.expiresAt > now) n++;
        return n;
    }

    /** Make a new hold, evicting whatever expired hold sat in the slot (it is dead forever). */
    private Hold create(String seatId, String userId, long now, long ttl) {
        Seat s = seats.computeIfAbsent(seatId, k -> new Seat());
        if (s.current != null) {
            s.current.state = State.SUPERSEDED;
            activeByUser.get(s.current.user).remove(s.current);
        }
        Hold h = new Hold(nextId++, seatId, userId, now + ttl);
        holds.put(h.id, h);
        s.current = h;
        activeByUser.computeIfAbsent(userId, k -> new HashSet<>()).add(h);
        log(Event.Type.HOLD_CREATED, h, now);
        return h;
    }

    private void log(Event.Type type, Hold h, long now) {
        if (audit != null) audit.add(new Event(type, h.id, h.seat, h.user, now, h.expiresAt));
    }
}
