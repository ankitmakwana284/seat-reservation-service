package com.paytm.reservation.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "reservations",
    indexes = {
        @Index(name = "idx_res_show_user", columnList = "show_id, user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "seats_json", nullable = false, columnDefinition = "TEXT")
    private String seatsJson;

    @Column(name = "seat_count", nullable = false)
    private Integer seatCount;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}