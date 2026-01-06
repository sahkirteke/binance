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
			@JsonProperty("o") double open,
			@JsonProperty("h") double high,
			@JsonProperty("l") double low,
			@JsonProperty("c") double close,
			@JsonProperty("v") double volume,
			@JsonProperty("T") long closeTime,
			@JsonProperty("x") boolean closed) {
	}
}
