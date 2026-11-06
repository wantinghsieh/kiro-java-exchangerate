package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies Requirement 5.6: operations on an already-interrupted thread complete without losing
 * data consistency and preserve the thread's interrupted status.
 *
 * <p>All interrupted work runs on a dedicated thread so the JUnit thread's flag is never touched.
 */
class ExchangeRateServiceInterruptTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2024-01-02T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final ExchangeRateService service = new ExchangeRateService(clock);
    private final CurrencyPair usdEur = new CurrencyPair("USD", "EUR");

    /** Runs the body on a fresh thread whose interrupt flag is set; rethrows any failure. */
    private static void runInterrupted(Runnable body) throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "interrupted-worker");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError("Interrupted worker failed", failure.get());
        }
    }

    /** Runs the supplier and asserts the interrupted flag is still set afterwards. */
    private static <T> T preserved(String op, Supplier<T> call) {
        T result = call.get();
        assertTrue(Thread.currentThread().isInterrupted(), op + " cleared the interrupted status");
        return result;
    }

    @Test
    @Timeout(30)
    void allOperationsCompleteAndPreserveInterruptedStatus() throws InterruptedException {
        clock.set(T1); // default-instant registration lands at T1, distinct from T0
        var observed = new AtomicReference<List<ExchangeRate>>();

        runInterrupted(() -> {
            var registered = preserved("register",
                    () -> service.register("USD", "EUR", new BigDecimal("0.9"), T0));
            assertEquals(usdEur, registered.pair());

            preserved("register(default instant)",
                    () -> service.register("USD", "EUR", new BigDecimal("0.95")));

            var current = preserved("currentRate", () -> service.currentRate("USD", "EUR"));
            assertEquals(new BigDecimal("0.9500000000"), current.orElseThrow().rate());

            var asOf = preserved("rateAsOf", () -> service.rateAsOf(usdEur, T0));
            assertTrue(asOf.isPresent());

            var hist = preserved("history", () -> service.history(usdEur));
            observed.set(hist);

            var ranged = preserved("history(range)", () -> service.history(usdEur, T0, T1));
            assertEquals(1, ranged.size());

            var converted = preserved("convert",
                    () -> service.convert(new BigDecimal("100"), "USD", "EUR"));
            assertEquals(new BigDecimal("95.00"), converted.convertedAmount());

            var convertedAsOf = preserved("convert(asOf)",
                    () -> service.convert(new BigDecimal("100"), "USD", "EUR", T0));
            assertEquals(new BigDecimal("90.00"), convertedAsOf.convertedAmount());

            var inverse = preserved("convert(inverse)",
                    () -> service.convert(new BigDecimal("10"), "EUR", "USD", T0));
            assertTrue(inverse.convertedAmount().signum() > 0);
        });

        assertEquals(2, observed.get().size());

        // Stored data is consistent when read from the (non-interrupted) JUnit thread.
        assertFalse(Thread.currentThread().isInterrupted());
        var stored = service.history(usdEur);
        assertEquals(2, stored.size());
        assertEquals(new BigDecimal("0.9000000000"), stored.get(0).rate());
        assertEquals(T0, stored.get(0).effectiveAt());
        assertEquals(new BigDecimal("0.9500000000"), stored.get(1).rate());
        assertEquals(T1, stored.get(1).effectiveAt());
        assertTrue(service.history(usdEur.inverse()).isEmpty());
    }

    @Test
    @Timeout(30)
    void rejectedOperationsOnInterruptedThreadLeaveDataUnchangedAndStatusSet()
            throws InterruptedException {
        service.register("USD", "EUR", new BigDecimal("0.9"), T0);
        var before = service.history(usdEur);

        runInterrupted(() -> {
            var thrown = new AtomicReference<Throwable>();
            try {
                service.register("USD", "EUR", new BigDecimal("-1"), T1);
            } catch (IllegalArgumentException e) {
                thrown.set(e);
            }
            assertTrue(thrown.get() != null, "invalid register must be rejected");
            assertTrue(Thread.currentThread().isInterrupted());

            thrown.set(null);
            try {
                service.convert(new BigDecimal("1"), "USD", "JPY");
            } catch (RateNotFoundException e) {
                thrown.set(e);
            }
            assertTrue(thrown.get() != null, "missing rate must raise RateNotFoundException");
            assertTrue(Thread.currentThread().isInterrupted());
        });

        assertEquals(before, service.history(usdEur));
        assertNull(service.history(new CurrencyPair("USD", "JPY")).stream().findFirst().orElse(null));
    }

    @Test
    @Timeout(30)
    void interruptedWriterAndPlainReaderSeeConsistentData() throws InterruptedException {
        runInterrupted(() -> {
            for (int i = 1; i <= 20; i++) {
                int n = i;
                preserved("register#" + n, () -> service.register("USD", "EUR",
                        BigDecimal.valueOf(n), T0.plusSeconds(n)));
            }
        });

        assertFalse(Thread.currentThread().isInterrupted());
        var stored = service.history(usdEur);
        assertEquals(20, stored.size());
        for (int i = 0; i < stored.size(); i++) {
            assertEquals(T0.plusSeconds(i + 1L), stored.get(i).effectiveAt());
            assertEquals(0, BigDecimal.valueOf(i + 1L).compareTo(stored.get(i).rate()));
        }
    }
}
