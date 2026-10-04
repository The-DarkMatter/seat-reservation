package dev.amogh.seats.obs;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.amogh.seats.reservation.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Business counters, incremented only AFTER the transaction that caused them
 * has committed, so they never count something the database rolled back.
 *
 * Prometheus names: reservations_confirmed_total, reservations_held_total,
 * reservations_declined_total{reason}, reservations_cancelled_total,
 * reservations_expired_total, seat_sales_total, seat_holds_total,
 * seat_releases_total{cause}, reservation_lock_retries_total and the
 * reservation_reserve_seconds histogram (by outcome). Seat event counters are
 * named seat_* so they can't collide with the seats_* state gauges.
 *
 * Every counter is registered at startup with value 0, so dashboards and
 * rate() work from the first scrape rather than appearing mid-burst.
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter reservationsConfirmed;
    private final Counter seatsConfirmed;
    private final Counter reservationsHeld;
    private final Counter seatsHeld;
    private final Counter reservationsCancelled;
    private final Counter reservationsExpired;
    private final Counter seatsReleasedByCancel;
    private final Counter seatsReleasedByExpiry;
    private final Counter lockRetries;
    private final Map<DeclineReason, Counter> declined = new EnumMap<>(DeclineReason.class);

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        reservationsConfirmed = counter("reservations.confirmed", "Reservations that became confirmed sales");
        seatsConfirmed = counter("seat.sales", "Seats that became confirmed sales");
        reservationsHeld = counter("reservations.held", "Holds created on hold-mode shows");
        seatsHeld = counter("seat.holds", "Seats placed on hold");
        reservationsCancelled = counter("reservations.cancelled", "Reservations cancelled by their owner");
        reservationsExpired = counter("reservations.expired", "Holds that ran out and were swept");
        seatsReleasedByCancel = Counter.builder("seat.releases").tag("cause", "cancel")
                .description("Seats returned to available").register(registry);
        seatsReleasedByExpiry = Counter.builder("seat.releases").tag("cause", "expiry")
                .description("Seats returned to available").register(registry);
        lockRetries = counter("reservation.lock.retries", "Transactions retried after a deadlock or lock-wait timeout");
        for (DeclineReason reason : DeclineReason.values()) {
            declined.put(reason, Counter.builder("reservations.declined")
                    .tag("reason", reason.code())
                    .description("Reserve requests that did not create a reservation, by reason")
                    .register(registry));
        }
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public void reserved(String status, int seats) {
        if (status.equals("held")) {
            reservationsHeld.increment();
            seatsHeld.increment(seats);
        } else {
            reservationsConfirmed.increment();
            seatsConfirmed.increment(seats);
        }
    }

    /** Seats added to an existing hold (same reservation, same expiry). */
    public void addedToHold(int seats) {
        seatsHeld.increment(seats);
    }

    public void holdConfirmed(int seats) {
        reservationsConfirmed.increment();
        seatsConfirmed.increment(seats);
    }

    public void declined(DeclineReason reason) {
        declined.get(reason).increment();
    }

    public void cancelled(int seatsReleased) {
        reservationsCancelled.increment();
        seatsReleasedByCancel.increment(seatsReleased);
    }

    public void expired(int seatsReleased) {
        reservationsExpired.increment();
        seatsReleasedByExpiry.increment(seatsReleased);
    }

    public void lockRetry() {
        lockRetries.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("reservation.reserve")
                .description("End-to-end reserve latency, including lock waits and retries")
                .tag("outcome", outcome)
                .register(registry));
    }
}
