package com.binance.market.dto;

import java.util.List;

public record OrderBookDepthResponse(
		long lastUpdateId,
		List<List<String>> bids,
		List<List<String>> asks) {
}
