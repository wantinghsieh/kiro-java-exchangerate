package com.example.exchangerate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores time-versioned exchange rates per ordered currency pair.
 *
 * <p>Thread-safety: this class is thread-safe. Each pair maps to an immutable, ascending
 * list that is replaced atomically on write, so readers always see a complete snapshot.
 */
public final class ExchangeRateService {

    /** Number of fractional digits kept for stored rates. */
    public static final int RATE_SCALE = 10;

    /** Number of fractional digits of converted amounts. */
    public static final int AMOUNT_SCALE = 2;

    private final Clock clock;
    private final ConcurrentHashMap<CurrencyPair, List<ExchangeRate>> state =
            new ConcurrentHashMap<>();

    /**
     * Creates a service that uses the given clock for default effective instants.
     *
     * @param clock the clock; must not be null
     * @throws NullPointerException if clock is null
     */
    public ExchangeRateService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a rate effective at the current clock instant (read once).
     *
     * <p>Thread-safe.
     *
     * @param base   the base currency code
     * @param target the target currency code
     * @param rate   the positive rate; rounded to {@link #RATE_SCALE} with HALF_EVEN
     * @return the stored exchange rate
     * @throws IllegalArgumentException if any input is invalid
     */
    public ExchangeRate register(String base, String target, BigDecimal rate) {
        return doRegister(base, target, rate, clock.instant());
    }

    /**
     * Registers a rate effective at the given instant, replacing any entry at that instant.
     *
     * <p>Thread-safe.
     *
     * @param base   the base currency code
     * @param target the target currency code
     * @param rate   the positive rate; rounded to {@link #RATE_SCALE} with HALF_EVEN
     * @param at     the effective instant; must not be null
     * @return the stored exchange rate
     * @throws IllegalArgumentException if any input is invalid, including a null instant
     */
    public ExchangeRate register(String base, String target, BigDecimal rate, Instant at) {
        return doRegister(base, target, rate, at);
    }

    /**
     * Returns the rate in effect now (clock read once).
     *
     * <p>Thread-safe.
     *
     * @param base   the base currency code
     * @param target the target currency code
     * @return the current rate, or empty if none is effective yet
     * @throws IllegalArgumentException if a code or the pair is invalid
     */
    public Optional<ExchangeRate> currentRate(String base, String target) {
        CurrencyCodes.requireValid(base);
        CurrencyCodes.requireValid(target);
        var pair = new CurrencyPair(base, target);
        return rateAsOf(pair, clock.instant());
    }

    /**
     * Returns the rate with the greatest effective instant not after {@code asOf}.
     *
     * <p>Thread-safe; operates on an immutable snapshot.
     *
     * @param pair the currency pair; must not be null
     * @param asOf the lookup instant; must not be null
     * @return the applicable rate, or empty if none
     * @throws NullPointerException if an argument is null
     */
    public Optional<ExchangeRate> rateAsOf(CurrencyPair pair, Instant asOf) {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(asOf, "asOf");
        var list = state.get(pair);
        if (list == null) {
            return Optional.empty();
        }
        int lo = 0;
        int hi = list.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).effectiveAt().compareTo(asOf) <= 0) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found < 0 ? Optional.empty() : Optional.of(list.get(found));
    }

    /**
     * Returns the full history for a pair in ascending effective order.
     *
     * <p>Thread-safe; the result is an immutable snapshot.
     *
     * @param pair the currency pair; must not be null
     * @return immutable list, empty if none
     * @throws NullPointerException if pair is null
     */
    public List<ExchangeRate> history(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        var list = state.get(pair);
        return list == null ? List.of() : list;
    }

    /**
     * Returns history entries with effective instant in {@code [startInclusive, endExclusive)}.
     *
     * <p>Thread-safe; the result is an immutable snapshot.
     *
     * @param pair           the currency pair; must not be null
     * @param startInclusive the inclusive start; must not be null
     * @param endExclusive   the exclusive end; must not be null
     * @return immutable list in ascending order
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if start is after end
     */
    public List<ExchangeRate> history(
            CurrencyPair pair, Instant startInclusive, Instant endExclusive) {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (startInclusive.isAfter(endExclusive)) {
            throw new IllegalArgumentException("start must not be after end");
        }
        if (startInclusive.equals(endExclusive)) {
            return List.of();
        }
        return List.copyOf(history(pair).stream()
                .filter(r -> !r.effectiveAt().isBefore(startInclusive)
                        && r.effectiveAt().isBefore(endExclusive))
                .toList());
    }

    /**
     * Converts an amount using the rate in effect now (clock read once).
     *
     * <p>Thread-safe.
     *
     * @param amount the non-negative amount; must not be null
     * @param from   the source currency code
     * @param to     the target currency code
     * @return the conversion result
     * @throws IllegalArgumentException if a code or the amount is invalid
     * @throws RateNotFoundException    if no direct or inverse rate is available
     */
    public ConversionResult convert(BigDecimal amount, String from, String to) {
        return doConvert(amount, from, to, null);
    }

    /**
     * Converts an amount using the rate in effect at {@code asOf}.
     *
     * <p>Resolution order: same currency (rate 1); direct rate; reciprocal of the inverse rate
     * (scale {@link #RATE_SCALE}, HALF_EVEN); otherwise {@link RateNotFoundException}. The
     * converted amount is rounded to {@link #AMOUNT_SCALE} with HALF_EVEN.
     *
     * <p>Thread-safe; operates on immutable snapshots.
     *
     * @param amount the non-negative amount; must not be null
     * @param from   the source currency code
     * @param to     the target currency code
     * @param asOf   the lookup instant; must not be null
     * @return the conversion result
     * @throws IllegalArgumentException if a code or the amount is invalid
     * @throws NullPointerException     if asOf is null
     * @throws RateNotFoundException    if no direct or inverse rate is available
     */
    public ConversionResult convert(BigDecimal amount, String from, String to, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return doConvert(amount, from, to, asOf);
    }

    private ConversionResult doConvert(BigDecimal amount, String from, String to, Instant asOf) {
        CurrencyCodes.requireValid(from);
        CurrencyCodes.requireValid(to);
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-null and non-negative");
        }
        var at = asOf != null ? asOf : clock.instant();
        if (from.equals(to)) {
            return new ConversionResult(amount, amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN),
                    from, to, BigDecimal.ONE, null);
        }
        var direct = rateAsOf(new CurrencyPair(from, to), at);
        if (direct.isPresent()) {
            var stored = direct.get();
            return result(amount, from, to, stored.rate(), stored);
        }
        var inverse = rateAsOf(new CurrencyPair(to, from), at);
        if (inverse.isPresent()) {
            var stored = inverse.get();
            var reciprocal = BigDecimal.ONE.divide(stored.rate(), RATE_SCALE, RoundingMode.HALF_EVEN);
            return result(amount, from, to, reciprocal, stored);
        }
        throw new RateNotFoundException(from, to);
    }

    private static ConversionResult result(
            BigDecimal amount, String from, String to, BigDecimal multiplier, ExchangeRate stored) {
        var converted = amount.multiply(multiplier).setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        return new ConversionResult(amount, converted, from, to, multiplier, stored);
    }

    private ExchangeRate doRegister(String base, String target, BigDecimal rate, Instant at) {
        CurrencyCodes.requireValid(base);
        CurrencyCodes.requireValid(target);
        var pair = new CurrencyPair(base, target);
        if (at == null) {
            throw new IllegalArgumentException("effective Instant must not be null");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        var rounded = rate.setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
        if (rounded.signum() == 0) {
            throw new IllegalArgumentException("rate rounds to zero at the Rate_Scale");
        }
        var stored = new ExchangeRate(pair, rounded, at);
        state.compute(pair, (_, existing) -> {
            var updated = new ArrayList<ExchangeRate>();
            if (existing != null) {
                for (var e : existing) {
                    if (!e.effectiveAt().equals(at)) {
                        updated.add(e);
                    }
                }
            }
            updated.add(stored);
            updated.sort(Comparator.comparing(ExchangeRate::effectiveAt));
            return List.copyOf(updated);
        });
        return stored;
    }
}
