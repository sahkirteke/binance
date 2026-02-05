package com.binance.strategy;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class PaperStopLossOrderExecutor implements StopLossOrderExecutor {

	@Override
	public StopLossOrderResult placeStopLoss(StopLossOrderRequest req) {
		String orderId = "paper-sl-" + UUID.randomUUID();
		return new StopLossOrderResult(orderId, req.stopPrice());
	}

	@Override
	public void cancelStopLoss(String symbol, String orderId) {
		// no-op for paper
	}
}
