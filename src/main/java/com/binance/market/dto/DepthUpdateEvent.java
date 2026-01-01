package com.binance.market.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DepthUpdateEvent(
		@JsonProperty("E") long eventTime,
		@JsonProperty("s") String symbol,
		@JsonProperty("U") long firstUpdateId,
		@JsonProperty("u") long finalUpdateId,
		@JsonProperty("b") List<List<String>> bids,
		@JsonProperty("a") List<List<String>> asks) {
}
