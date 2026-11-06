package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ExchangeRateService} conversion (Requirements 4.1-4.9, 7.1).
 */
class ExchangeRateServiceConvertTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofDays(1));
    private static final Instant T2 = T0.plus(Duration.ofDays(2));
    private static final CurrencyPair USD_EUR = new CurrencyPair("USD", "EUR");
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    private MutableClock clock;
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T2);
        service = new ExchangeRateService(clock);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void directConversionUsesCurrentRate() {
        var stored = service.register("USD", "EUR", bd("0.9"), T0);

        var result = service.convert(bd("100"), "USD", "EUR");

        assertEquals(bd("100"), result.sourceAmount());
        assertEquals(bd("90.00"), result.convertedAmount());
        assertEquals(2, result.convertedAmount().scale());
        assertEquals("USD", result.from());
        assertEquals("EUR", result.to());
        assertEquals(stored.rate(), result.appliedRate());
        assertEquals(stored, result.exchangeRateUsed().orElseThrow());
    }

    @Test
    void asOfConversionUsesRateInEffectAtThatInstant() {
        service.register("USD", "EUR", bd("0.90"), T0);
        service.register("USD", "EUR", bd("1.10"), T1);

        assertEquals(bd("90.00"), service.convert(bd("100"), "USD", "EUR", T0).convertedAmount());
        assertEquals(bd("90.00"),
                service.convert(bd("100"), "USD", "EUR", T1.minusNanos(1)).convertedAmount());
        assertEquals(bd("110.00"), service.convert(bd("100"), "USD", "EUR", T1).convertedAmount());
    }

    @Test
    void asOfBeforeFirstRateIsNotFound() {
        service.register("USD", "EUR", bd("0.90"), T1);

        assertThrows(RateNotFoundException.class,
                () -> service.convert(bd("1"), "USD", "EUR", T0));
    }

    @Test
    void currentConversionIgnoresFutureRates() {
        service.register("USD", "EUR", bd("0.90"), T0);
        service.register("USD", "EUR", bd("2.00"), T2.plusSeconds(1));

        assertEquals(bd("90.00"), service.convert(bd("100"), "USD", "EUR").convertedAmount());
    }

    @Test
    void sameCurrencyIsIdentityWithoutRates() {
        var result = service.convert(bd("12.3"), "USD", "USD");

        assertEquals(bd("12.30"), result.convertedAmount());
        assertEquals(2, result.convertedAmount().scale());
        assertEquals(0, BigDecimal.ONE.compareTo(result.appliedRate()));
        assertTrue(result.exchangeRateUsed().isEmpty());
    }

    @Test
    void sameCurrencyAsOfIsIdentityWithoutRates() {
        var result = service.convert(bd("5"), "JPY", "JPY", T0);

        assertEquals(bd("5.00"), result.convertedAmount());
        assertTrue(result.exchangeRateUsed().isEmpty());
    }

    @Test
    void inverseConversionUsesReciprocal() {
        var stored = service.register("EUR", "USD", bd("4"), T0);

        var result = service.convert(bd("100"), "USD", "EUR");

        assertEquals(bd("25.00"), result.convertedAmount());
        assertEquals(0, bd("0.25").compareTo(result.appliedRate()));
        assertEquals(ExchangeRateService.RATE_SCALE, result.appliedRate().scale());
        assertEquals(stored, result.exchangeRateUsed().orElseThrow());
    }

    @Test
    void inverseReciprocalIsRoundedToRateScale() {
        service.register("EUR", "USD", bd("3"), T0);

        var result = service.convert(bd("1"), "USD", "EUR");

        assertEquals(bd("0.3333333333"), result.appliedRate());
        assertEquals(bd("0.33"), result.convertedAmount());
    }

    @Test
    void inverseConversionHonoursAsOf() {
        service.register("EUR", "USD", bd("4"), T0);
        service.register("EUR", "USD", bd("2"), T1);

        assertEquals(bd("25.00"), service.convert(bd("100"), "USD", "EUR", T0).convertedAmount());
        assertEquals(bd("50.00"), service.convert(bd("100"), "USD", "EUR", T1).convertedAmount());
    }

    @Test
    void directRatePreferredOverInverse() {
        var direct = service.register("USD", "EUR", bd("0.5"), T0);
        service.register("EUR", "USD", bd("4"), T0);

        var result = service.convert(bd("100"), "USD", "EUR");

        assertEquals(bd("50.00"), result.convertedAmount());
        assertEquals(direct, result.exchangeRateUsed().orElseThrow());
    }

    @Test
    void zeroAmountConvertsToZeroWithScaleTwo() {
        service.register("USD", "EUR", bd("0.9"), T0);

        var result = service.convert(BigDecimal.ZERO, "USD", "EUR");

        assertEquals(0, BigDecimal.ZERO.compareTo(result.convertedAmount()));
        assertEquals(2, result.convertedAmount().scale());
        assertEquals(2, service.convert(BigDecimal.ZERO, "USD", "USD").convertedAmount().scale());
    }

    @Test
    void missingRateThrowsWithBothCodes() {
        var ex = assertThrows(RateNotFoundException.class,
                () -> service.convert(bd("1"), "USD", "EUR"));

        assertTrue(ex.getMessage().contains("USD"));
        assertTrue(ex.getMessage().contains("EUR"));
    }

    @Test
    void missingRateAsOfThrowsWithBothCodes() {
        var ex = assertThrows(RateNotFoundException.class,
                () -> service.convert(bd("1"), "GBP", "JPY", T0));

        assertTrue(ex.getMessage().contains("GBP"));
        assertTrue(ex.getMessage().contains("JPY"));
    }

    @Test
    void nullAmountIsRejected() {
        service.register("USD", "EUR", bd("0.9"), T0);

        assertThrows(IllegalArgumentException.class, () -> service.convert(null, "USD", "EUR"));
        assertThrows(IllegalArgumentException.class,
                () -> service.convert(null, "USD", "EUR", T0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-0.0000000001", "-1000000"})
    void negativeAmountIsRejected(String amount) {
        service.register("USD", "EUR", bd("0.9"), T0);

        assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd(amount), "USD", "EUR"));
        assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd(amount), "USD", "EUR", T0));
        assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd(amount), "USD", "USD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"usd", "US", "USDX", "U1D", "", "   ", "US$"})
    void invalidFromCodeIsRejectedAndNamed(String code) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd("1"), code, "EUR"));
        assertTrue(ex.getMessage().contains(code), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd("1"), code, "EUR", T0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eur", "EU", "EURO", "E2R", "", "   ", "EU$"})
    void invalidToCodeIsRejectedAndNamed(String code) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd("1"), "USD", code));
        assertTrue(ex.getMessage().contains(code), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.convert(bd("1"), "USD", code, T0));
    }

    @Test
    void nullCodesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.convert(bd("1"), null, "EUR"));
        assertThrows(IllegalArgumentException.class, () -> service.convert(bd("1"), "USD", null));
    }

    @ParameterizedTest
    @CsvSource({
        "0.125, 0.12",   // midpoint, previous digit even: stays
        "0.135, 0.14",   // midpoint, previous digit odd: rounds up to even
        "0.145, 0.14",
        "0.155, 0.16",
        "0.1249, 0.12",  // below midpoint
        "0.1251, 0.13",  // above midpoint
        "2.675, 2.68",
        "0.005, 0.00",
        "0.015, 0.02"
    })
    void convertedAmountRoundsHalfEven(String amount, String expected) {
        service.register("USD", "EUR", BigDecimal.ONE, T0);

        var result = service.convert(bd(amount), "USD", "EUR");

        assertEquals(bd(expected), result.convertedAmount());
        assertEquals(2, result.convertedAmount().scale());
    }

    @Test
    void sameCurrencyRoundsHalfEven() {
        assertEquals(bd("0.12"), service.convert(bd("0.125"), "USD", "USD").convertedAmount());
        assertEquals(bd("0.14"), service.convert(bd("0.135"), "USD", "USD").convertedAmount());
    }

    @Test
    void stateIsUnchangedAfterFailures() {
        var stored = service.register("USD", "EUR", bd("0.9"), T0);

        assertThrows(IllegalArgumentException.class, () -> service.convert(null, "USD", "EUR"));
        assertThrows(IllegalArgumentException.class, () -> service.convert(bd("-1"), "USD", "EUR"));
        assertThrows(IllegalArgumentException.class, () -> service.convert(bd("1"), "usd", "EUR"));
        assertThrows(RateNotFoundException.class, () -> service.convert(bd("1"), "GBP", "JPY"));

        assertEquals(List.of(stored), service.history(USD_EUR));
        assertTrue(service.history(EUR_USD).isEmpty());
        assertTrue(service.history(new CurrencyPair("GBP", "JPY")).isEmpty());
        assertTrue(service.history(new CurrencyPair("JPY", "GBP")).isEmpty());
        assertSame(stored, service.currentRate("USD", "EUR").orElseThrow());
        assertFalse(service.currentRate("EUR", "USD").isPresent());
    }

    @Test
    void successfulConversionDoesNotChangeState() {
        var stored = service.register("EUR", "USD", bd("4"), T0);

        service.convert(bd("100"), "USD", "EUR");

        assertEquals(List.of(stored), service.history(EUR_USD));
        assertTrue(service.history(USD_EUR).isEmpty());
    }
}
