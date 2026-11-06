package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Readers (query and convert) run while writers register. Every registered rate is a pure
 * function of its effective instant, so any observed value can be checked against the set of
 * values that were ever registered. Histories must always be strictly ascending and, because
 * writers only add distinct instants, each reader must see them grow monotonically.
 */
class ExchangeRateServiceReadWriteConcurrencyTest {

    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant NOW = BASE.plus(Duration.ofDays(365));
    private static final CurrencyPair PAIR = new CurrencyPair("USD", "EUR");
    private static final int WRITERS = 8;
    private static final int PER_WRITER = 200;
    private static final int READERS = 8;
    private static final int TOTAL = WRITERS * PER_WRITER;

    /** Effective instant for entry k. */
    private static Instant instantFor(int k) {
        return BASE.plusSeconds(k);
    }

    /** The only rate ever registered at instant k (scale 10 already). */
    private static BigDecimal rateFor(int k) {
        return BigDecimal.valueOf(1000 + k, 3).setScale(10, RoundingMode.HALF_EVEN);
    }

    private static int indexOf(Instant at) {
        long k = Duration.between(BASE, at).getSeconds();
        assertTrue(k >= 0 && k < TOTAL, "unexpected effective instant " + at);
        return (int) k;
    }

    private static void checkRate(ExchangeRate r) {
        assertTrue(r != null, "null rate observed");
        assertEquals(PAIR, r.pair());
        assertEquals(0, rateFor(indexOf(r.effectiveAt())).compareTo(r.rate()),
                "rate not derived from a registered value: " + r);
        assertEquals(10, r.rate().scale());
    }

    private static void checkHistory(List<ExchangeRate> h) {
        Instant prev = null;
        for (var r : h) {
            checkRate(r);
            if (prev != null) {
                assertTrue(r.effectiveAt().isAfter(prev), "history not strictly ascending");
            }
            prev = r.effectiveAt();
        }
    }

    private static void checkConversion(ConversionResult c, boolean inverse) {
        var stored = c.exchangeRateUsed().orElseThrow();
        checkRate(stored);
        var expectedMultiplier = inverse
                ? BigDecimal.ONE.divide(stored.rate(), 10, RoundingMode.HALF_EVEN)
                : stored.rate();
        assertEquals(0, expectedMultiplier.compareTo(c.appliedRate()), "applied rate mismatch");
        var expected = c.sourceAmount().multiply(expectedMultiplier)
                .setScale(2, RoundingMode.HALF_EVEN);
        assertEquals(0, expected.compareTo(c.convertedAmount()), "converted amount mismatch");
        assertEquals(2, c.convertedAmount().scale());
    }

    private static void readerLoop(ExchangeRateService service, AtomicBoolean stop,
            CyclicBarrier start, AtomicLong iterations) throws Exception {
        start.await();
        int lastSize = 0;
        var amount = new BigDecimal("100.00");
        do {
            var all = service.history(PAIR);
            checkHistory(all);
            assertTrue(all.size() >= lastSize, "history shrank: " + all.size() + " < " + lastSize);
            lastSize = all.size();

            var ranged = service.history(PAIR, instantFor(TOTAL / 4), instantFor(TOTAL / 2));
            checkHistory(ranged);
            for (var r : ranged) {
                assertTrue(!r.effectiveAt().isBefore(instantFor(TOTAL / 4))
                        && r.effectiveAt().isBefore(instantFor(TOTAL / 2)));
            }

            service.currentRate("USD", "EUR").ifPresent(ExchangeRateServiceReadWriteConcurrencyTest::checkRate);
            var asOfRate = service.rateAsOf(PAIR, instantFor(TOTAL / 2));
            asOfRate.ifPresent(r -> {
                checkRate(r);
                assertTrue(!r.effectiveAt().isAfter(instantFor(TOTAL / 2)));
            });

            try {
                checkConversion(service.convert(amount, "USD", "EUR"), false);
                checkConversion(service.convert(amount, "EUR", "USD"), true);
            } catch (RateNotFoundException _) {
                // Only acceptable before the first registration became visible.
                assertTrue(lastSize == 0 || service.history(PAIR).isEmpty()
                        || service.currentRate("USD", "EUR").isEmpty(),
                        "RateNotFoundException although a rate was already effective");
            }
            iterations.incrementAndGet();
        } while (!stop.get());
    }

    @Test
    @Timeout(30)
    void readersOnlyObserveRegisteredConsistentValuesWhileWritersRegister() throws Exception {
        var service = new ExchangeRateService(new MutableClock(NOW));
        var stop = new AtomicBoolean(false);
        var iterations = new AtomicLong();
        var start = new CyclicBarrier(WRITERS + READERS);
        var writersDone = new CountDownLatch(WRITERS);
        var failures = new ConcurrentLinkedQueue<Throwable>();

        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS)) {
            List<Future<?>> readerFutures = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                readerFutures.add(pool.submit(() -> {
                    try {
                        readerLoop(service, stop, start, iterations);
                    } catch (Throwable t) {
                        failures.add(t);
                        stop.set(true);
                    }
                    return null;
                }));
            }
            for (int w = 0; w < WRITERS; w++) {
                final int writer = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < PER_WRITER; i++) {
                            int k = i * WRITERS + writer;
                            var stored = service.register("USD", "EUR", rateFor(k), instantFor(k));
                            assertEquals(0, rateFor(k).compareTo(stored.rate()));
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                        stop.set(true);
                    } finally {
                        writersDone.countDown();
                    }
                    return null;
                });
            }

            assertTrue(writersDone.await(25, TimeUnit.SECONDS), "writers did not finish");
            stop.set(true);
            for (var f : readerFutures) {
                f.get(25, TimeUnit.SECONDS);
            }
        }

        assertTrue(failures.isEmpty(), () -> "reader/writer failure: " + failures.peek());
        assertTrue(iterations.get() > 0, "readers never ran");

        var finalHistory = service.history(PAIR);
        assertEquals(TOTAL, finalHistory.size());
        checkHistory(finalHistory);
        for (int k = 0; k < TOTAL; k++) {
            assertEquals(instantFor(k), finalHistory.get(k).effectiveAt());
        }
    }
}
