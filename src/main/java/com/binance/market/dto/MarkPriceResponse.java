package com.binance.market.dto;

import java.math.BigDecimal;

public record MarkPriceResponse(
		String symbol,
		BigDecimal markPrice) {
}
