package com.binance.strategy;

public record ShortSetupProperties(
		S1 s1,
		S2 s2,
		S3 s3,
		S4 s4,
		S5 s5,
		S6 s6) {

	public record S1(
			double bbWidthMin,
			double bbWidthMax,
			double ema20DistMin,
			double ema20DistMax) {
	}

	public record S2(
			double rsiMin,
			double rsiMax,
			double ema20DistMin,
			double ema20DistMax) {
	}

	public record S3(
			double bbWidthMin,
			double bbWidthMax,
			double rsiMin,
			double rsiMax) {
	}

	public record S4(
			double bbPercentBMin,
			double bbPercentBMax,
			double macdDeltaMin,
			double macdDeltaMax) {
	}

	public record S5(
			double adxMin,
			double adxMax,
			double macdDeltaMinExclusive) {
	}

	public record S6(
			double volRatioMin,
			double bbWidthMin,
			double ema20DistMin,
			double adxMin) {
	}
}
