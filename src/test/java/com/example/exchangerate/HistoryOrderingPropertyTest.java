package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Property test for ordering, uniqueness and unmodifiability of rate history. */
class HistoryOrderingPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    /** Small range so duplicate instants are frequent. */
    @Provide
    Arbitrary<List<Instant>> instantSequences() {
        return Arbitraries.longs().between(0L, 20L)
            .map(s -> EPOCH.plusSeconds(s))
            .list().ofMinSize(1).ofMaxSize(40);
    }

    // Feature: currency-exchange-rate-service, Property 6: History is ordered, unique, and unmodifiable
    // Validates: Requirements 3.4, 3.6
    @Property(tries = 200)
    void historyIsStrictlyAscendingUniqueAndUnmodifiable(
            @ForAll("codes") String base,
            @ForAll("codes") String target,
            @ForAll("instantSequences") List<Instant> instants) {
        Assume.that(!base.equals(target));
        var service = new ExchangeRateService(new MutableClock(EPOCH));
        for (int i = 0; i < instants.size(); i++) {
            service.register(base, target, BigDecimal.valueOf(i + 1), instants.get(i));
        }

        var pair = new CurrencyPair(base, target);
        List<ExchangeRate> history = service.history(pair);
        Set<Instant> distinct = new TreeSet<>(instants);

        assertEquals(distinct.size(), history.size());
        assertEquals(List.copyOf(distinct), history.stream().map(ExchangeRate::effectiveAt).toList());
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i - 1).effectiveAt().isBefore(history.get(i).effectiveAt()));
        }

        ExchangeRate first = history.get(0);
        assertThrows(UnsupportedOperationException.class, () -> history.add(first));
        assertThrows(UnsupportedOperationException.class, () -> history.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> history.set(0, first));
        assertThrows(UnsupportedOperationException.class, history::clear);
    }

    // Feature: currency-exchange-rate-service, Property 6: History is ordered, unique, and unmodifiable
    // Validates: Requirements 3.6
    @Property(tries = 100)
    void emptyHistoryForUnknownPairIsUnmodifiable(
            @ForAll("codes") String base,
            @ForAll("codes") String target) {
        Assume.that(!base.equals(target));
        var service = new ExchangeRateService(new MutableClock(EPOCH));

        List<ExchangeRate> history = service.history(new CurrencyPair(base, target));

        assertTrue(history.isEmpty());
        assertThrows(UnsupportedOperationException.class, history::clear);
        assertThrows(UnsupportedOperationException.class,
            () -> history.add(new ExchangeRate(new CurrencyPair(base, target), BigDecimal.ONE, EPOCH)));
    }
}
