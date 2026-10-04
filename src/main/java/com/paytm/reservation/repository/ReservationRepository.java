package com.paytm.reservation.repository;

import com.paytm.reservation.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {
    Optional<Reservation> findByIdAndUserId(String id, String userId);
}