package com.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KlineEvent(
		@JsonProperty("E") long eventTime,
		@JsonProperty("s") String symbol,
		@JsonProperty("k") Kline kline) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Kline(
			@JsonProperty("c") double close,
			@JsonProperty("T") long closeTime,
			@JsonProperty("x") boolean closed) {
	}
}
