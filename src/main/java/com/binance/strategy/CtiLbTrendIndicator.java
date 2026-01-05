package com.binance.strategy;

public class CtiLbTrendIndicator implements TrendIndicator {

	private static final double SMOOTHING_PERIOD = 21.0;
	private static final double CONSTANT_D = 0.4;

	double i1;
	double i2;
	double i3;
	double i4;
	double i5;
	double i6;
	double bfrPrev;
	Trend lastTrend = Trend.LONG;
	boolean initialized;

	static int period() {
		return (int) SMOOTHING_PERIOD;
	}

	@Override
	public TrendSignal onClosedCandle(double close, long closeTime) {
		double di = (SMOOTHING_PERIOD - 1.0) / 2.0 + 1.0;
		double c1 = 2.0 / (di + 1.0);
		double c2 = 1.0 - c1;
		double c3 = 3.0 * (CONSTANT_D * CONSTANT_D + CONSTANT_D * CONSTANT_D * CONSTANT_D);
		double c4 = -3.0 * (2.0 * CONSTANT_D * CONSTANT_D + CONSTANT_D + CONSTANT_D * CONSTANT_D * CONSTANT_D);
		double c5 = 3.0 * CONSTANT_D + 1.0 + CONSTANT_D * CONSTANT_D * CONSTANT_D + 3.0 * CONSTANT_D * CONSTANT_D;

		double prevI1 = i1;
		double prevI2 = i2;
		double prevI3 = i3;
		double prevI4 = i4;
		double prevI5 = i5;
		double prevI6 = i6;

		i1 = c1 * close + c2 * prevI1;
		i2 = c1 * i1 + c2 * prevI2;
		i3 = c1 * i2 + c2 * prevI3;
		i4 = c1 * i3 + c2 * prevI4;
		i5 = c1 * i4 + c2 * prevI5;
		i6 = c1 * i5 + c2 * prevI6;

		double bfr = -(CONSTANT_D * CONSTANT_D * CONSTANT_D) * i6 + c3 * i5 + c4 * i4 + c5 * i3;

		Trend prevTrend = lastTrend;
		Trend trend = lastTrend;
		if (bfr > bfrPrev) {
			trend = Trend.LONG;
		} else if (bfr < bfrPrev) {
			trend = Trend.SHORT;
		}
		boolean changed = initialized && trend != prevTrend;

		double priorBfr = bfrPrev;
		bfrPrev = bfr;
		lastTrend = trend;
		initialized = true;

		return new TrendSignal(trend, changed, bfr, priorBfr, closeTime);
	}
}
