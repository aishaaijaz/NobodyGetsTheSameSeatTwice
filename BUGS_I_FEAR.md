# BUGS_I_FEAR

## The bug class my tests would NOT catch: time that misbehaves in the real world

All my tests use a `FakeClock` that only moves **forward**, and the engine reads it **once per operation under one lock**. So the tests can never produce:

- the wall clock **stepping backwards** (NTP correction, VM pause/resume, leap-second smear, manual change) -> a hold that was expired becomes live again, so a seat that already showed FREE, or that was grace-confirmable, flips back to HELD; two readers on either side of the jump disagree about who owns the seat;
- **clock skew between servers**, if this engine is ever run on more than one node (or a hold is created on node A and confirmed on node B): node B's "now" is behind A's, so an expired hold looks live, or the reverse.

My invariant checker even asserts `time never goes backwards` in the log, so it would flag the symptom only in a world where the fake clock already behaved.

### How I would catch it in production
1. **Use a monotonic source for TTLs** (`System.nanoTime()`-based `Clock`), never `System.currentTimeMillis()`; keep wall time only for display.
2. **Independent double-sale detector that does not trust the engine:** persist every sale with a **unique constraint on `seat_id`**; a duplicate insert is a loud error. Plus a periodic reconciliation job: `SELECT seat_id, COUNT(*) FROM sales GROUP BY 1 HAVING COUNT(*) > 1` must be empty; page someone if not.
3. **Metrics + alerts:** `clock_regression_total` (current reading < previous reading), grace-confirm rate (sudden jump = clocks drifting), and holds-outstanding-past-ttl.
4. **If multi-node:** one authority for time and state (the DB's clock, or a single partition owner per seat), not each node's local clock.

## Runner-up fears (shorter)
- **Memory growth:** lazy expiry never deletes holds; a long-lived process leaks. Tests are too short to show it.
- **Rare interleavings:** the stress seed fixes each thread's op stream but not the OS scheduler, so a failure may not repeat. A single global lock makes the space of interleavings small, which is why I accepted it.
