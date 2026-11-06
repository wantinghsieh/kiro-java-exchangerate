package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property test for registration rounding. Test class state is not shared across tries. */
class RegistrationRoundingPropertyTest {

    // Feature: currency-exchange-rate-service, Property 1: Registration stores the rounded rate
    // Validates: Requirements 1.1, 1.4, 1.9
    @Property(tries = 200)
    void registrationStoresRoundedRate(
            @ForAll("pairs") CurrencyPair pair,
            @ForAll("rates") BigDecimal rate,
            @ForAll("instants") Instant at) {
        BigDecimal expected = rate.setScale(ExchangeRateService.RATE_SCALE, RoundingMode.HALF_EVEN);
        Assume.that(expected.signum() > 0);

        var service = new ExchangeRateService(new MutableClock(Instant.EPOCH));
        List<ExchangeRate> inverseBefore = service.history(pair.inverse());

        service.register(pair.base(), pair.target(), rate, at);

        List<ExchangeRate> history = service.history(pair);
        assertEquals(1, history.size());
        ExchangeRate stored = history.get(0);
        assertEquals(pair, stored.pair());
        assertEquals(at, stored.effectiveAt());
        assertEquals(0, expected.compareTo(stored.rate()));
        assertTrue(stored.rate().scale() <= ExchangeRateService.RATE_SCALE);
        assertEquals(expected, stored.rate().setScale(ExchangeRateService.RATE_SCALE, RoundingMode.UNNECESSARY));
        assertEquals(inverseBefore, service.history(pair.inverse()));
        assertTrue(service.history(pair.inverse()).isEmpty());
    }

    @Provide
    Arbitrary<CurrencyPair> pairs() {
        Arbitrary<String> code = Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
        return Combinators.combine(code, code).as((b, t) -> new String[] {b, t})
                .filter(a -> !a[0].equals(a[1]))
                .map(a -> new CurrencyPair(a[0], a[1]));
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        Arbitrary<Long> unscaled = Arbitraries.longs().between(1L, 999_999_999_999_999L);
        Arbitrary<Integer> scale = Arbitraries.integers().between(0, 15);
        return Combinators.combine(unscaled, scale).as((u, s) -> BigDecimal.valueOf(u, s));
    }

    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 4_000_000_000L).map(Instant::ofEpochSecond);
    }
}
