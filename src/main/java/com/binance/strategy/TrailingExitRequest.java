package com.binance.strategy;

public record TrailingExitRequest(
		String symbol,
		CtiDirection side,
		double entryPrice,
		double markPrice,
		int leverageUsed,
		double pnlPct,
		String reason,
		int profitExitCount,
		int lossExitCount) {
}
