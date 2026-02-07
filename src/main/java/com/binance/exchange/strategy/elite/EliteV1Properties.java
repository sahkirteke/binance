package com.binance.exchange.strategy.elite;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "elite-v1")
public record EliteV1Properties(
		@NotNull Mode mode,
		@NotNull String zoneId,
		@NotEmpty List<String> symbols,
		double paperNotionalUsd,
		@Positive int maxOpenPositionsGlobal,
		@Positive int maxEntriesPerSymbolPerDay,
		double tpPct,
		double slPct,
		@Positive int timeStopMinutes,
		@NotNull ConflictResolution conflictResolution,
		@Positive int warmupMin5mBars,
		@NotNull RegimeConfig regime,
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

	public enum Regime {
		CHOP,
		TREND
	}

	public record RegimeConfig(
			double chopBwRatioMax,
			double chopMacdRatioMax,
			@Positive int debounceBars,
			@Positive int cooldownBars) {
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
			double chopMaxBwRatio,
			double minVolRatioOfEma,
			double maxAtrRatio,
			double maxTickPctAllowed,
			boolean requireStableRegime) {
	}

	public record ShortConfig(
			boolean enabled,
			@NotNull Regime onlyRegime,
			@NotNull ShortVeto veto,
			@NotNull ShortEliteMomentum eliteMomentum) {
	}

	public record ShortVeto(
			double bbPercentBMinExclusive,
			double ema20DistPctMax,
			boolean requireBbOutsideFalse) {
	}

	public record ShortEliteMomentum(
			double pbMin,
			double pbMax,
			double bwRatioMin,
			double bwRatioMax,
			double volRatioOfEmaMin,
			double volRatioOfEmaMax,
			double macdRatioMin,
			boolean requireCloseBelowEma20,
			boolean requireEma20SlopeDown,
			boolean requireMacdDeltaNegative) {
	}
}
