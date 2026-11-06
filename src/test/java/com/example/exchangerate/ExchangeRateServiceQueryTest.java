package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ExchangeRateService} queries: current rate, as-of lookup and history
 * (Requirements 2.1-2.6, 3.1-3.10, 7.1).
 */
class ExchangeRateServiceQueryTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofDays(1));
    private static final Instant T2 = T0.plus(Duration.ofDays(2));
    private static final Instant T3 = T0.plus(Duration.ofDays(3));
    private static final CurrencyPair USD_EUR = new CurrencyPair("USD", "EUR");
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    private MutableClock clock;
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T2);
        service = new ExchangeRateService(clock);
    }

    private ExchangeRate reg(String rate, Instant at) {
        return service.register("USD", "EUR", new BigDecimal(rate), at);
    }

    // ---------- Requirement 2: current rate ----------

    @Test
    void currentRateReturnsLatestAtOrBeforeNow() {
        reg("0.90", T0);
        var expected = reg("0.91", T1);
        reg("0.95", T3); // future

        assertEquals(Optional_of(expected), service.currentRate("USD", "EUR"));
    }

    @Test
    void currentRateIncludesEntryEffectiveExactlyNow() {
        var exact = reg("0.93", T2);

        assertEquals(Optional_of(exact), service.currentRate("USD", "EUR"));
    }

    @Test
    void currentRateEmptyWhenNoHistory() {
        assertTrue(service.currentRate("USD", "EUR").isEmpty());
        assertTrue(service.history(USD_EUR).isEmpty());
    }

    @Test
    void currentRateEmptyWhenOnlyFutureRates() {
        reg("0.90", T3);

        assertTrue(service.currentRate("USD", "EUR").isEmpty());
        assertEquals(1, service.history(USD_EUR).size());
    }

    @Test
    void currentRateDoesNotUseInversePair() {
        service.register("EUR", "USD", new BigDecimal("1.10"), T0);

        assertTrue(service.currentRate("USD", "EUR").isEmpty());
    }

    @Test
    void newerRegisteredRateBecomesCurrent() {
        var older = reg("0.90", T0);
        assertEquals(Optional_of(older), service.currentRate("USD", "EUR"));

        var newer = reg("0.92", T1);
        assertEquals(Optional_of(newer), service.currentRate("USD", "EUR"));
    }

    @Test
    void currentRateFollowsClock() {
        reg("0.90", T0);
        var later = reg("0.95", T3);
        assertEquals(T0, service.currentRate("USD", "EUR").orElseThrow().effectiveAt());

        clock.set(T3);
        assertEquals(Optional_of(later), service.currentRate("USD", "EUR"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "US", "USDX", "usd", "U5D", "€UR"})
    void currentRateRejectsInvalidBaseCode(String code) {
        var ex = assertThrows(IllegalArgumentException.class, () -> service.currentRate(code, "EUR"));
        assertTrue(ex.getMessage().contains(code == null ? "null" : code), ex.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "EU", "EURO", "eur", "E-R", "12E"})
    void currentRateRejectsInvalidTargetCode(String code) {
        var ex = assertThrows(IllegalArgumentException.class, () -> service.currentRate("USD", code));
        assertTrue(ex.getMessage().contains(code == null ? "null" : code), ex.getMessage());
    }

    @Test
    void currentRateRejectsDuplicatedCodes() {
        var ex = assertThrows(IllegalArgumentException.class, () -> service.currentRate("USD", "USD"));
        assertTrue(ex.getMessage().contains("USD"), ex.getMessage());
    }

    @Test
    void rejectedCurrentRateQueryLeavesHistoryUnchanged() {
        var stored = reg("0.90", T0);

        assertThrows(IllegalArgumentException.class, () -> service.currentRate("bad", "EUR"));
        assertThrows(IllegalArgumentException.class, () -> service.currentRate("USD", "USD"));

        assertEquals(List.of(stored), service.history(USD_EUR));
    }

    // ---------- Requirement 3: as-of ----------

    @Test
    void rateAsOfReturnsGreatestAtOrBefore() {
        reg("0.90", T0);
        var middle = reg("0.92", T1);
        reg("0.95", T3);

        assertEquals(Optional_of(middle), service.rateAsOf(USD_EUR, T2));
    }

    @Test
    void rateAsOfIsInclusiveAtExactEffectiveInstant() {
        reg("0.90", T0);
        var exact = reg("0.92", T1);

        assertEquals(Optional_of(exact), service.rateAsOf(USD_EUR, T1));
    }

    @Test
    void rateAsOfJustBeforeEntryReturnsPreviousEntry() {
        var first = reg("0.90", T0);
        reg("0.92", T1);

        assertEquals(Optional_of(first), service.rateAsOf(USD_EUR, T1.minusNanos(1)));
    }

    @Test
    void rateAsOfBeforeEarliestIsEmpty() {
        reg("0.90", T1);

        assertTrue(service.rateAsOf(USD_EUR, T1.minusNanos(1)).isEmpty());
        assertTrue(service.rateAsOf(USD_EUR, Instant.MIN).isEmpty());
    }

    @Test
    void rateAsOfAfterLatestReturnsLatest() {
        reg("0.90", T0);
        var last = reg("0.92", T1);

        assertEquals(Optional_of(last), service.rateAsOf(USD_EUR, Instant.MAX));
    }

    @Test
    void rateAsOfEmptyWithoutHistory() {
        assertTrue(service.rateAsOf(USD_EUR, T3).isEmpty());
        assertTrue(service.rateAsOf(EUR_USD, T3).isEmpty());
    }

    // ---------- Requirement 3: history ----------

    @Test
    void historyIsOrderedAscendingRegardlessOfRegistrationOrder() {
        var c = reg("0.95", T3);
        var a = reg("0.90", T0);
        var b = reg("0.92", T1);

        assertEquals(List.of(a, b, c), service.history(USD_EUR));
    }

    @Test
    void historyHasOneEntryPerInstant() {
        reg("0.90", T1);
        var replacement = reg("0.99", T1);

        assertEquals(List.of(replacement), service.history(USD_EUR));
    }

    @Test
    void historyEmptyForUnknownPair() {
        var history = service.history(USD_EUR);

        assertTrue(history.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> history.add(dummy()));
    }

    @Test
    void historyIsUnmodifiable() {
        var stored = reg("0.90", T0);
        var history = service.history(USD_EUR);

        assertThrows(UnsupportedOperationException.class, () -> history.add(dummy()));
        assertThrows(UnsupportedOperationException.class, () -> history.remove(0));
        assertThrows(UnsupportedOperationException.class, history::clear);
        assertEquals(List.of(stored), service.history(USD_EUR));
    }

    @Test
    void historyIsPerDirectionalPair() {
        var usdEur = reg("0.90", T0);
        var eurUsd = service.register("EUR", "USD", new BigDecimal("1.10"), T0);

        assertEquals(List.of(usdEur), service.history(USD_EUR));
        assertEquals(List.of(eurUsd), service.history(EUR_USD));
    }

    // ---------- Requirement 3: range history ----------

    @Test
    void rangeIsHalfOpen() {
        reg("0.90", T0);
        var b = reg("0.92", T1);
        var c = reg("0.93", T2);
        reg("0.95", T3);

        assertEquals(List.of(b, c), service.history(USD_EUR, T1, T3));
    }

    @Test
    void rangeIncludesStartAndExcludesEnd() {
        var a = reg("0.90", T0);
        reg("0.92", T1);

        assertEquals(List.of(a), service.history(USD_EUR, T0, T1));
    }

    @Test
    void rangeCoveringEverythingReturnsAllInOrder() {
        reg("0.95", T3);
        var a = reg("0.90", T0);
        var b = reg("0.92", T1);
        var c = service.history(USD_EUR).get(2);

        assertEquals(List.of(a, b, c), service.history(USD_EUR, Instant.MIN, Instant.MAX));
    }

    @Test
    void rangeWithNoMatchesIsEmptyAndUnmodifiable() {
        reg("0.90", T0);

        var result = service.history(USD_EUR, T1, T3);
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.add(dummy()));
    }

    @Test
    void rangeResultIsUnmodifiable() {
        reg("0.90", T0);
        var result = service.history(USD_EUR, T0, T1);

        assertEquals(1, result.size());
        assertThrows(UnsupportedOperationException.class, () -> result.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> result.add(dummy()));
    }

    @Test
    void rangeForUnknownPairIsEmpty() {
        assertTrue(service.history(USD_EUR, T0, T3).isEmpty());
    }

    @Test
    void rangeStartAfterEndThrowsAndLeavesHistoryUnchanged() {
        var stored = reg("0.90", T1);

        assertThrows(IllegalArgumentException.class, () -> service.history(USD_EUR, T2, T1));
        assertThrows(IllegalArgumentException.class,
                () -> service.history(USD_EUR, T1.plusNanos(1), T1));
        assertEquals(List.of(stored), service.history(USD_EUR));
    }

    @Test
    void rangeStartEqualsEndIsEmptyEvenWithEntryAtThatInstant() {
        reg("0.90", T1);

        var result = service.history(USD_EUR, T1, T1);
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.add(dummy()));
    }

    // ---------- Requirement 3.9: null arguments ----------

    @Test
    void nullArgumentsThrowNullPointerException() {
        reg("0.90", T0);

        assertThrows(NullPointerException.class, () -> service.rateAsOf(null, T1));
        assertThrows(NullPointerException.class, () -> service.rateAsOf(USD_EUR, null));
        assertThrows(NullPointerException.class, () -> service.history(null));
        assertThrows(NullPointerException.class, () -> service.history(null, T0, T1));
        assertThrows(NullPointerException.class, () -> service.history(USD_EUR, null, T1));
        assertThrows(NullPointerException.class, () -> service.history(USD_EUR, T0, null));

        assertEquals(1, service.history(USD_EUR).size());
    }

    // ---------- Requirement 3.10: snapshots ----------

    @Test
    void fullHistorySnapshotUnchangedByLaterRegistration() {
        var a = reg("0.90", T0);
        var snapshot = service.history(USD_EUR);

        reg("0.92", T1); // new instant
        reg("0.99", T0); // replacement of existing instant

        assertEquals(List.of(a), snapshot);
        assertEquals(1, snapshot.size());
        assertEquals(2, service.history(USD_EUR).size());
    }

    @Test
    void emptySnapshotUnchangedByLaterRegistration() {
        var snapshot = service.history(USD_EUR);

        reg("0.90", T0);

        assertTrue(snapshot.isEmpty());
        assertFalse(service.history(USD_EUR).isEmpty());
    }

    @Test
    void rangeSnapshotUnchangedByLaterRegistration() {
        var a = reg("0.90", T0);
        var snapshot = service.history(USD_EUR, T0, T3);

        reg("0.92", T1);

        assertEquals(List.of(a), snapshot);
    }

    // ---------- helpers ----------

    private static java.util.Optional<ExchangeRate> Optional_of(ExchangeRate rate) {
        return java.util.Optional.of(rate);
    }

    private static ExchangeRate dummy() {
        return new ExchangeRate(USD_EUR, BigDecimal.ONE, T0);
    }
}
