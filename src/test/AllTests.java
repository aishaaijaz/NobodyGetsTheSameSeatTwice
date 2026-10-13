package tickets;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free test runner. Usage: java -cp out tickets.AllTests [seed] */
public final class AllTests {
    interface T {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : System.nanoTime();
        System.out.println("SEED=" + seed + "   (reproduce: java -cp out tickets.AllTests " + seed + ")");

        Map<String, T> tests = new LinkedHashMap<>();
        tests.put("R1 one holder, one sale", RuleTests::r1_oneHolderAndOneSale);
        tests.put("R2 max four live holds", RuleTests::r2_maxFourLiveHolds);
        tests.put("R3 grace confirm if untouched", RuleTests::r3_graceConfirmWhenUntouched);
        tests.put("R3 no confirm once retaken", RuleTests::r3_noConfirmOnceRetaken);
        tests.put("R4 never extended", RuleTests::r4_neverExtended);
        tests.put("R5 retry idempotent / new hold after expiry", RuleTests::r5_retryIdempotentWhileLive_newHoldAfterExpiry);
        tests.put("R6 hold_many all-or-nothing", RuleTests::r6_holdManyIsAllOrNothing);
        tests.put("R6 expired holds don't count", RuleTests::r6_expiredHoldsDontCount);
        tests.put("misc errors", RuleTests::misc_errors);
        tests.put("invariant checker can fail", RuleTests::checker_catchesBrokenLogs);
        tests.put("model test vs reference (3000 x 150 ops)", () -> ModelTest.run(seed, 3000, 150));
        tests.put("opposite-order hold_many, no deadlock", StressTest::oppositeOrderNoDeadlock);
        tests.put("stress: 1000 rounds x 8 threads x 200 ops", () -> StressTest.run(seed, 1000, 8, 200));

        int failed = 0;
        for (Map.Entry<String, T> e : tests.entrySet()) {
            try {
                e.getValue().run();
                System.out.println("PASS  " + e.getKey());
            } catch (Throwable x) {
                failed++;
                System.out.println("FAIL  " + e.getKey() + "\n      " + x);
                if (x.getCause() != null) System.out.println("      caused by " + x.getCause());
            }
        }
        System.out.println(failed == 0 ? "ALL PASSED (seed " + seed + ")" : failed + " FAILED (seed " + seed + ")");
        System.exit(failed == 0 ? 0 : 1);
    }
}
