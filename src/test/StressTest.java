package tickets;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Many threads hammer a few seats with random hold / holdMany / confirm / release while a fake
 * clock keeps moving. Afterwards the audit log (which is in lock order) is replayed by
 * InvariantChecker. NOTE: the seed fixes each thread's random op stream, but thread
 * interleaving is up to the OS, so a failing run is not bit-for-bit repeatable. That is
 * inherent to real concurrency tests (see BUGS_I_FEAR.md).
 */
final class StressTest {
    /**
     * Sold seats stay sold forever, so one long run goes quiet after the first few hundred ops.
     * Instead: many short rounds, each on a fresh engine, so we keep hitting the busy phase.
     */
    static void run(long seed, int rounds, int threads, int opsPerThread) throws Exception {
        long holds = 0, confirms = 0, grace = 0, releases = 0;
        for (int round = 0; round < rounds; round++) {
            InvariantChecker.Stats st;
            try {
                st = oneRound(seed + round, threads, opsPerThread);
            } catch (AssertionError e) {
                throw new AssertionError("round " + round + " (round seed " + (seed + round) + "): " + e.getMessage(), e);
            }
            holds += st.holds;
            confirms += st.confirms;
            grace += st.graceConfirms;
            releases += st.releases;
        }
        RuleTests.check(holds > rounds * 10L, "too few holds overall: " + holds);
        RuleTests.check(confirms > 0, "no confirms happened");
        RuleTests.check(grace > 0, "no grace confirms happened");
        System.out.println("    rounds=" + rounds + " holds=" + holds + " confirms=" + confirms
                + " (grace=" + grace + ") releases=" + releases);
    }

    private static InvariantChecker.Stats oneRound(long seed, int threads, int opsPerThread) throws Exception {
        String[] seats = {"S0", "S1", "S2", "S3", "S4", "S5"};
        String[] users = {"u0", "u1", "u2", "u3", "u4"};
        FakeClock clock = new FakeClock(1000);
        HoldEngine eng = new HoldEngine(clock, true);
        AtomicLong maxId = new AtomicLong(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                Random r = new Random(seed * 1_000_003L + tid);
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        int k = r.nextInt(100);
                        if (k < 30) {
                            Result<Long> res = eng.hold(seats[r.nextInt(seats.length)], users[r.nextInt(users.length)],
                                    1 + r.nextInt(20));
                            if (res.isOk()) maxId.accumulateAndGet(res.value(), Math::max);
                        } else if (k < 45) {
                            List<String> req = new ArrayList<>();
                            int n = 1 + r.nextInt(3);
                            for (int j = 0; j < n; j++) req.add(seats[r.nextInt(seats.length)]); // random order, maybe dups
                            Result<List<Long>> res = eng.holdMany(users[r.nextInt(users.length)], req, 1 + r.nextInt(20));
                            if (res.isOk()) for (long id : res.value()) maxId.accumulateAndGet(id, Math::max);
                        } else if (k < 58) {
                            long m = maxId.get();
                            if (m > 0) eng.confirm(m - r.nextInt((int) Math.min(m, 12)));
                        } else if (k < 76) {
                            long m = maxId.get();
                            if (m > 0) eng.release(m - r.nextInt((int) Math.min(m, 12)));
                        } else {
                            clock.advance(1 + r.nextInt(3));
                        }
                    }
                } catch (Throwable x) {
                    failure.compareAndSet(null, x);
                }
            }, "stress-" + t);
            th.setDaemon(true);
            ts.add(th);
        }
        for (Thread th : ts) th.start();
        for (Thread th : ts) {
            th.join(60_000);
            if (th.isAlive()) throw new AssertionError("thread " + th.getName() + " still running after 60s: deadlock or hang");
        }
        if (failure.get() != null) throw new AssertionError("worker crashed", failure.get());

        InvariantChecker.Stats st = InvariantChecker.check(eng.auditLog());

        // Final engine state must agree with what the replay says was sold.
        for (String seat : seats) {
            SeatStatus s = eng.seatStatus(seat);
            String soldTo = st.soldTo.get(seat);
            if (soldTo != null) {
                RuleTests.eq(s, SeatStatus.sold(soldTo), "final status of " + seat);
            } else {
                RuleTests.check(s.kind() != SeatStatus.Kind.SOLD, seat + " is SOLD in engine but never confirmed in log");
            }
        }
        return st;
    }

    /**
     * Two users repeatedly ask for the same two seats in OPPOSITE order. With per-seat locks
     * taken in request order this is the classic deadlock; with our single lock it must finish.
     */
    static void oppositeOrderNoDeadlock() throws Exception {
        FakeClock clock = new FakeClock(1000);
        HoldEngine eng = new HoldEngine(clock, true);
        int rounds = 100_000;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong wins = new AtomicLong();

        Thread a = worker(eng, "alice", List.of("S1", "S2"), rounds, wins, failure);
        Thread b = worker(eng, "bob", List.of("S2", "S1"), rounds, wins, failure);
        a.start();
        b.start();
        a.join(30_000);
        b.join(30_000);
        if (a.isAlive() || b.isAlive()) throw new AssertionError("DEADLOCK: opposite-order hold_many hung");
        if (failure.get() != null) throw new AssertionError("worker crashed", failure.get());
        InvariantChecker.check(eng.auditLog());
        RuleTests.check(wins.get() > 0, "nobody ever got both seats");
        System.out.println("    successful hold_many calls: " + wins.get() + " of " + (2 * rounds));
    }

    private static Thread worker(HoldEngine eng, String user, List<String> seats, int rounds,
                                 AtomicLong wins, AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    Result<List<Long>> r = eng.holdMany(user, seats, 100);
                    if (r.isOk()) {
                        wins.incrementAndGet();
                        for (long id : r.value()) eng.release(id);
                    }
                }
            } catch (Throwable x) {
                failure.compareAndSet(null, x);
            }
        }, "opp-" + user);
        t.setDaemon(true);
        return t;
    }
}
