package com.paytm.reservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final Counter reservationsConfirmedCounter;
    private final Counter declinedSeatTakenCounter;
    private final Counter declinedUserLimitCounter;
    private final Counter idempotentReplayCounter;

    public ReservationMetrics(MeterRegistry meterRegistry) {
        this.reservationsConfirmedCounter = Counter.builder("reservations_total")
                .tag("status", "confirmed")
                .description("Total number of confirmed reservations")
                .register(meterRegistry);

        this.declinedSeatTakenCounter = Counter.builder("reservations_declined_total")
                .tag("reason", "seat_taken")
                .description("Total reservations declined due to seat collision")
                .register(meterRegistry);

        this.declinedUserLimitCounter = Counter.builder("reservations_declined_total")
                .tag("reason", "user_limit_exceeded")
                .description("Total reservations declined due to per-user limit")
                .register(meterRegistry);

        this.idempotentReplayCounter = Counter.builder("reservations_declined_total")
                .tag("reason", "idempotent_replay")
                .description("Total requests served via idempotent replay")
                .register(meterRegistry);
    }

    public void incrementConfirmed() {
        reservationsConfirmedCounter.increment();
    }

    public void incrementSeatTaken() {
        declinedSeatTakenCounter.increment();
    }

    public void incrementUserLimit() {
        declinedUserLimitCounter.increment();
    }

    public void incrementIdempotentReplay() {
        idempotentReplayCounter.increment();
    }
}