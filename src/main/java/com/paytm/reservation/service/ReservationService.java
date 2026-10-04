package com.paytm.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.ReservationResponse;
import com.paytm.reservation.dto.ReserveSeatsRequest;
import com.paytm.reservation.dto.ShowStateResponse;
import com.paytm.reservation.exception.*;
import com.paytm.reservation.metrics.ReservationMetrics;
import com.paytm.reservation.model.*;
import com.paytm.reservation.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ReservationMetrics metrics;
    private final ObjectMapper objectMapper;

    @Transactional
    public Show createShow(CreateShowRequest request) {
        List<String> distinctSeats = request.getSeats().stream().distinct().toList();

        Show show = Show.builder()
                .name(request.getName())
                .pricePaise(request.getPricePaise())
                .perUserLimit(request.getPerUserLimit() != null ? request.getPerUserLimit() : 4)
                .totalSeats(distinctSeats.size())
                .build();

        Show savedShow = showRepository.save(show);

        List<Seat> seats = distinctSeats.stream()
                .map(seatNo -> Seat.builder()
                        .showId(savedShow.getId())
                        .seatNumber(seatNo)
                        .status(SeatStatus.AVAILABLE)
                        .build())
                .toList();

        seatRepository.saveAll(seats);
        return savedShow;
    }

    public ShowStateResponse getShowState(Long showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        List<Seat> seats = seatRepository.findByShowId(showId);

        Map<String, Long> counts = new HashMap<>();
        counts.put("available", 0L);
        counts.put("held", 0L);
        counts.put("confirmed", 0L);

        Map<String, String> seatMap = new LinkedHashMap<>();

        for (Seat seat : seats) {
            String status = seat.getStatus().name().toLowerCase();
            counts.put(status, counts.getOrDefault(status, 0L) + 1L);
            seatMap.put(seat.getSeatNumber(), status);
        }

        return ShowStateResponse.builder()
                .showId(show.getId())
                .name(show.getName())
                .pricePaise(show.getPricePaise())
                .totalSeats(show.getTotalSeats())
                .counts(counts)
                .seats(seatMap)
                .build();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserveSeats(Long showId, String userId, String headerIdempotencyKey, ReserveSeatsRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User authentication required (Authorization: Bearer <userId>)");
        }

        String idempotencyKey = (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank())
                ? headerIdempotencyKey
                : request.getIdempotencyKey();

        // 1. Sort requested seats deterministically to eliminate cross-transaction deadlocks
        List<String> requestedSeats = request.getSeats().stream().distinct().sorted().toList();
        if (requestedSeats.isEmpty()) {
            throw new IllegalArgumentException("Must request at least 1 valid seat");
        }

        String requestHash = computeSha256(showId + ":" + String.join(",", requestedSeats));

        // 2. Check Idempotency Key
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                if (!record.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException("Idempotency key already used for a different request payload");
                }
                metrics.incrementIdempotentReplay();
                try {
                    return objectMapper.readValue(record.getResponseBody(), ReservationResponse.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize idempotency response", e);
                }
            }
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        // 3. Enforce Per-User Limit
        long currentHeld = seatRepository.countByShowIdAndReservedByUserIdAndStatus(showId, userId, SeatStatus.CONFIRMED);
        if (currentHeld + requestedSeats.size() > show.getPerUserLimit()) {
            metrics.incrementUserLimit();
            throw new PerUserLimitExceededException("Booking would exceed maximum limit of " + show.getPerUserLimit() + " seats per user");
        }

        // 4. Atomic All-or-Nothing Claim
        String reservationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<String> successfullyClaimed = new ArrayList<>();

        for (String seatNumber : requestedSeats) {
            int updated = seatRepository.atomicallyClaimSeat(
                    showId,
                    seatNumber,
                    SeatStatus.AVAILABLE,
                    SeatStatus.CONFIRMED,
                    userId,
                    reservationId,
                    now
            );

            if (updated == 1) {
                successfullyClaimed.add(seatNumber);
            } else {
                // Another transaction got this seat first - abort and rollback claimed seats
                metrics.incrementSeatTaken();
                throw new SeatConflictException("Seat " + seatNumber + " is already taken or unavailable");
            }
        }

        // 5. Create Confirmed Reservation
        long totalAmountPaise = show.getPricePaise() * requestedSeats.size();
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .showId(showId)
                .userId(userId)
                .seatsJson(toJson(requestedSeats))
                .seatCount(requestedSeats.size())
                .amountPaise(totalAmountPaise)
                .status("confirmed")
                .createdAt(now)
                .build();

        reservationRepository.save(reservation);

        ReservationResponse response = ReservationResponse.builder()
                .reservationId(reservationId)
                .showId(showId)
                .userId(userId)
                .seats(requestedSeats)
                .amountPaise(totalAmountPaise)
                .status("confirmed")
                .build();

        // 6. Record Idempotency Result
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyRecordRepository.save(IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .userId(userId)
                    .requestHash(requestHash)
                    .statusCode(201)
                    .responseBody(toJson(response))
                    .createdAt(now)
                    .build());
        }

        metrics.incrementConfirmed();
        return response;
    }

    @Transactional
    public void cancelReservation(String reservationId, String userId) {
        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found or does not belong to user"));

        seatRepository.releaseSeatsByReservationId(reservationId, Instant.now());
        reservation.setStatus("cancelled");
        reservationRepository.save(reservation);
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}