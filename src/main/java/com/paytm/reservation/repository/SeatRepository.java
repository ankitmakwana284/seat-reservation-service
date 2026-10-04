package com.paytm.reservation.repository;

import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByShowId(Long showId);

    List<Seat> findByShowIdAndSeatNumberIn(Long showId, List<String> seatNumbers);

    long countByShowIdAndReservedByUserIdAndStatus(Long showId, String userId, SeatStatus status);

    long countByShowIdAndStatus(Long showId, SeatStatus status);

    @Modifying
    @Query("""
        UPDATE Seat s 
        SET s.status = :targetStatus, 
            s.reservedByUserId = :userId, 
            s.reservationId = :reservationId, 
            s.updatedAt = :now 
        WHERE s.showId = :showId 
          AND s.seatNumber = :seatNumber 
          AND s.status = :expectedStatus
    """)
    int atomicallyClaimSeat(
        @Param("showId") Long showId,
        @Param("seatNumber") String seatNumber,
        @Param("expectedStatus") SeatStatus expectedStatus,
        @Param("targetStatus") SeatStatus targetStatus,
        @Param("userId") String userId,
        @Param("reservationId") String reservationId,
        @Param("now") Instant now
    );

    @Modifying
    @Query("""
        UPDATE Seat s 
        SET s.status = 'AVAILABLE', 
            s.reservedByUserId = NULL, 
            s.reservationId = NULL, 
            s.updatedAt = :now 
        WHERE s.reservationId = :reservationId
    """)
    int releaseSeatsByReservationId(
        @Param("reservationId") String reservationId,
        @Param("now") Instant now
    );
}