package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property tests for conversion with no applicable rate (Requirement 4.7) and for rejection of
 * invalid amounts and codes in {@code convert} (Requirements 4.8 and 4.9).
 */
class MissingRatePropertyTest {

    private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant PAST = Instant.parse("2023-06-01T00:00:00Z");

    @Provide
    Arbitrary<String> validCodes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    /** Two distinct valid codes. */
    @Provide
    Arbitrary<List<String>> distinctCodePairs() {
        return Combinators.combine(validCodes(), validCodes())
            .as((a, b) -> List.of(a, b))
            .filter(l -> !l.get(0).equals(l.get(1)));
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Combinators.combine(
                Arbitraries.longs().between(0L, 1_000_000_000L),
                Arbitraries.integers().between(0, 6))
            .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    @Provide
    Arbitrary<BigDecimal> positiveRates() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000_000L),
                Arbitraries.integers().between(0, 8))
            .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    @Provide
    Arbitrary<BigDecimal> negativeAmounts() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000L),
                Arbitraries.integers().between(0, 6))
            .as((unscaled, scale) -> BigDecimal.valueOf(-unscaled, scale));
    }

    @Provide
    Arbitrary<String> invalidCodes() {
        Arbitrary<String> random = Arbitraries.strings().ofMaxLength(6).filter(s -> !isValid(s));
        Arbitrary<String> lower = Arbitraries.strings().withCharRange('a', 'z').ofLength(3);
        Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofLength(3);
        Arbitrary<String> wrongLength = Arbitraries.strings().withCharRange('A', 'Z')
            .ofMinLength(0).ofMaxLength(8).filter(s -> s.length() != 3);
        Arbitrary<String> fixed = Arbitraries.of("", " ", "   ", "US", "USDX", "usd", "U1D", "US ");
        return Arbitraries.oneOf(random, lower, digits, wrongLength, fixed).injectNull(0.1);
    }

    // Feature: currency-exchange-rate-service, Property 12: Missing rate raises RateNotFoundException
    // Validates: Requirements 4.7
    @Property(tries = 200)
    void convertWithoutApplicableRateThrowsRateNotFound(
            @ForAll("distinctCodePairs") List<String> codes,
            @ForAll("distinctCodePairs") List<String> otherCodes,
            @ForAll("amounts") BigDecimal amount,
            @ForAll("positiveRates") BigDecimal rate,
            @ForAll boolean futureDirect,
            @ForAll boolean futureInverse,
            @ForAll boolean otherPairs,
            @ForAll boolean useAsOf) {
        String from = codes.get(0);
        String to = codes.get(1);
        var target = new CurrencyPair(from, to);
        var other = new CurrencyPair(otherCodes.get(0), otherCodes.get(1));
        boolean otherCollides = other.equals(target) || other.equals(target.inverse());

        var service = new ExchangeRateService(new MutableClock(NOW));
        // Rates registered only after the lookup instant are not in effect.
        Instant lookup = useAsOf ? PAST : NOW;
        Instant future = lookup.plusSeconds(1);
        if (futureDirect) {
            service.register(from, to, rate, future);
        }
        if (futureInverse) {
            service.register(to, from, rate, future.plusSeconds(60));
        }
        if (otherPairs && !otherCollides) {
            service.register(other.base(), other.target(), rate, PAST.minusSeconds(10));
            service.register(other.target(), other.base(), rate, PAST.minusSeconds(10));
        }
        // For the as-of variant, anything at or before PAST would be in effect, so only future data is used above.
        var watched = List.of(target, target.inverse(), other, other.inverse());
        var before = snapshot(service, watched);

        var ex = assertThrows(RateNotFoundException.class, () -> {
            if (useAsOf) {
                service.convert(amount, from, to, lookup);
            } else {
                service.convert(amount, from, to);
            }
        });
        assertTrue(ex.getMessage().contains(from), ex.getMessage());
        assertTrue(ex.getMessage().contains(to), ex.getMessage());
        assertEquals(before, snapshot(service, watched));
    }

    // Feature: currency-exchange-rate-service, Property 12: Missing rate raises RateNotFoundException
    // Validates: Requirements 4.7 (empty service)
    @Property(tries = 100)
    void convertOnEmptyServiceThrowsRateNotFound(
            @ForAll("distinctCodePairs") List<String> codes,
            @ForAll("amounts") BigDecimal amount) {
        var service = new ExchangeRateService(new MutableClock(NOW));
        var ex = assertThrows(RateNotFoundException.class,
            () -> service.convert(amount, codes.get(0), codes.get(1)));
        assertTrue(ex.getMessage().contains(codes.get(0)), ex.getMessage());
        assertTrue(ex.getMessage().contains(codes.get(1)), ex.getMessage());
    }

    // Feature: currency-exchange-rate-service, Property 12: Missing rate raises RateNotFoundException
    // Validates: Requirements 4.8
    @Property(tries = 200)
    void convertRejectsNullOrNegativeAmount(
            @ForAll("negativeAmounts") BigDecimal negative,
            @ForAll boolean useNull,
            @ForAll boolean useAsOf) {
        var service = seededService();
        var watched = List.of(new CurrencyPair("USD", "EUR"), new CurrencyPair("EUR", "USD"));
        var before = snapshot(service, watched);
        BigDecimal amount = useNull ? null : negative;

        assertThrows(IllegalArgumentException.class, () -> {
            if (useAsOf) {
                service.convert(amount, "USD", "EUR", NOW);
            } else {
                service.convert(amount, "USD", "EUR");
            }
        });
        assertEquals(before, snapshot(service, watched));
    }

    // Feature: currency-exchange-rate-service, Property 12: Missing rate raises RateNotFoundException
    // Validates: Requirements 4.9
    @Property(tries = 200)
    void convertRejectsInvalidCodes(
            @ForAll("invalidCodes") String badCode,
            @ForAll("validCodes") String goodCode,
            @ForAll("amounts") BigDecimal amount,
            @ForAll boolean badIsFrom,
            @ForAll boolean useAsOf) {
        var service = seededService();
        var watched = List.of(new CurrencyPair("USD", "EUR"), new CurrencyPair("EUR", "USD"));
        var before = snapshot(service, watched);
        String from = badIsFrom ? badCode : goodCode;
        String to = badIsFrom ? goodCode : badCode;

        var ex = assertThrows(IllegalArgumentException.class, () -> {
            if (useAsOf) {
                service.convert(amount, from, to, NOW);
            } else {
                service.convert(amount, from, to);
            }
        });
        assertTrue(ex.getMessage().contains(String.valueOf(badCode)), ex.getMessage());
        assertEquals(before, snapshot(service, watched));
    }

    private static boolean isValid(String s) {
        return s != null && s.length() == 3 && s.chars().allMatch(c -> c >= 'A' && c <= 'Z');
    }

    private static ExchangeRateService seededService() {
        var service = new ExchangeRateService(new MutableClock(NOW));
        service.register("USD", "EUR", new BigDecimal("0.9100000000"), PAST);
        service.register("EUR", "USD", new BigDecimal("1.0800000000"), PAST);
        return service;
    }

    private static Map<CurrencyPair, List<ExchangeRate>> snapshot(
            ExchangeRateService service, List<CurrencyPair> pairs) {
        var result = new LinkedHashMap<CurrencyPair, List<ExchangeRate>>();
        for (var pair : new ArrayList<>(pairs)) {
            result.put(pair, service.history(pair));
        }
        return result;
    }
}
