package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReserveSeatsRequest {

    @NotEmpty(message = "Seats list cannot be empty")
    private List<String> seats;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;
}