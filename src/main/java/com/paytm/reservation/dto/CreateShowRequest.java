package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class CreateShowRequest {

    @NotBlank(message = "Show name is required")
    private String name;

    @NotEmpty(message = "Seats list cannot be empty")
    private List<String> seats;

    @JsonProperty("price_paise")
    @Min(value = 0, message = "Price must be a non-negative integer in paise")
    private Long pricePaise;

    @JsonProperty("per_user_limit")
    @Builder.Default
    private Integer perUserLimit = 4;
}