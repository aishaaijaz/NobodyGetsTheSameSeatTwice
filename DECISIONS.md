# DECISIONS

Every ambiguity I hit, what I chose, why. Each decision has a test (named in brackets) that fails if the behaviour changes.

## Rule conflicts

### D1. R3 vs R4: "expired hold can still be confirmed if nobody took the seat" vs "holds are never extended"
These collide: if a late confirm works, the hold effectively lived past its TTL.

**Choice:** both are honoured by defining them precisely.
- R4 means the **`expires_at` of a hold never changes** and no call (retry, confirm, anything) pushes it out.
- R3's grace is a **one-shot, confirm-only** privilege of the original hold. It does not make the seat HELD, does not block anyone, and does not change `expires_at`.
- "Nobody has taken the seat" = no *other* hold was ever created on that seat after this one, and the seat is not sold. Once any other hold is created on the seat, the old hold is **dead forever**, even if that new hold is released immediately. (Otherwise a seat could be double-promised: Bob thinks he has it, then Alice's zombie hold wins.)
- While in grace, `seat_status` says **FREE** (the hold is expired), but only the old holder can confirm without a race; anyone else's `hold()` takes the seat and kills the grace.
- Accepted consequence: grace has **no time limit** (spec says "unless nobody else has taken the seat", nothing about a cutoff). A hold from yesterday can be confirmed today if the seat was never touched. A product owner may want a cap; it is a one-line change in `confirm` (`now - expiresAt <= GRACE`) but I did not invent a number.
- Tests: `R3 grace confirm if untouched`, `R3 no confirm once retaken`, `R4 never extended`.

### D2. R5 vs R3/R4: what is an "idempotent retry" of `hold()` on a seat the user already holds?
- Retry while the hold is **live**: return the **same hold_id**, same `expires_at`, ignore the new `ttl`. Not an extension, so R4 holds.
- Retry after the hold **expired** (even if it is still confirmable by grace): this is **not** a retry. It creates a **new hold** with a fresh TTL and a new id; the old id is superseded and can no longer be confirmed. Returning the old id here would silently revive an expired hold, which is an extension in disguise.
- A retry with a different `ttl` is not an error; the extra ttl is ignored.
- Test: `R5 retry idempotent / new hold after expiry`.

### D3. R2 vs R5: does a retry count as another seat?
No. A retry returns the existing hold and uses no extra slot, so a user holding 4 seats can still retry any of them. [R5 test]

### D4. R2: what counts toward the limit of 4?
Only **live** holds: `expires_at > now`. Not counted: expired holds (incl. grace), released holds, confirmed seats (SOLD is no longer a hold).
Consequence: a user with 4 live holds can still grace-confirm an expired fifth, because that does not create a hold. Test: `R2 max four live holds`.

### D5. R2 vs R6: hold_many when the batch would push the user over 4
- Duplicates in the list are collapsed first (`[S1,S1,S2]` = `[S1,S2]`).
- Seats the user already holds live are idempotent (R5) and cost **0** new slots; they are returned with their existing ids.
- Check is `live_now + brand_new_seats <= 4`. If not, the whole call fails with `LIMIT_EXCEEDED` and **nothing** is held. Same for a blocked seat: nothing is held.
- It is validate-everything first, then apply, all under one lock, so there is no partially-applied state to roll back. Test: `R6 ...` (x2).

### D6. R6: signature
Spec gives `hold_many(user, [seats])` without ttl. I added `ttl_seconds` (same value for all seats), since every hold needs one.

## Smaller ambiguities

| # | Question | Choice |
|---|----------|--------|
| D7 | When exactly does a hold expire? | At `now >= expires_at` (so a hold with ttl 10 made at t=100 is live at 109, expired at 110). |
| D8 | ttl <= 0 | `INVALID_TTL`. |
| D9 | hold_many with empty list | `EMPTY_REQUEST`. |
| D10 | Error precedence (hold) | ttl valid -> seat sold -> seat held by other -> own live hold (idempotent OK) -> limit. For hold_many: ttl -> empty -> per seat in order (sold, held by other) -> limit. Reference model uses the same order, so the model test pins it down. |
| D11 | confirm/release an already-confirmed hold | `ALREADY_CONFIRMED` (distinct from "lost"). Not idempotent success: a double confirm is more likely a bug than a retry. |
| D12 | confirm/release a released or superseded hold | `HOLD_NOT_ACTIVE`. |
| D13 | release of an expired-but-untouched (grace) hold | Allowed; it just frees the slot. |
| D14 | Who may confirm? | `confirm(hold_id)` has no user argument in the spec, so holding the id is the credential. Authentication is out of scope. |
| D15 | Unknown seat ids | Accepted lazily; they are simply FREE. No seat catalogue was specified. |
| D16 | null arguments | `NullPointerException` (programmer error, not a business error). |

## Expiry strategy
**Lazy.** Nothing runs on a timer. A hold is live iff `expires_at > now`, evaluated at each call. Reason: no background thread to race with, and lazy expiry is *required* for D1 (the expired hold must stay around so grace confirm can find it). Downside: memory grows with number of holds ever made; fine for in-memory scope, would need compaction in production.

## Concurrency
- **One `ReentrantLock` guards all state.** Every public method takes it, reads the clock **once** inside it, validates, mutates, unlocks.
- Why: simplest strategy I can fully explain and prove. R1 (no double sale), R2 (per-user count spans many seats) and R6 (atomic multi-seat) all need cross-seat atomicity, which a single lock gives for free.
- Deadlock: impossible with one lock. `hold_many([1,2])` vs `hold_many([2,1])` just serialize. [test: `opposite-order hold_many, no deadlock`]
- Cost: no parallelism between unrelated seats. At ticketing scale one JVM could do far more ops/s than the network can feed it, but if profiling ever shows contention the upgrade is: per-seat locks acquired **in sorted seat-id order** plus a per-user lock for the R2 counter acquired **after** all seat locks (fixed global order: seats sorted, then user). I did not do this: more locks = more ways to be subtly wrong in a 60-minute build.
- Clock read inside the lock means timestamps in the audit log are non-decreasing in log order, and one operation sees one consistent `now`.