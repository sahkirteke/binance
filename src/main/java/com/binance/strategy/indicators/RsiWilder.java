package com.binance.strategy.indicators;

public class RsiWilder {

	private final int period;
	private double prevClose = Double.NaN;
	private int count;
	private double sumGain;
	private double sumLoss;
	private double avgGain = Double.NaN;
	private double avgLoss = Double.NaN;
	private double value = Double.NaN;

	public RsiWilder(int period) {
		this.period = period;
	}

	public double update(double close) {
		if (Double.isNaN(prevClose)) {
			prevClose = close;
			return value;
		}
		double change = close - prevClose;
		double gain = Math.max(change, 0.0);
		double loss = Math.max(-change, 0.0);
		prevClose = close;
		if (count < period) {
			sumGain += gain;
			sumLoss += loss;
			count++;
			if (count == period) {
				avgGain = sumGain / period;
				avgLoss = sumLoss / period;
				value = resolveRsi(avgGain, avgLoss);
			}
			return value;
		}
		avgGain = (avgGain * (period - 1.0) + gain) / period;
		avgLoss = (avgLoss * (period - 1.0) + loss) / period;
		value = resolveRsi(avgGain, avgLoss);
		return value;
	}

	public boolean isReady() {
		return count >= period;
	}

	public double value() {
		return value;
	}

	private double resolveRsi(double avgGainValue, double avgLossValue) {
		if (avgLossValue == 0.0) {
			return 100.0;
		}
		double rs = avgGainValue / avgLossValue;
		return 100.0 - (100.0 / (1.0 + rs));
	}
}
