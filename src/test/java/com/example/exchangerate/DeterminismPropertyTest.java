package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Assume;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property test for determinism under a fixed clock (Requirement 6.6).
 */
class DeterminismPropertyTest {

    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");
    private static final int REPEATS = 100;

    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.of("USD", "EUR", "JPY", "GBP");
    }

    @Provide
    Arbitrary<BigDecimal> positiveRates() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000_000L),
                Arbitraries.integers().between(0, 8))
            .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Combinators.combine(
                Arbitraries.longs().between(0L, 1_000_000_000L),
                Arbitraries.integers().between(0, 6))
            .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    /** Offsets in seconds from BASE, spanning before and after the clock instant. */
    @Provide
    Arbitrary<List<Long>> offsets() {
        return Arbitraries.longs().between(-1000L, 1000L).list().ofMinSize(0).ofMaxSize(8);
    }

    // Feature: currency-exchange-rate-service, Property 13: Deterministic results under a fixed Clock
    // Validates: Requirements 6.6
    @Property(tries = 100)
    void repeatedQueriesAndConversionsAreEqual(
            @ForAll("codes") String from,
            @ForAll("codes") String to,
            @ForAll("offsets") List<Long> directOffsets,
            @ForAll("offsets") List<Long> inverseOffsets,
            @ForAll("positiveRates") BigDecimal rate,
            @ForAll("amounts") BigDecimal amount,
            @ForAll long clockOffset,
            @ForAll long asOfOffset) {
        Assume.that(!from.equals(to));
        var clock = new MutableClock(BASE.plusSeconds(Math.floorMod(clockOffset, 2001L) - 1000L));
        var service = new ExchangeRateService(clock);
        for (long o : directOffsets) {
            service.register(from, to, rate.add(BigDecimal.valueOf(o, 4).abs()), BASE.plusSeconds(o));
        }
        for (long o : inverseOffsets) {
            service.register(to, from, rate.add(BigDecimal.valueOf(o, 3).abs()), BASE.plusSeconds(o));
        }
        var pair = new CurrencyPair(from, to);
        Instant asOf = BASE.plusSeconds(Math.floorMod(asOfOffset, 2001L) - 1000L);
        Instant start = BASE.minusSeconds(500);
        Instant end = BASE.plusSeconds(500);

        assertRepeatable(() -> service.currentRate(from, to));
        assertRepeatable(() -> service.rateAsOf(pair, asOf));
        assertRepeatable(() -> service.history(pair));
        assertRepeatable(() -> service.history(pair, start, end));
        assertRepeatable(() -> outcome(() -> service.convert(amount, from, to)));
        assertRepeatable(() -> outcome(() -> service.convert(amount, from, to, asOf)));
    }

    /** Evaluates the supplier {@value #REPEATS} times and asserts every result equals the first. */
    private static <T> void assertRepeatable(Supplier<T> op) {
        T first = op.get();
        for (int i = 1; i < REPEATS; i++) {
            assertEquals(first, op.get(), "repetition " + i);
        }
    }

    /** Captures a result or the exception type and message so failures are compared too. */
    private static Object outcome(Supplier<ConversionResult> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            return e.getClass().getName() + ":" + e.getMessage();
        }
    }
}
