package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for CurrencyPair (and CurrencyCodes through it), ExchangeRate, RateNotFoundException. */
class DomainTypesTest {

    static Stream<String> invalidCodes() {
        return Stream.of(null, "", "   ", "US", "USDX", "usd", "Usd", "U1D", "US$", "123");
    }

    @Nested
    class CurrencyPairTest {

        @ParameterizedTest
        @MethodSource("com.example.exchangerate.DomainTypesTest#invalidCodes")
        void rejectsInvalidBase(String code) {
            var ex = assertThrows(IllegalArgumentException.class, () -> new CurrencyPair(code, "EUR"));
            assertTrue(ex.getMessage().contains(String.valueOf(code)));
        }

        @ParameterizedTest
        @MethodSource("com.example.exchangerate.DomainTypesTest#invalidCodes")
        void rejectsInvalidTarget(String code) {
            var ex = assertThrows(IllegalArgumentException.class, () -> new CurrencyPair("USD", code));
            assertTrue(ex.getMessage().contains(String.valueOf(code)));
        }

        @Test
        void rejectsEqualCodes() {
            var ex = assertThrows(IllegalArgumentException.class, () -> new CurrencyPair("USD", "USD"));
            assertTrue(ex.getMessage().contains("USD"));
        }

        @Test
        void inverseSwapsCodes() {
            var pair = new CurrencyPair("USD", "EUR");
            var inv = pair.inverse();
            assertEquals("EUR", inv.base());
            assertEquals("USD", inv.target());
            assertEquals(pair, inv.inverse());
            assertNotEquals(pair, inv);
        }

        @Test
        void equalityAndHashCode() {
            assertEquals(new CurrencyPair("USD", "EUR"), new CurrencyPair("USD", "EUR"));
            assertEquals(new CurrencyPair("USD", "EUR").hashCode(), new CurrencyPair("USD", "EUR").hashCode());
        }
    }

    @Nested
    class ExchangeRateTest {
        private final CurrencyPair pair = new CurrencyPair("USD", "EUR");
        private final Instant at = Instant.parse("2024-01-01T00:00:00Z");

        @Test
        void storesComponentsAndIsImmutable() {
            var rate = new BigDecimal("0.9200000000");
            var er = new ExchangeRate(pair, rate, at);
            assertEquals(pair, er.pair());
            assertEquals(rate, er.rate());
            assertEquals(at, er.effectiveAt());
            assertEquals(er, new ExchangeRate(pair, new BigDecimal("0.9200000000"), at));
        }

        @Test
        void acceptsScaleTenAndLess() {
            new ExchangeRate(pair, new BigDecimal("0.0000000001"), at);
            new ExchangeRate(pair, BigDecimal.ONE, at);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "-0.0000000001", "0.0000000000"})
        void rejectsNonPositiveRate(String value) {
            assertThrows(IllegalArgumentException.class, () -> new ExchangeRate(pair, new BigDecimal(value), at));
        }

        @Test
        void rejectsScaleAboveTen() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExchangeRate(pair, new BigDecimal("0.12345678901"), at));
        }

        @Test
        void rejectsNulls() {
            assertThrows(NullPointerException.class, () -> new ExchangeRate(null, BigDecimal.ONE, at));
            assertThrows(NullPointerException.class, () -> new ExchangeRate(pair, null, at));
            assertThrows(NullPointerException.class, () -> new ExchangeRate(pair, BigDecimal.ONE, null));
        }
    }

    @Nested
    class RateNotFoundExceptionTest {
        @Test
        void messageContainsBothCodesAndIsUnchecked() {
            var ex = new RateNotFoundException("USD", "JPY");
            assertTrue(ex.getMessage().contains("USD"));
            assertTrue(ex.getMessage().contains("JPY"));
            assertTrue(ex instanceof RuntimeException);
        }
    }
}
