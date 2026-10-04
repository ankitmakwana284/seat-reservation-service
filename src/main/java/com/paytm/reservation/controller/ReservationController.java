package com.paytm.reservation.controller;

import com.paytm.reservation.security.UserContext;
import com.paytm.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancelReservation(@PathVariable("id") String reservationId) {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(Map.of("status", "cancelled", "reservation_id", reservationId));
    }
}