package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Property test for replacement of a rate registered at the same pair and instant. */
class ReplacementPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000_000_000L),
                Arbitraries.integers().between(0, 15))
            .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 10_000_000_000L).map(Instant::ofEpochSecond);
    }

    // Feature: currency-exchange-rate-service, Property 3: Same pair and instant replaces
    // Validates: Requirements 1.3
    @Property(tries = 200)
    void sameInstantRegistrationReplacesEarlierRate(
            @ForAll("codes") String base,
            @ForAll("codes") String target,
            @ForAll("rates") BigDecimal first,
            @ForAll("rates") BigDecimal second,
            @ForAll("instants") Instant at) {
        Assume.that(!base.equals(target));
        BigDecimal firstRounded = first.setScale(ExchangeRateService.RATE_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal secondRounded = second.setScale(ExchangeRateService.RATE_SCALE, RoundingMode.HALF_EVEN);
        Assume.that(firstRounded.signum() > 0 && secondRounded.signum() > 0);

        var service = new ExchangeRateService(new MutableClock(EPOCH));
        service.register(base, target, first, at);
        service.register(base, target, second, at);

        List<ExchangeRate> history = service.history(new CurrencyPair(base, target));
        List<ExchangeRate> atInstant = history.stream()
            .filter(r -> r.effectiveAt().equals(at))
            .toList();
        assertEquals(1, atInstant.size());
        assertEquals(1, history.size());
        assertEquals(0, secondRounded.compareTo(atInstant.get(0).rate()));
        assertTrue(atInstant.get(0).rate().scale() <= ExchangeRateService.RATE_SCALE);
    }
}
