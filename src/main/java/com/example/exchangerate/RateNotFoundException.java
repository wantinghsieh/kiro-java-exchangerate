package com.example.exchangerate;

/**
 * Thrown when no direct or inverse rate is available for a requested conversion.
 *
 * <p>Thread-safety: immutable once constructed and thread-safe.
 */
public class RateNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for the given codes.
     *
     * @param from the source currency code
     * @param to   the target currency code
     */
    public RateNotFoundException(String from, String to) {
        super("No exchange rate found for " + from + " to " + to);
    }
}
