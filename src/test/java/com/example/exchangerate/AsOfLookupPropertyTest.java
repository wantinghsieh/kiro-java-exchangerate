package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Property test comparing as-of and current rate lookups with a simple reference model. */
class AsOfLookupPropertyTest {

    private static final String BASE = "USD";
    private static final String TARGET = "EUR";
    private static final CurrencyPair PAIR = new CurrencyPair(BASE, TARGET);

    /** One registration: an effective instant and a rate. */
    record Reg(Instant at, BigDecimal rate) { }

    @Provide
    Arbitrary<Instant> instants() {
        // Small range so that collisions (same instant) and boundary hits are frequent.
        return Arbitraries.longs().between(0L, 60L).map(Instant::ofEpochSecond);
    }

    @Provide
    Arbitrary<List<Reg>> registrations() {
        Arbitrary<BigDecimal> rates = Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000L),
                Arbitraries.integers().between(0, 6))
            .as(BigDecimal::valueOf);
        return Combinators.combine(instants(), rates).as(Reg::new).list().ofMaxSize(15);
    }

    /** Reference model: last registration per instant wins, then filter and take the max instant. */
    private static Optional<Reg> model(List<Reg> regs, Instant asOf) {
        Map<Instant, BigDecimal> byInstant = new HashMap<>();
        for (Reg r : regs) {
            byInstant.put(r.at(), r.rate().setScale(ExchangeRateService.RATE_SCALE, RoundingMode.HALF_EVEN));
        }
        Instant best = null;
        for (Instant i : byInstant.keySet()) {
            if (!i.isAfter(asOf) && (best == null || i.isAfter(best))) {
                best = i;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Reg(best, byInstant.get(best)));
    }

    private static void assertMatches(Optional<Reg> expected, Optional<ExchangeRate> actual) {
        assertEquals(expected.isPresent(), actual.isPresent());
        expected.ifPresent(e -> {
            ExchangeRate a = actual.orElseThrow();
            assertEquals(e.at(), a.effectiveAt());
            assertEquals(0, e.rate().compareTo(a.rate()));
            assertEquals(PAIR, a.pair());
        });
    }

    private static ExchangeRateService load(List<Reg> regs, MutableClock clock) {
        var service = new ExchangeRateService(clock);
        for (Reg r : regs) {
            service.register(BASE, TARGET, r.rate(), r.at());
        }
        return service;
    }

    // Feature: currency-exchange-rate-service, Property 5: As-of lookup matches a reference model
    // Validates: Requirements 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3
    @Property(tries = 300)
    void rateAsOfMatchesReferenceModel(
            @ForAll("registrations") List<Reg> regs,
            @ForAll("instants") Instant asOf) {
        var service = load(regs, new MutableClock(Instant.EPOCH));
        assertMatches(model(regs, asOf), service.rateAsOf(PAIR, asOf));
    }

    // Feature: currency-exchange-rate-service, Property 5: As-of lookup matches a reference model
    // Validates: Requirements 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3
    @Property(tries = 300)
    void currentRateMatchesReferenceModelAtClockInstant(
            @ForAll("registrations") List<Reg> regs,
            @ForAll("instants") Instant now) {
        var clock = new MutableClock(now);
        var service = load(regs, clock);
        assertMatches(model(regs, now), service.currentRate(BASE, TARGET));
    }

    // Feature: currency-exchange-rate-service, Property 5: As-of lookup matches a reference model
    // Validates: Requirements 2.2, 3.3
    @Property(tries = 100)
    void currentRateIsEmptyWhenNoHistoryOrAllEntriesInFuture(
            @ForAll("registrations") List<Reg> regs) {
        // No history at all.
        var empty = new ExchangeRateService(new MutableClock(Instant.ofEpochSecond(30)));
        assertTrue(empty.currentRate(BASE, TARGET).isEmpty());
        assertTrue(empty.rateAsOf(PAIR, Instant.ofEpochSecond(30)).isEmpty());

        // Clock strictly before every registration (instants are >= 0, clock at -1s).
        var clock = new MutableClock(Instant.ofEpochSecond(-1));
        var service = load(regs, clock);
        assertTrue(service.currentRate(BASE, TARGET).isEmpty());

        // Advancing the clock makes the model's answer visible.
        clock.set(Instant.ofEpochSecond(60));
        assertMatches(model(regs, Instant.ofEpochSecond(60)), service.currentRate(BASE, TARGET));
    }
}
