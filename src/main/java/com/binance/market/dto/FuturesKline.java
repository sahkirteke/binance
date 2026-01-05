package com.binance.market.dto;

public record FuturesKline(
		long openTime,
		double open,
		double high,
		double low,
		double close,
		long closeTime) {
}
