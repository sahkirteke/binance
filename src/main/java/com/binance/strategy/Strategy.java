package com.binance.strategy;

public interface Strategy {

	StrategyType type();

	void start();

	void stop();
}
