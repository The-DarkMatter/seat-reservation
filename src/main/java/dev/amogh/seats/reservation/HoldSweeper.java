package dev.amogh.seats.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping, not correctness. An overdue hold is already claimable by
 * anyone, and already reported as available, the instant it expires, because
 * every query compares held_until with NOW(6). The sweeper just tidies the
 * rows (seat back to 'available', reservation marked 'expired') so the table
 * says plainly what is true, and counts the releases.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);
    private static final int MAX_PER_RUN = 500;

    private final ReservationService reservations;

    public HoldSweeper(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${app.sweeper-interval}")
    public void sweep() {
        try {
            int expired = 0;
            while (expired < MAX_PER_RUN && reservations.expireOneOverdueHold() != null) {
                expired++;
            }
            if (expired > 0) {
                log.info("expired {} overdue holds", expired);
            }
        } catch (RuntimeException e) {
            // Next run picks up where this one stopped.
            log.warn("hold sweep failed: {}", e.getMessage());
        }
    }
}
