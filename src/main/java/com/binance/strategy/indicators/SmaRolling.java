package com.binance.strategy.indicators;

public class SmaRolling {

	private final double[] window;
	private int index;
	private int count;
	private double sum;

	public SmaRolling(int size) {
		this.window = new double[size];
	}

	public double update(double value) {
		if (Double.isNaN(value)) {
			return current();
		}
		if (count < window.length) {
			window[index] = value;
			sum += value;
			count++;
		} else {
			sum -= window[index];
			window[index] = value;
			sum += value;
		}
		index = (index + 1) % window.length;
		return current();
	}

	public boolean isReady() {
		return count >= window.length;
	}

	public double current() {
		return isReady() ? sum / window.length : Double.NaN;
	}
}
