package tickets;

import java.util.concurrent.atomic.AtomicLong;

/** Manually-advanced clock for tests. Thread-safe; only ever moves forward via advance(). */
public final class FakeClock implements Clock {
    private final AtomicLong now;

    public FakeClock(long startSeconds) {
        this.now = new AtomicLong(startSeconds);
    }

    @Override
    public long nowSeconds() {
        return now.get();
    }

    public void advance(long seconds) {
        if (seconds < 0) throw new IllegalArgumentException("clock must not go backwards");
        now.addAndGet(seconds);
    }
}
