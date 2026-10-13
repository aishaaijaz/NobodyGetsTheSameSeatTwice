package tickets;

/** Injectable time source. Tests use FakeClock, never sleep. */
public interface Clock {
    long nowSeconds();
}
