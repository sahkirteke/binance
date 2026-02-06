package com.binance.exchange.strategy.elite;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "eliteV1")
public record EliteV1Properties(
		@NotNull Mode mode,
		@NotBlank String timeframe,
		@NotBlank String evalEvery,
		@NotEmpty List<String> symbols,
		@Positive int maxOpenPositions,
		@Positive int maxEntriesPerSymbolPerDay,
		double tpPct,
		double slPct,
		Double paperNotional,
		@Positive int timeStopMinutes,
		@NotNull ConflictResolution conflictResolution,
		@NotNull InputsNotReadyPolicy inputsNotReadyPolicy,
		@Name("long") @NotNull LongConfig longConfig,
		@Name("short") @NotNull ShortConfig shortConfig) {

	public enum Mode {
		PAPER,
		LIVE
	}

	public enum ConflictResolution {
		SL_FIRST,
		TP_FIRST
	}

	public enum InputsNotReadyPolicy {
		NO_TRADE
	}

	public enum Regime {
		CHOP,
		TREND
	}

	public record LongConfig(
			boolean enabled,
			@NotNull Regime onlyRegime,
			double rsiMin,
			double rsiMax,
			double ema20DistMin,
			double bbPercentBMax,
			boolean enableSetup5SafetyGate,
			@NotNull Setup5SafetyGate setup5) {
	}

	public record Setup5SafetyGate(
			double maxBbWidth,
			double maxVolRatio,
			double chopMaxBwRatio) {
	}

	public record ShortConfig(
			boolean enabled,
			@NotNull Regime onlyRegime,
			@NotNull ShortVeto veto,
			@NotNull ShortEliteBand elite1,
			@NotNull ShortEliteBand elite2) {
	}

	public record ShortVeto(
			double bbPercentBMinExclusive,
			double ema20DistPctMax,
			boolean requireBbOutsideFalse) {
	}

	public record ShortEliteBand(
			double pbMin,
			double pbMax,
			double bwRatioMin,
			double bwRatioMax,
			double volRatioOfEmaMax,
			double macdRatioMin) {
	}
}
