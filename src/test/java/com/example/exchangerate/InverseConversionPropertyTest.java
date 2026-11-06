package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Property test for inverse-rate conversion and direct-rate preference. */
class InverseConversionPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.longs().between(1L, 1_000_000_000_000_000L)
            .map(unscaled -> BigDecimal.valueOf(unscaled, ExchangeRateService.RATE_SCALE));
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(0L, 1_000_000_000_000L)
            .flatMap(unscaled -> Arbitraries.integers().between(0, 4)
                .map(scale -> BigDecimal.valueOf(unscaled, scale)));
    }

    // Feature: currency-exchange-rate-service, Property 11: Inverse conversion uses the reciprocal
    // Validates: Requirements 4.4, 4.5
    @Property(tries = 200)
    void onlyInverseRateUsesReciprocal(
            @ForAll("codes") String from,
            @ForAll("codes") String to,
            @ForAll("rates") BigDecimal inverseRate,
            @ForAll("amounts") BigDecimal amount) {
        Assume.that(!from.equals(to));

        var service = new ExchangeRateService(new MutableClock(EPOCH));
        service.register(to, from, inverseRate, EPOCH);

        var result = service.convert(amount, from, to, EPOCH);

        BigDecimal reciprocal = BigDecimal.ONE.divide(inverseRate, 10, RoundingMode.HALF_EVEN);
        BigDecimal expected = amount.multiply(reciprocal).setScale(2, RoundingMode.HALF_EVEN);
        assertEquals(expected, result.convertedAmount());
    }

    // Feature: currency-exchange-rate-service, Property 11: Inverse conversion uses the reciprocal
    // Validates: Requirements 4.4, 4.5
    @Property(tries = 200)
    void directRateIsPreferredOverInverse(
            @ForAll("codes") String from,
            @ForAll("codes") String to,
            @ForAll("rates") BigDecimal directRate,
            @ForAll("rates") BigDecimal inverseRate,
            @ForAll("amounts") BigDecimal amount) {
        Assume.that(!from.equals(to));

        var service = new ExchangeRateService(new MutableClock(EPOCH));
        service.register(from, to, directRate, EPOCH);
        service.register(to, from, inverseRate, EPOCH);

        var result = service.convert(amount, from, to, EPOCH);

        BigDecimal expected = amount.multiply(directRate).setScale(2, RoundingMode.HALF_EVEN);
        assertEquals(expected, result.convertedAmount());
    }
}
