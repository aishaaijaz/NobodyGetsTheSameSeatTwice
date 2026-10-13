package tickets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

/**
 * Randomized model-based test: same random operation sequence into HoldEngine and ReferenceModel
 * (single-threaded, shared fake clock). After every op: results must be equal AND every seat's
 * status must be equal. Failure message contains seed, sequence and step -> exactly reproducible.
 */
final class ModelTest {
    static void run(long seed, int sequences, int opsPerSequence) {
        String[] seats = {"S0", "S1", "S2", "S3", "S4", "S5", "S6"};
        String[] users = {"A", "B", "C"};
        Map<String, Integer> tally = new TreeMap<>();
        int graceConfirms = 0;

        for (int s = 0; s < sequences; s++) {
            Random r = new Random(seed + s);
            FakeClock clock = new FakeClock(1000);
            HoldEngine eng = new HoldEngine(clock, true);
            ReferenceModel ref = new ReferenceModel(clock);

            for (int i = 0; i < opsPerSequence; i++) {
                String desc;
                Object got, want;
                int k = r.nextInt(100);
                if (k < 35) {
                    String seat = seats[r.nextInt(seats.length)], user = users[r.nextInt(users.length)];
                    long ttl = 1 + r.nextInt(8);
                    desc = "hold(" + seat + "," + user + "," + ttl + ")";
                    got = eng.hold(seat, user, ttl);
                    want = ref.hold(seat, user, ttl);
                } else if (k < 50) {
                    String user = users[r.nextInt(users.length)];
                    List<String> req = new ArrayList<>();
                    int n = 1 + r.nextInt(4);
                    for (int j = 0; j < n; j++) req.add(seats[r.nextInt(seats.length)]);
                    long ttl = 1 + r.nextInt(8);
                    desc = "holdMany(" + user + "," + req + "," + ttl + ")";
                    got = eng.holdMany(user, req, ttl);
                    want = ref.holdMany(user, req, ttl);
                } else if (k < 70) {
                    long id = r.nextInt((int) ref.lastId() + 3);
                    desc = "confirm(" + id + ")";
                    got = eng.confirm(id);
                    want = ref.confirm(id);
                } else if (k < 82) {
                    long id = r.nextInt((int) ref.lastId() + 3);
                    desc = "release(" + id + ")";
                    got = eng.release(id);
                    want = ref.release(id);
                } else {
                    long d = r.nextInt(7);
                    desc = "advance(" + d + ")";
                    clock.advance(d);
                    got = want = null;
                }

                String where = "seed=" + seed + " sequence=" + s + " step=" + i + " op=" + desc;
                if (!Objects.equals(got, want)) {
                    throw new AssertionError("MISMATCH " + where + "\n  engine:    " + got + "\n  reference: " + want);
                }
                if (got instanceof Result<?> res) {
                    tally.merge(res.isOk() ? "OK" : res.error().name(), 1, Integer::sum);
                }
                for (String seat : seats) {
                    SeatStatus a = eng.seatStatus(seat), b = ref.seatStatus(seat);
                    if (!a.equals(b)) {
                        throw new AssertionError("STATUS MISMATCH on " + seat + " after " + where
                                + "\n  engine:    " + a + "\n  reference: " + b);
                    }
                }
            }
            graceConfirms += InvariantChecker.check(eng.auditLog()).graceConfirms;
        }

        // Make sure the test actually reached the interesting corners (otherwise it proves nothing).
        for (String needed : new String[]{"OK", "SEAT_HELD", "SEAT_SOLD", "LIMIT_EXCEEDED",
                "HOLD_NOT_ACTIVE", "ALREADY_CONFIRMED", "UNKNOWN_HOLD", "EMPTY_REQUEST"}) {
            if (needed.equals("EMPTY_REQUEST")) continue; // generator never sends empty lists; covered in RuleTests
            if (!tally.containsKey(needed)) throw new AssertionError("model test is vacuous: never saw " + needed + " " + tally);
        }
        if (graceConfirms == 0) throw new AssertionError("model test is vacuous: no grace confirm ever happened");
        System.out.println("    outcomes: " + tally + ", graceConfirms=" + graceConfirms);
    }
}
