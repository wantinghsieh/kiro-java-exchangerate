package com.example.exchangerate.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A controllable {@link Clock} for tests. Time only changes through {@link #set(Instant)}
 * and {@link #advance(Duration)}; the wall clock is never read.
 *
 * <p>Thread-safe: the current instant is held in an {@link AtomicReference}, and
 * {@code advance} is an atomic read-modify-write, so concurrent calls never lose updates.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    /**
     * Creates a clock at the given instant in UTC.
     *
     * @param initial the starting instant, not null
     */
    public MutableClock(Instant initial) {
        this(initial, ZoneId.of("UTC"));
    }

    /**
     * Creates a clock at the given instant and zone.
     *
     * @param initial the starting instant, not null
     * @param zone    the time zone, not null
     */
    public MutableClock(Instant initial, ZoneId zone) {
        this.now = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Sets the clock to the given instant.
     *
     * @param instant the new instant, not null
     */
    public void set(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant"));
    }

    /**
     * Atomically moves the clock forward (or backward if negative) by the given duration.
     *
     * @param duration the amount to add, not null
     */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** Returns a view sharing this clock's instant but using a different zone. */
    @Override
    public Clock withZone(ZoneId newZone) {
        Objects.requireNonNull(newZone, "zone");
        if (newZone.equals(zone)) {
            return this;
        }
        MutableClock parent = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return newZone;
            }

            @Override
            public Clock withZone(ZoneId z) {
                return parent.withZone(z);
            }

            @Override
            public Instant instant() {
                return parent.instant();
            }
        };
    }
}
