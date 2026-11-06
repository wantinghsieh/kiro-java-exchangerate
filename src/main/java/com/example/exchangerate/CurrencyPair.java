package com.example.exchangerate;

/**
 * An ordered pair of distinct currency codes: the base currency and the target currency.
 *
 * <p>Thread-safety: immutable and thread-safe.
 *
 * @param base   the base currency code (three characters A to Z)
 * @param target the target currency code (three characters A to Z, different from base)
 */
public record CurrencyPair(String base, String target) {

    /**
     * Validates both codes and rejects equal base and target.
     *
     * @throws IllegalArgumentException if a code is invalid or base equals target
     */
    public CurrencyPair {
        CurrencyCodes.requireValid(base);
        CurrencyCodes.requireValid(target);
        if (base.equals(target)) {
            throw new IllegalArgumentException(
                    "Base and target currency must differ: " + base);
        }
    }

    /**
     * Returns the pair with base and target swapped.
     *
     * @return the inverse pair
     */
    public CurrencyPair inverse() {
        return new CurrencyPair(target, base);
    }
}
