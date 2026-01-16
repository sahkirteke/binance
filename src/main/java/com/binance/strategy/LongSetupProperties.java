package com.binance.strategy;

public record LongSetupProperties(
		Setup1 setup1,
		Setup2 setup2,
		Setup3 setup3,
		Setup4 setup4,
		Setup5 setup5) {

	public record Setup1(
			double bbWidthMin,
			double bbWidthMax,
			double volMin,
			double volMax) {
	}

	public record Setup2(
			double bbWidthMin,
			double bbWidthMax,
			double volMin,
			double volMax) {
	}

	public record Setup3(
			double bbWidthMin,
			double bbWidthMax,
			double ema20DistMin,
			double ema20DistMax) {
	}

	public record Setup4(
			double bbWidthMin,
			double bbWidthMax,
			double ema20DistMin,
			double ema20DistMax) {
	}

	public record Setup5(
			double rsiMin,
			double rsiMax,
			double ema20DistMin) {
	}
}
