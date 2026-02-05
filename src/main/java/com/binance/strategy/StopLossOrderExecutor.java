package com.binance.strategy;

public interface StopLossOrderExecutor {
	StopLossOrderResult placeStopLoss(StopLossOrderRequest req);
	void cancelStopLoss(String symbol, String orderId);
}
