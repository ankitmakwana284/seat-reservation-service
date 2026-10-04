package com.paytm.reservation.controller;

import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.ReservationResponse;
import com.paytm.reservation.dto.ReserveSeatsRequest;
import com.paytm.reservation.dto.ShowStateResponse;
import com.paytm.reservation.model.Show;
import com.paytm.reservation.security.UserContext;
import com.paytm.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
@RequiredArgsConstructor
public class ShowController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<Show> createShow(@Valid @RequestBody CreateShowRequest request) {
        Show created = reservationService.createShow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShowStateResponse> getShow(@PathVariable("id") Long id) {
        return ResponseEntity.ok(reservationService.getShowState(id));
    }

    @PostMapping("/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable("id") Long showId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody ReserveSeatsRequest request
    ) {
        String userId = UserContext.getUserId();
        ReservationResponse response = reservationService.reserveSeats(showId, userId, idempotencyKeyHeader, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}