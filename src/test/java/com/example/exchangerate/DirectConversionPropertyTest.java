package com.example.exchangerate;

import com.example.exchangerate.support.MutableClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Property test for direct conversion arithmetic, checked against a simple reference model. */
class DirectConversionPropertyTest {

    private static final String BASE = "USD";
    private static final String TARGET = "EUR";

    /** One registration: an effective instant and a rate. */
    record Reg(Instant at, BigDecimal rate) { }

    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 60L).map(Instant::ofEpochSecond);
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        // 0 to 6 fractional digits, includes zero.
        return Combinators.combine(
                Arbitraries.longs().between(0L, 10_000_000_000L),
                Arbitraries.integers().between(0, 6))
            .as(BigDecimal::valueOf);
    }

    @Provide
    Arbitrary<List<Reg>> registrations() {
        Arbitrary<BigDecimal> rates = Combinators.combine(
                Arbitraries.longs().between(1L, 1_000_000_000L),
                Arbitraries.integers().between(0, 9))
            .as(BigDecimal::valueOf);
        return Combinators.combine(instants(), rates).as(Reg::new).list().ofMinSize(1).ofMaxSize(10);
    }

    /** Reference model: last registration per instant wins; returns the greatest instant at or before asOf. */
    private static Map.Entry<Instant, BigDecimal> model(List<Reg> regs, Instant asOf) {
        var byInstant = new TreeMap<Instant, BigDecimal>();
        for (Reg r : regs) {
            byInstant.put(r.at(), r.rate().setScale(ExchangeRateService.RATE_SCALE, RoundingMode.HALF_EVEN));
        }
        return byInstant.floorEntry(asOf);
    }

    private static ExchangeRateService load(List<Reg> regs, MutableClock clock) {
        var service = new ExchangeRateService(clock);
        for (Reg r : regs) {
            service.register(BASE, TARGET, r.rate(), r.at());
        }
        return service;
    }

    private static void assertDirect(Map.Entry<Instant, BigDecimal> expected, BigDecimal amount,
                                     ConversionResult result) {
        BigDecimal rate = expected.getValue();
        BigDecimal converted = amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        assertEquals(converted, result.convertedAmount());
        assertEquals(2, result.convertedAmount().scale());
        assertEquals(rate, result.appliedRate());
        assertTrue(result.exchangeRateUsed().isPresent());
        assertEquals(expected.getKey(), result.exchangeRateUsed().orElseThrow().effectiveAt());
        assertEquals(rate, result.exchangeRateUsed().orElseThrow().rate());
        assertEquals(amount, result.sourceAmount());
        assertEquals(BASE, result.from());
        assertEquals(TARGET, result.to());
    }

    // Feature: currency-exchange-rate-service, Property 9: Direct conversion arithmetic
    // Validates: Requirements 4.1, 4.2, 4.6
    @Property(tries = 200)
    void directConversionAtClockInstant(
            @ForAll("registrations") List<Reg> regs,
            @ForAll("amounts") BigDecimal amount,
            @ForAll("instants") Instant now) {
        var expected = model(regs, now);
        Assume.that(expected != null);
        var service = load(regs, new MutableClock(now));
        assertDirect(expected, amount, service.convert(amount, BASE, TARGET));
    }

    // Feature: currency-exchange-rate-service, Property 9: Direct conversion arithmetic
    // Validates: Requirements 4.1, 4.2, 4.6
    @Property(tries = 200)
    void directConversionAsOfInstantUsesRateInEffectThen(
            @ForAll("registrations") List<Reg> regs,
            @ForAll("amounts") BigDecimal amount,
            @ForAll("instants") Instant asOf) {
        var expected = model(regs, asOf);
        Assume.that(expected != null);
        // Clock is far in the future so a wrong "current" lookup would give a different rate.
        var service = load(regs, new MutableClock(Instant.ofEpochSecond(1_000)));
        assertDirect(expected, amount, service.convert(amount, BASE, TARGET, asOf));
    }

    // Feature: currency-exchange-rate-service, Property 9: Direct conversion arithmetic
    // Validates: Requirements 4.1, 4.6
    @Property(tries = 100)
    void zeroAmountYieldsScaleTwoZero(@ForAll("registrations") List<Reg> regs) {
        var service = load(regs, new MutableClock(Instant.ofEpochSecond(1_000)));
        var result = service.convert(BigDecimal.ZERO, BASE, TARGET);
        assertEquals(new BigDecimal("0.00"), result.convertedAmount());
        assertEquals(2, result.convertedAmount().scale());
    }
}
