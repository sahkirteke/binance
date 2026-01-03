package com.binance.strategy;

public interface TrendIndicator {
	TrendSignal onClosedCandle(double close, long closeTime);
}
