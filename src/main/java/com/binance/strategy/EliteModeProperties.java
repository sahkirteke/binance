package com.binance.strategy;

import org.springframework.boot.context.properties.bind.Name;

public record EliteModeProperties(
		boolean enabled,
		@Name("long") LongEliteProperties longMode,
		@Name("short") ShortEliteProperties shortMode) {

	public record LongEliteProperties(
			boolean enabled,
			String applyRegime,
			String applyOnlyIfMatchedSetup,
			double bwRatioMax,
			double atrRatioMax,
			double volRatioOfEmaMax,
			double ema20DistPctMax,
			double bbPercentBMax) {
	}

	public record ShortEliteProperties(
			boolean enabled,
			String allowedRegime,
			boolean requireRawEqualsActive,
			ShortEliteVeto veto,
			ShortEliteSetup s1,
			ShortEliteSetup s2) {
	}

	public record ShortEliteVeto(
			double bbPercentBMinExclusive,
			boolean requireBbOutsideFalse,
			double ema20DistPctMax) {
	}

	public record ShortEliteSetup(
			double pbMin,
			double pbMax,
			double bwRatioMin,
			double bwRatioMax,
			double volRatioOfEmaMax,
			double macdRatioMin) {
	}
}
