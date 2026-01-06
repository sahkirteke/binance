package com.binance.strategy.indicators;

public class AtrWilder {

	private final int period;
	private double prevClose = Double.NaN;
	private int count;
	private double sum;
	private double value = Double.NaN;

	public AtrWilder(int period) {
		this.period = period;
	}

	public double update(double high, double low, double close) {
		if (Double.isNaN(prevClose)) {
			prevClose = close;
			return value;
		}
		double tr = Math.max(high - low,
				Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
		prevClose = close;
		if (count < period) {
			sum += tr;
			count++;
			if (count == period) {
				value = sum / period;
			}
			return value;
		}
		value = (value * (period - 1.0) + tr) / period;
		return value;
	}

	public boolean isReady() {
		return count >= period;
	}

	public double value() {
		return value;
	}
}
