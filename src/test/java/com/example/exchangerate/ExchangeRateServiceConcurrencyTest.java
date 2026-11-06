package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Concurrency tests for {@link ExchangeRateService} (Requirements 5.1, 5.2, 7.4, 7.5, 7.6, 7.9). */
class ExchangeRateServiceConcurrencyTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final int THREADS = 48;
    private static final String[] TARGETS = {"EUR", "GBP", "JPY"};
    private static final int PAIRS = TARGETS.length;

    /** Thread i registers a distinct (pair, instant) combination with a distinct rate. */
    private static BigDecimal rateFor(int i) {
        return BigDecimal.valueOf(1_000_000_000L + i, 10);
    }

    private static CurrencyPair pairFor(int i) {
        return new CurrencyPair("USD", TARGETS[i % PAIRS]);
    }

    private static Instant instantFor(int i) {
        return T0.plusSeconds(i / PAIRS);
    }

    @Test
    @Timeout(30)
    void concurrentRegistrationsOfDistinctRatesAreAllStoredUnaltered() throws Exception {
        var service = new ExchangeRateService(new MutableClock(T0));
        var barrier = new CyclicBarrier(THREADS);
        var returned = new ArrayList<Future<ExchangeRate>>();

        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                final int idx = i;
                returned.add(executor.submit(() -> {
                    CurrencyPair pair = pairFor(idx);
                    barrier.await(20, TimeUnit.SECONDS);
                    return service.register(pair.base(), pair.target(), rateFor(idx), instantFor(idx));
                }));
            }

            // Propagate any worker exception.
            for (int i = 0; i < THREADS; i++) {
                try {
                    ExchangeRate r = returned.get(i).get(25, TimeUnit.SECONDS);
                    assertEquals(new ExchangeRate(pairFor(i), rateFor(i), instantFor(i)), r);
                } catch (ExecutionException e) {
                    throw new AssertionError("worker " + i + " failed", e.getCause());
                }
            }
        }

        Map<CurrencyPair, List<ExchangeRate>> byPair = new HashMap<>();
        int total = 0;
        for (int p = 0; p < PAIRS; p++) {
            CurrencyPair pair = new CurrencyPair("USD", TARGETS[p]);
            List<ExchangeRate> history = service.history(pair);
            byPair.put(pair, history);
            total += history.size();
            assertEquals(history.stream().map(ExchangeRate::effectiveAt).distinct().count(), history.size(),
                    "duplicate instants for " + pair);
            for (int j = 1; j < history.size(); j++) {
                assertTrue(history.get(j - 1).effectiveAt().isBefore(history.get(j).effectiveAt()));
            }
        }
        assertEquals(THREADS, total, "every registration present exactly once");

        for (int i = 0; i < THREADS; i++) {
            var expected = new ExchangeRate(pairFor(i), rateFor(i), instantFor(i));
            assertEquals(1, byPair.get(pairFor(i)).stream().filter(expected::equals).count(),
                    "missing, duplicated or altered: " + expected);
        }
    }
}
