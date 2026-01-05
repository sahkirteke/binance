package com.binance.strategy;

import java.util.OptionalDouble;

public class AdxIndicator {

	private final int period;
	private double prevHigh;
	private double prevLow;
	private double prevClose;
	private boolean initialized;
	private int trCount;
	private double trSum;
	private double plusDmSum;
	private double minusDmSum;
	private int dxCount;
	private double dxSum;
	private double adx;

	public AdxIndicator(int period) {
		if (period <= 0) {
			throw new IllegalArgumentException("period must be positive");
		}
		this.period = period;
	}

	public OptionalDouble update(double high, double low, double close) {
		if (!initialized) {
			initialized = true;
			prevHigh = high;
			prevLow = low;
			prevClose = close;
			return OptionalDouble.empty();
		}

		double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
		double upMove = high - prevHigh;
		double downMove = prevLow - low;
		double plusDm = (upMove > downMove && upMove > 0) ? upMove : 0;
		double minusDm = (downMove > upMove && downMove > 0) ? downMove : 0;

		OptionalDouble result;
		if (trCount < period) {
			trSum += tr;
			plusDmSum += plusDm;
			minusDmSum += minusDm;
			trCount++;
			result = OptionalDouble.empty();
		} else {
			trSum = trSum - (trSum / period) + tr;
			plusDmSum = plusDmSum - (plusDmSum / period) + plusDm;
			minusDmSum = minusDmSum - (minusDmSum / period) + minusDm;
			result = computeAdx();
		}

		prevHigh = high;
		prevLow = low;
		prevClose = close;
		return result;
	}

	private OptionalDouble computeAdx() {
		if (trSum == 0) {
			return OptionalDouble.empty();
		}
		double plusDi = 100.0 * (plusDmSum / trSum);
		double minusDi = 100.0 * (minusDmSum / trSum);
		double sum = plusDi + minusDi;
		if (sum == 0) {
			return OptionalDouble.empty();
		}
		double dx = 100.0 * (Math.abs(plusDi - minusDi) / sum);
		if (dxCount < period) {
			dxSum += dx;
			dxCount++;
			if (dxCount == period) {
				adx = dxSum / period;
				return OptionalDouble.of(adx);
			}
			return OptionalDouble.empty();
		}
		adx = ((adx * (period - 1)) + dx) / period;
		return OptionalDouble.of(adx);
	}
}
