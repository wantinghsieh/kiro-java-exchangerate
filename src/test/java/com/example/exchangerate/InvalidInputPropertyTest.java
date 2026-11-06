package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property test for rejection of invalid input without side effects.
 *
 * <p>Requirements 4.8 and 4.9 (invalid codes / negative or null amount in convert) are not
 * exercised here because {@code convert} does not exist yet. Once task 6.1 adds it, the
 * convert-specific cases of this property are covered by the tests of task 6.2 and later.
 */
class InvalidInputPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant SEEDED_AT = Instant.parse("2023-06-01T00:00:00Z");

    private static final List<CurrencyPair> SEEDED_PAIRS = List.of(
        new CurrencyPair("USD", "EUR"),
        new CurrencyPair("EUR", "USD"),
        new CurrencyPair("GBP", "JPY"));

    @Provide
    Arbitrary<String> validCodes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    @Provide
    Arbitrary<String> invalidCodes() {
        Arbitrary<String> random = Arbitraries.strings().ofMaxLength(6)
            .filter(s -> !isValid(s));
        Arbitrary<String> lower = Arbitraries.strings().withCharRange('a', 'z').ofLength(3);
        Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofLength(3);
        Arbitrary<String> wrongLength = Arbitraries.strings().withCharRange('A', 'Z')
            .ofMinLength(0).ofMaxLength(8).filter(s -> s.length() != 3);
        Arbitrary<String> fixed = Arbitraries.of("", " ", "   ", "US", "USDX", "usd", "U1D", "US ");
        return Arbitraries.oneOf(random, lower, digits, wrongLength, fixed).injectNull(0.1);
    }

    @Provide
    Arbitrary<BigDecimal> positiveRates() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000_000L),
                Arbitraries.integers().between(0, 8))
            .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    @Provide
    Arbitrary<BigDecimal> nonPositiveRates() {
        Arbitrary<BigDecimal> zeros = Arbitraries.integers().between(0, 15)
            .map(scale -> BigDecimal.ZERO.setScale(scale));
        Arbitrary<BigDecimal> negatives = Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000_000L),
                Arbitraries.integers().between(0, 15))
            .as((unscaled, scale) -> BigDecimal.valueOf(-unscaled, scale));
        return Arbitraries.oneOf(zeros, negatives).injectNull(0.2);
    }

    /** Positive rates that are at most 5e-11, so HALF_EVEN at scale 10 gives zero. */
    @Provide
    Arbitrary<BigDecimal> roundsToZeroRates() {
        return Arbitraries.integers().between(11, 18).flatMap(scale -> {
            BigInteger max = BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(scale - 11));
            return Arbitraries.bigIntegers().between(BigInteger.ONE, max)
                .map(unscaled -> new BigDecimal(unscaled, scale));
        });
    }

    // Feature: currency-exchange-rate-service, Property 4: Invalid input is rejected without side effects
    // Validates: Requirements 1.5
    @Property(tries = 200)
    void registerRejectsInvalidCodes(
            @ForAll("invalidCodes") String badCode,
            @ForAll("validCodes") String goodCode,
            @ForAll("positiveRates") BigDecimal rate,
            @ForAll boolean badIsBase,
            @ForAll boolean withInstant) {
        var service = seededService();
        var before = snapshot(service, SEEDED_PAIRS);
        String base = badIsBase ? badCode : goodCode;
        String target = badIsBase ? goodCode : badCode;

        var ex = assertThrows(IllegalArgumentException.class, () -> {
            if (withInstant) {
                service.register(base, target, rate, EPOCH);
            } else {
                service.register(base, target, rate);
            }
        });
        assertTrue(ex.getMessage().contains(String.valueOf(badCode)), ex.getMessage());
        assertEquals(before, snapshot(service, SEEDED_PAIRS));
    }

    // Feature: currency-exchange-rate-service, Property 4: Invalid input is rejected without side effects
    // Validates: Requirements 1.6
    @Property(tries = 200)
    void registerRejectsEqualCodes(
            @ForAll("validCodes") String code,
            @ForAll("positiveRates") BigDecimal rate) {
        var service = seededService();
        var before = snapshot(service, SEEDED_PAIRS);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.register(code, code, rate, EPOCH));
        assertTrue(ex.getMessage().contains(code), ex.getMessage());
        assertEquals(before, snapshot(service, SEEDED_PAIRS));
    }

    // Feature: currency-exchange-rate-service, Property 4: Invalid input is rejected without side effects
    // Validates: Requirements 1.7
    @Property(tries = 200)
    void registerRejectsNullZeroOrNegativeRate(
            @ForAll("nonPositiveRates") BigDecimal rate,
            @ForAll boolean useSeededPair) {
        var service = seededService();
        var pair = useSeededPair ? SEEDED_PAIRS.get(0) : new CurrencyPair("CHF", "CAD");
        var pairs = pairsToWatch(pair);
        var before = snapshot(service, pairs);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.register(pair.base(), pair.target(), rate, EPOCH));
        assertTrue(ex.getMessage().contains("rate"), ex.getMessage());
        assertEquals(before, snapshot(service, pairs));
    }

    // Feature: currency-exchange-rate-service, Property 4: Invalid input is rejected without side effects
    // Validates: Requirements 1.8
    @Property(tries = 200)
    void registerRejectsRateRoundingToZero(
            @ForAll("roundsToZeroRates") BigDecimal rate,
            @ForAll boolean useSeededPair) {
        var service = seededService();
        var pair = useSeededPair ? SEEDED_PAIRS.get(0) : new CurrencyPair("CHF", "CAD");
        var pairs = pairsToWatch(pair);
        var before = snapshot(service, pairs);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.register(pair.base(), pair.target(), rate, EPOCH));
        assertTrue(ex.getMessage().contains("rounds to zero"), ex.getMessage());
        assertEquals(before, snapshot(service, pairs));
    }

    // Feature: currency-exchange-rate-service, Property 4: Invalid input is rejected without side effects
    // Validates: Requirements 2.5
    @Property(tries = 200)
    void currentRateRejectsInvalidCodes(
            @ForAll("invalidCodes") String badCode,
            @ForAll("validCodes") String goodCode,
            @ForAll boolean badIsBase) {
        var service = seededService();
        var before = snapshot(service, SEEDED_PAIRS);
        String base = badIsBase ? badCode : goodCode;
        String target = badIsBase ? goodCode : badCode;

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.currentRate(base, target));
        assertTrue(ex.getMessage().contains(String.valueOf(badCode)), ex.getMessage());
        assertEquals(before, snapshot(service, SEEDED_PAIRS));
    }

    // Feature: currency-exchange-rate-service, Property 4: Invalid input is rejected without side effects
    // Validates: Requirements 2.6
    @Property(tries = 200)
    void currentRateRejectsEqualCodes(@ForAll("validCodes") String code) {
        var service = seededService();
        var before = snapshot(service, SEEDED_PAIRS);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.currentRate(code, code));
        assertTrue(ex.getMessage().contains(code), ex.getMessage());
        assertEquals(before, snapshot(service, SEEDED_PAIRS));
    }

    private static boolean isValid(String s) {
        return s != null && s.length() == 3
            && s.chars().allMatch(c -> c >= 'A' && c <= 'Z');
    }

    private static ExchangeRateService seededService() {
        var service = new ExchangeRateService(new MutableClock(EPOCH));
        service.register("USD", "EUR", new BigDecimal("0.9100000000"), SEEDED_AT);
        service.register("USD", "EUR", new BigDecimal("0.9200000000"), SEEDED_AT.plusSeconds(60));
        service.register("EUR", "USD", new BigDecimal("1.0800000000"), SEEDED_AT);
        service.register("GBP", "JPY", new BigDecimal("190.5000000000"), SEEDED_AT);
        return service;
    }

    private static List<CurrencyPair> pairsToWatch(CurrencyPair pair) {
        return List.of(SEEDED_PAIRS.get(0), SEEDED_PAIRS.get(1), SEEDED_PAIRS.get(2),
            pair, pair.inverse());
    }

    private static Map<CurrencyPair, List<ExchangeRate>> snapshot(
            ExchangeRateService service, List<CurrencyPair> pairs) {
        var result = new LinkedHashMap<CurrencyPair, List<ExchangeRate>>();
        for (var pair : pairs) {
            result.put(pair, service.history(pair));
        }
        return result;
    }
}
