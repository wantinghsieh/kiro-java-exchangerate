package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Property test for the half-open range history query. */
class RangeHistoryPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");
    private static final CurrencyPair PAIR = new CurrencyPair("USD", "EUR");

    /** Small second offsets so registered instants and range bounds often coincide. */
    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 40L).map(EPOCH::plusSeconds);
    }

    @Provide
    Arbitrary<List<Instant>> registrationInstants() {
        return instants().list().ofMaxSize(25);
    }

    private static ExchangeRateService serviceWith(List<Instant> instants) {
        var service = new ExchangeRateService(new MutableClock(EPOCH));
        int i = 1;
        for (Instant at : instants) {
            service.register("USD", "EUR", BigDecimal.valueOf(i++, 2), at);
        }
        return service;
    }

    private static void assertUnmodifiable(List<ExchangeRate> list) {
        assertThrows(UnsupportedOperationException.class, () -> list.add(null));
        assertThrows(UnsupportedOperationException.class, list::clear);
    }

    // Feature: currency-exchange-rate-service, Property 7: Range history is the half-open filter
    // Validates: Requirements 3.5, 3.7
    @Property(tries = 200)
    void rangeEqualsFullHistoryFilteredHalfOpen(
            @ForAll("registrationInstants") List<Instant> registered,
            @ForAll("instants") Instant a,
            @ForAll("instants") Instant b) {
        Instant start = a.isAfter(b) ? b : a;
        Instant end = a.isAfter(b) ? a : b;
        var service = serviceWith(registered);

        List<ExchangeRate> expected = service.history(PAIR).stream()
            .filter(r -> !r.effectiveAt().isBefore(start) && r.effectiveAt().isBefore(end))
            .toList();
        List<ExchangeRate> actual = service.history(PAIR, start, end);

        assertEquals(expected, actual);
        for (int i = 1; i < actual.size(); i++) {
            assertTrue(actual.get(i - 1).effectiveAt().isBefore(actual.get(i).effectiveAt()));
        }
        assertUnmodifiable(actual);
        if (start.equals(end)) {
            assertTrue(actual.isEmpty());
        }
    }

    // Feature: currency-exchange-rate-service, Property 7: Range history is the half-open filter
    // Validates: Requirements 3.7 (empty when start equals end)
    @Property(tries = 100)
    void emptyWhenStartEqualsEnd(
            @ForAll("registrationInstants") List<Instant> registered,
            @ForAll("instants") Instant at) {
        var service = serviceWith(registered);
        List<ExchangeRate> result = service.history(PAIR, at, at);
        assertTrue(result.isEmpty());
        assertUnmodifiable(result);
    }

    // Feature: currency-exchange-rate-service, Property 7: Range history is the half-open filter
    // Validates: Requirements 3.8
    @Property(tries = 100)
    void startAfterEndIsRejected(
            @ForAll("registrationInstants") List<Instant> registered,
            @ForAll("instants") Instant a,
            @ForAll("instants") Instant b) {
        if (a.equals(b)) {
            return;
        }
        Instant start = a.isAfter(b) ? a : b;
        Instant end = a.isAfter(b) ? b : a;
        var service = serviceWith(registered);
        assertThrows(IllegalArgumentException.class, () -> service.history(PAIR, start, end));
    }
}
