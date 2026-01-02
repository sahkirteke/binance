package com.binance.strategy;

public interface StrategyRunner {
	StrategyType type();

	void start();
}
