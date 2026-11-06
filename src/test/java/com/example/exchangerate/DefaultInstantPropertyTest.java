package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property tests for the default effective instant of a registration. */
class DefaultInstantPropertyTest {

    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 4_000_000_000L).map(Instant::ofEpochSecond);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.001"), new BigDecimal("100000"))
                .ofScale(6);
    }

    @Provide
    Arbitrary<String[]> distinctCodes() {
        return Combinators.combine(codes(), codes()).as((a, b) -> new String[] {a, b})
                .filter(p -> !p[0].equals(p[1]));
    }

    // Feature: currency-exchange-rate-service, Property 2: Default effective instant comes from the Clock
    // Validates: Requirements 1.2
    @Property(tries = 200)
    void defaultEffectiveInstantComesFromClock(
            @ForAll("distinctCodes") String[] codePair,
            @ForAll("rates") BigDecimal rate,
            @ForAll("instants") Instant now) {
        var service = new ExchangeRateService(new MutableClock(now));

        ExchangeRate returned = service.register(codePair[0], codePair[1], rate);

        assertEquals(now, returned.effectiveAt());
        List<ExchangeRate> history = service.history(new CurrencyPair(codePair[0], codePair[1]));
        assertEquals(1, history.size());
        assertEquals(now, history.get(0).effectiveAt());
    }
}
