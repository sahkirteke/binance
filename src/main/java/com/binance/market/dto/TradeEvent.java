package com.binance.market.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TradeEvent(
		@JsonProperty("E") long eventTime,
		@JsonProperty("s") String symbol,
		@JsonProperty("p") BigDecimal price,
		@JsonProperty("q") BigDecimal quantity,
		@JsonProperty("m") boolean buyerMaker) {
}
