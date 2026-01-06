package com.binance.strategy.indicators;

public class EmaIndicator {

	private final int period;
	private final double alpha;
	private double value = Double.NaN;
	private int count;

	public EmaIndicator(int period) {
		this.period = period;
		this.alpha = 2.0 / (period + 1.0);
	}

	public double update(double price) {
		if (Double.isNaN(value)) {
			value = price;
		} else {
			value = value + alpha * (price - value);
		}
		count++;
		return value;
	}

	public boolean isReady() {
		return count >= period;
	}
}
