package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.exchangerate.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property tests: returned history lists are snapshots unaffected by later registrations. */
class HistorySnapshotPropertyTest {

    private static final CurrencyPair PAIR = new CurrencyPair("USD", "EUR");

    /** A (instant, rate) registration. */
    record Reg(Instant at, BigDecimal rate) {}

    @Provide
    Arbitrary<Reg> regs() {
        Arbitrary<Instant> at = Arbitraries.longs().between(0L, 200L).map(Instant::ofEpochSecond);
        Arbitrary<BigDecimal> rate = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.001"), new BigDecimal("1000"))
                .ofScale(6);
        return net.jqwik.api.Combinators.combine(at, rate).as(Reg::new);
    }

    @Provide
    Arbitrary<List<Reg>> regLists() {
        return regs().list().ofMaxSize(15);
    }

    // Feature: currency-exchange-rate-service, Property 8: Returned histories are snapshots
    // Validates: Requirements 3.10
    @Property(tries = 200)
    void returnedHistoriesAreUnaffectedByLaterRegistrations(
            @ForAll("regLists") List<Reg> initial,
            @ForAll("regLists") List<Reg> later,
            @ForAll("regs") Reg replacing,
            @ForAll("regs") Reg bounds) {
        var service = new ExchangeRateService(new MutableClock(Instant.ofEpochSecond(0)));
        for (Reg r : initial) {
            service.register("USD", "EUR", r.rate(), r.at());
        }

        Instant a = bounds.at();
        Instant b = replacing.at();
        Instant start = a.isBefore(b) ? a : b;
        Instant end = a.isBefore(b) ? b : a;

        List<ExchangeRate> full = service.history(PAIR);
        List<ExchangeRate> range = service.history(PAIR, start, end);
        List<ExchangeRate> fullCopy = new ArrayList<>(full);
        List<ExchangeRate> rangeCopy = new ArrayList<>(range);

        // Later registrations: arbitrary ones, plus a replacement of an existing instant if any,
        // plus a brand-new instant outside the generated range.
        for (Reg r : later) {
            service.register("USD", "EUR", r.rate(), r.at());
        }
        if (!fullCopy.isEmpty()) {
            ExchangeRate existing = fullCopy.get(0);
            service.register("USD", "EUR", existing.rate().add(new BigDecimal("1")), existing.effectiveAt());
        }
        service.register("USD", "EUR", replacing.rate(), replacing.at());
        service.register("USD", "EUR", new BigDecimal("1.5"), Instant.ofEpochSecond(10_000));

        assertEquals(fullCopy.size(), full.size());
        assertEquals(fullCopy, full);
        assertEquals(rangeCopy.size(), range.size());
        assertEquals(rangeCopy, range);
    }
}
