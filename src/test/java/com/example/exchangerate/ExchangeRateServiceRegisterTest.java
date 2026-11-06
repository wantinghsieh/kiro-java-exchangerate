package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link ExchangeRateService} registration (Requirements 1.1-1.10, 6.2). */
class ExchangeRateServiceRegisterTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2024-01-02T00:00:00Z");
    private static final CurrencyPair USD_EUR = new CurrencyPair("USD", "EUR");
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    private MutableClock clock;
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        service = new ExchangeRateService(clock);
    }

    private void assertNothingStored() {
        assertTrue(service.history(USD_EUR).isEmpty());
        assertTrue(service.history(EUR_USD).isEmpty());
    }

    @Test
    void registersNominalRate() {
        var stored = service.register("USD", "EUR", new BigDecimal("0.92"), T1);

        assertEquals(USD_EUR, stored.pair());
        assertEquals(0, new BigDecimal("0.92").compareTo(stored.rate()));
        assertEquals(T1, stored.effectiveAt());
        assertEquals(List.of(stored), service.history(USD_EUR));
    }

    @Test
    void usesClockInstantWhenNoneGiven() {
        clock.set(T1);
        var stored = service.register("USD", "EUR", new BigDecimal("0.92"));

        assertEquals(T1, stored.effectiveAt());
        assertEquals(List.of(stored), service.history(USD_EUR));
    }

    @Test
    void replacesRateAtSameInstant() {
        service.register("USD", "EUR", new BigDecimal("0.90"), T1);
        var second = service.register("USD", "EUR", new BigDecimal("0.95"), T1);

        assertEquals(List.of(second), service.history(USD_EUR));
    }

    @Test
    void keepsDistinctInstantsOrdered() {
        var later = service.register("USD", "EUR", new BigDecimal("0.95"), T1);
        var earlier = service.register("USD", "EUR", new BigDecimal("0.90"), T0);

        assertEquals(List.of(earlier, later), service.history(USD_EUR));
    }

    @Test
    void roundsToScaleTenHalfEven() {
        var stored = service.register("USD", "EUR", new BigDecimal("1.123456789012345"), T0);

        assertEquals(new BigDecimal("1.1234567890"), stored.rate());
        assertEquals(stored, service.history(USD_EUR).get(0));
    }

    @ParameterizedTest
    @CsvSource({
        "1.000000000049, 1.0000000000",  // below midpoint
        "1.00000000005,  1.0000000000",  // at midpoint, ties to even (0)
        "1.00000000015,  1.0000000002",  // at midpoint, ties to even (2)
        "1.000000000051, 1.0000000001"   // above midpoint
    })
    void roundsAroundMidpoint(String input, String expected) {
        var stored = service.register("USD", "EUR", new BigDecimal(input), T0);

        assertEquals(new BigDecimal(expected), stored.rate());
        assertEquals(10, stored.rate().scale());
    }

    @Test
    void leavesInversePairUnchanged() {
        service.register("EUR", "USD", new BigDecimal("1.10"), T0);
        var before = service.history(EUR_USD);

        service.register("USD", "EUR", new BigDecimal("0.92"), T0);

        assertEquals(before, service.history(EUR_USD));
        assertEquals(1, service.history(USD_EUR).size());
    }

    @Test
    void rejectsNullInstant() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "EUR", BigDecimal.ONE, null));

        assertTrue(ex.getMessage().contains("effective Instant must not be null"));
        assertNothingStored();
    }

    @Test
    void rejectsNullClock() {
        var ex = assertThrows(NullPointerException.class, () -> new ExchangeRateService(null));

        assertTrue(ex.getMessage().contains("clock"));
    }

    @Test
    void rejectsNullRate() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "EUR", null, T0));

        assertTrue(ex.getMessage().contains("positive"));
        assertNothingStored();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0000000000", "-1", "-0.5", "-0.00000000000000000001"})
    void rejectsNonPositiveRate(String rate) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "EUR", new BigDecimal(rate), T0));

        assertTrue(ex.getMessage().contains("positive"));
        assertNothingStored();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00000000004", "0.00000000005", "0.000000000049"})
    void rejectsRateThatRoundsToZero(String rate) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "EUR", new BigDecimal(rate), T0));

        assertTrue(ex.getMessage().contains("rounds to zero"));
        assertNothingStored();
    }

    @Test
    void rejectsSameBaseAndTarget() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "USD", BigDecimal.ONE, T0));

        assertTrue(ex.getMessage().contains("USD"));
        assertNothingStored();
    }

    @Test
    void rejectionDoesNotAlterExistingHistory() {
        var existing = service.register("USD", "EUR", new BigDecimal("0.92"), T0);

        assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "EUR", BigDecimal.ZERO, T0));
        assertThrows(IllegalArgumentException.class,
                () -> service.register("USD", "EUR", BigDecimal.ONE, null));

        assertEquals(List.of(existing), service.history(USD_EUR));
        assertTrue(service.history(EUR_USD).isEmpty());
    }
}
