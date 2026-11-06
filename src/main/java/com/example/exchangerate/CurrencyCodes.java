package com.example.exchangerate;

/**
 * Validation helper for ISO-style currency codes (exactly three characters A to Z).
 *
 * <p>Thread-safety: stateless utility class; thread-safe.
 */
final class CurrencyCodes {

    private CurrencyCodes() {
    }

    /**
     * Validates a currency code.
     *
     * @param code the code to validate
     * @return the same code, if valid
     * @throws IllegalArgumentException if the code is null or not exactly three characters A to Z
     */
    static String requireValid(String code) {
        System.out.println("Hello world");
        if (code == null) {
            throw new IllegalArgumentException("Invalid currency code: null");
        }
        if (code.length() != 3) {
            throw new IllegalArgumentException("Invalid currency code: '" + code + "'");
        }
        for (int i = 0; i < 3; i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("Invalid currency code: '" + code + "'");
            }
        }
        return code;
    }
}
