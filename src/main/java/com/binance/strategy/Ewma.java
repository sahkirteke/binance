package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;

public class Ewma {
	private final BigDecimal alpha;
	private BigDecimal value;

	public Ewma(BigDecimal alpha) {
		this.alpha = alpha;
	}

	public synchronized BigDecimal update(BigDecimal sample) {
		if (sample == null) {
			return value;
		}
		if (value == null) {
			value = sample;
		} else {
			BigDecimal oneMinus = BigDecimal.ONE.subtract(alpha, MathContext.DECIMAL64);
			value = sample.multiply(alpha, MathContext.DECIMAL64)
					.add(value.multiply(oneMinus, MathContext.DECIMAL64), MathContext.DECIMAL64);
		}
		return value;
	}

	public synchronized BigDecimal value() {
		return value;
	}
}
