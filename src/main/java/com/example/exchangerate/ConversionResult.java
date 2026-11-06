package com.example.exchangerate;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a currency conversion.
 *
 * <p>{@code appliedRate} is the multiplier actually used: the stored rate, the reciprocal of a
 * stored inverse rate, or {@code 1} for a same-currency conversion. {@code sourceRate} is the
 * stored {@link ExchangeRate} behind that multiplier, or {@code null} for the same-currency
 * identity case. Prefer {@link #exchangeRateUsed()} over the raw {@code sourceRate()} accessor.
 * {@code from} and {@code to} are plain codes (not a {@link CurrencyPair}) because the identity
 * case has equal codes.
 *
 * <p>Thread-safety: this record is immutable and thread-safe. Its {@link BigDecimal} and
 * {@link ExchangeRate} components are immutable, so instances can be shared freely across threads.
 *
 * @param sourceAmount    the amount that was converted; never null
 * @param convertedAmount the converted amount; never null
 * @param from            the source currency code; never null
 * @param to              the target currency code; never null
 * @param appliedRate     the multiplier used; never null
 * @param sourceRate      the stored rate behind {@code appliedRate}, or null for the identity case
 */
public record ConversionResult(
        BigDecimal sourceAmount,
        BigDecimal convertedAmount,
        String from,
        String to,
        BigDecimal appliedRate,
        ExchangeRate sourceRate) {

    /**
     * Validates that every component except {@code sourceRate} is non-null.
     *
     * @throws NullPointerException if a required component is null
     */
    public ConversionResult {
        Objects.requireNonNull(sourceAmount, "sourceAmount");
        Objects.requireNonNull(convertedAmount, "convertedAmount");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(appliedRate, "appliedRate");
    }

    /**
     * Returns the stored exchange rate used for this conversion.
     *
     * @return the stored rate, or empty for a same-currency (identity) conversion
     */
    public Optional<ExchangeRate> exchangeRateUsed() {
        return Optional.ofNullable(sourceRate);
    }
}
