package com.paytm.reservation.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_show_seat_number", columnNames = {"show_id", "seat_number"})
    },
    indexes = {
        @Index(name = "idx_show_status", columnList = "show_id, status"),
        @Index(name = "idx_show_user", columnList = "show_id, reserved_by_user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "seat_number", nullable = false, length = 32)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "reserved_by_user_id")
    private String reservedByUserId;

    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}