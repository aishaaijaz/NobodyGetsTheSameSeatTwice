# Seat hold engine

In-memory, thread-safe ticket hold engine in plain Java (17+), no dependencies, no build tool.

## Run

```bash
./run.sh            # random seed, printed at the top
./run.sh 12345      # reproduce a run
```

Without bash (Windows):

```bat
dir /s /b src\*.java > sources.txt
javac -d out @sources.txt
java -cp out tickets.AllTests 12345
```

Exit code is 0 only if all tests pass.

## API (`tickets.HoldEngine`)

| Call | Returns |
|------|---------|
| `hold(seatId, userId, ttlSeconds)` | `Result<Long>` hold id, or error |
| `holdMany(userId, seatIds, ttlSeconds)` | `Result<List<Long>>`, all-or-nothing |
| `confirm(holdId)` | `Result<Void>` |
| `release(holdId)` | `Result<Void>` |
| `seatStatus(seatId)` | `SeatStatus` FREE / HELD(user, expiresAt) / SOLD(user) |

Clock is injected (`Clock`); tests use `FakeClock` and never sleep. Errors are in `Err`.

## What works
- All six rules (R1-R6), with the conflicts between them resolved in `DECISIONS.md`.
- Atomic `holdMany`; no deadlock for overlapping seat sets in opposite order (single lock).
- Tests (`src/test/java/tickets`):
  - `RuleTests`: one test per rule and per rule-conflict decision, plus a test that the invariant checker really fails on broken logs.
  - `ModelTest`: 3000 random sequences x 150 ops vs `ReferenceModel` (a deliberately simple single-threaded implementation that derives state by scanning a list). Compares every result and every seat status after every op. Seed printed; failure message has seed + sequence + step.
  - `StressTest`: 1000 short rounds x 8 threads x 200 ops on 6 seats / 5 users with a moving fake clock; random hold, hold_many (random seat order), confirm, release. Afterwards `InvariantChecker` replays the audit log: no double sale, no user over 4 live holds, no two live holds on a seat, no confirm of a hold whose seat was retaken. Also asserts it exercised grace confirms (so it is not vacuous). A separate test hammers opposite-order `holdMany` for deadlock.
- I checked the tests can fail: breaking the engine (ignoring other users' holds; removing the lock) makes them fail.

## What does not / limits
- **One global lock**: correct and simple, but no parallelism across seats (see DECISIONS.md for the upgrade path).
- **In memory only**; the hold history and audit log grow without bound (audit is off by default; lazy expiry never evicts).
- **Grace confirm has no time limit** (spec did not give one).
- **Stress tests are not bit-reproducible**: the seed fixes the operations each thread tries, not the thread scheduling.
- No authentication: whoever has a hold id can confirm/release it.
- See `BUGS_I_FEAR.md` for what the tests cannot catch (clock misbehaviour).
