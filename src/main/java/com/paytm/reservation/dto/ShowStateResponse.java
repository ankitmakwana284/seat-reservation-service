package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowStateResponse {

    @JsonProperty("show_id")
    private Long showId;

    private String name;

    @JsonProperty("price_paise")
    private Long pricePaise;

    @JsonProperty("total_seats")
    private Integer totalSeats;

    private Map<String, Long> counts; // available, held, confirmed

    private Map<String, String> seats; // seatNumber -> status
}