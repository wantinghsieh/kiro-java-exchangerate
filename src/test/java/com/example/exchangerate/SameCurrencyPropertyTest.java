package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Property test for same-currency conversion being the identity. */
class SameCurrencyPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    /** Non-negative amounts with 0 to 6 fractional digits so rounding midpoints occur. */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigIntegers().between(java.math.BigInteger.ZERO, java.math.BigInteger.valueOf(1_000_000_000_000L))
            .flatMap(unscaled -> Arbitraries.integers().between(0, 6)
                .map(scale -> new BigDecimal(unscaled, scale)));
    }

    // Feature: currency-exchange-rate-service, Property 10: Same-currency conversion is the identity
    // Validates: Requirements 4.3
    @Property(tries = 200)
    void sameCurrencyConversionIsIdentityWithoutRates(
            @ForAll("amounts") BigDecimal amount,
            @ForAll("codes") String code) {
        var service = new ExchangeRateService(new MutableClock(EPOCH));

        var result = service.convert(amount, code, code);

        var expected = amount.setScale(2, RoundingMode.HALF_EVEN);
        assertEquals(expected, result.convertedAmount());
        assertEquals(2, result.convertedAmount().scale());
        assertEquals(0, BigDecimal.ONE.compareTo(result.appliedRate()));
        assertTrue(result.exchangeRateUsed().isEmpty());
        assertEquals(code, result.from());
        assertEquals(code, result.to());
        assertEquals(amount, result.sourceAmount());
    }

    // Feature: currency-exchange-rate-service, Property 10: Same-currency conversion is the identity
    // Validates: Requirements 4.3
    @Property(tries = 100)
    void sameCurrencyConversionIgnoresRegisteredRatesAndAsOf(
            @ForAll("amounts") BigDecimal amount,
            @ForAll("codes") String code,
            @ForAll("codes") String other) {
        var service = new ExchangeRateService(new MutableClock(EPOCH));
        if (!code.equals(other)) {
            service.register(code, other, new BigDecimal("2.5"), EPOCH);
        }

        var result = service.convert(amount, code, code, EPOCH.plusSeconds(10));

        assertEquals(amount.setScale(2, RoundingMode.HALF_EVEN), result.convertedAmount());
        assertEquals(0, BigDecimal.ONE.compareTo(result.appliedRate()));
        assertTrue(result.exchangeRateUsed().isEmpty());
    }
}
