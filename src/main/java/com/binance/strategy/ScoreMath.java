package com.binance.strategy;

public final class ScoreMath {

	private ScoreMath() {
	}

	public static int sign(double value) {
		if (value > 0) {
			return 1;
		}
		if (value < 0) {
			return -1;
		}
		return 0;
	}

	public static double abs(double value) {
		return Math.abs(value);
	}

	public static double min(double first, double second) {
		return Math.min(first, second);
	}

	public static double max(double first, double second) {
		return Math.max(first, second);
	}

	public static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
