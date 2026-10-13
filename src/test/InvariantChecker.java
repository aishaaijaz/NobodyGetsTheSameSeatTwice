package tickets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays an audit log from scratch and checks the business invariants. It does NOT call the
 * engine, so engine bugs cannot hide behind engine logic. Throws AssertionError on violation.
 */
final class InvariantChecker {
    static final class Stats {
        int holds, confirms, graceConfirms, releases;
        final Map<String, String> soldTo = new HashMap<>(); // seat -> user
    }

    static Stats check(List<Event> log) {
        Stats st = new Stats();
        Map<String, Long> slot = new HashMap<>();     // seat -> hold id currently occupying it
        Map<Long, Event> created = new HashMap<>();   // hold id -> its creation event
        long last = Long.MIN_VALUE;

        for (Event e : log) {
            req(e.time() >= last, "clock went backwards: " + e);
            last = e.time();

            switch (e.type()) {
                case HOLD_CREATED -> {
                    req(!st.soldTo.containsKey(e.seat()), "hold created on a SOLD seat: " + e);
                    Long occ = slot.get(e.seat());
                    if (occ != null) {
                        req(created.get(occ).expiresAt() <= e.time(), "two live holds on one seat: " + e);
                    }
                    req(e.expiresAt() > e.time(), "hold born already expired: " + e);
                    created.put(e.holdId(), e);
                    slot.put(e.seat(), e.holdId());
                    st.holds++;

                    int live = 0;
                    for (long hid : slot.values()) {
                        Event c = created.get(hid);
                        if (c.user().equals(e.user()) && c.expiresAt() > e.time()) live++;
                    }
                    req(live <= HoldEngine.MAX_LIVE_HOLDS, "user over 4 live holds: " + e);
                }
                case CONFIRMED -> {
                    Long occ = slot.get(e.seat());
                    req(occ != null && occ == e.holdId(),
                            "confirmed a hold that is not the seat's current hold (retaken or already gone): " + e);
                    req(!st.soldTo.containsKey(e.seat()), "DOUBLE SALE: " + e);
                    st.soldTo.put(e.seat(), e.user());
                    slot.remove(e.seat());
                    st.confirms++;
                    if (e.expiresAt() <= e.time()) st.graceConfirms++;
                }
                case RELEASED -> {
                    Long occ = slot.get(e.seat());
                    req(occ != null && occ == e.holdId(), "released a hold that is not current: " + e);
                    slot.remove(e.seat());
                    st.releases++;
                }
            }
        }
        return st;
    }

    private static void req(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
