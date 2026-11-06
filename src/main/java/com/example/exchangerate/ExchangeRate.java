package com.example.exchangerate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A rate for a currency pair that is effective from a given instant.
 *
 * <p>Thread-safety: immutable and thread-safe.
 *
 * @param pair        the currency pair
 * @param rate        the positive rate with a scale of at most 10
 * @param effectiveAt the instant from which the rate applies
 */
public record ExchangeRate(CurrencyPair pair, BigDecimal rate, Instant effectiveAt) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if rate is not positive or its scale exceeds 10
     */
    public ExchangeRate {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive: " + rate);
        }
        if (rate.scale() > 10) {
            throw new IllegalArgumentException("rate scale must be at most 10: " + rate);
        }
    }
}
