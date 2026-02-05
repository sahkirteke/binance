package com.binance.strategy;

import org.springframework.stereotype.Component;

@Component
public class LiveStopLossOrderExecutor implements StopLossOrderExecutor {

	@Override
	public StopLossOrderResult placeStopLoss(StopLossOrderRequest req) {
		throw new UnsupportedOperationException("LIVE stop-loss placement not implemented yet.");
	}

	@Override
	public void cancelStopLoss(String symbol, String orderId) {
		throw new UnsupportedOperationException("LIVE stop-loss cancel not implemented yet.");
	}
}
