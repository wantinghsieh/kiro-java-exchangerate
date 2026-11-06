package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Same-key contention: many threads register the same pair and instant concurrently. */
class ExchangeRateServiceSameKeyConcurrencyTest {

    private static final int THREADS = 64;
    private static final Instant AT = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    @Timeout(30)
    void sameKeyContentionLeavesExactlyOneRegisteredValue() throws Exception {
        var service = new ExchangeRateService(new MutableClock(AT));
        var pair = new CurrencyPair("USD", "EUR");
        var ready = new CountDownLatch(THREADS);
        var start = new CountDownLatch(1);

        // Distinct rates; scale 12 so that rounding to scale 10 is exercised.
        List<BigDecimal> submitted = new ArrayList<>();
        Set<BigDecimal> expectedRounded = new HashSet<>();
        for (int i = 0; i < THREADS; i++) {
            var rate = BigDecimal.valueOf(1_000_000_000_000L + i * 137L, 12);
            submitted.add(rate);
            expectedRounded.add(rate.setScale(ExchangeRateService.RATE_SCALE,
                    java.math.RoundingMode.HALF_EVEN));
        }

        var returned = new ArrayList<ExchangeRate>();
        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            List<Future<ExchangeRate>> futures = new ArrayList<>();
            for (var rate : submitted) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.register("USD", "EUR", rate, AT);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads should be ready");
            start.countDown();
            for (var f : futures) {
                returned.add(f.get(20, TimeUnit.SECONDS));
            }
        }

        var history = service.history(pair);
        assertEquals(1, history.size(), "exactly one entry must remain");
        var entry = history.getFirst();
        assertEquals(AT, entry.effectiveAt());
        assertEquals(pair, entry.pair());
        assertTrue(expectedRounded.contains(entry.rate()),
                "remaining rate must equal one registered (rounded) value: " + entry.rate());
        assertEquals(entry, service.rateAsOf(pair, AT).orElseThrow());
        for (var r : returned) {
            assertTrue(expectedRounded.contains(r.rate()), "returned value must be a rounded input");
        }
    }
}
