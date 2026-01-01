package com.binance.market.dto;

import java.math.BigDecimal;

public record BookTickerResponse(
		String symbol,
		BigDecimal bidPrice,
		BigDecimal bidQty,
		BigDecimal askPrice,
		BigDecimal askQty) {
}
