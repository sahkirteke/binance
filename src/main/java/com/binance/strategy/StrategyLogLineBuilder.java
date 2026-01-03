package com.binance.strategy;

public final class StrategyLogLineBuilder {

	private StrategyLogLineBuilder() {
	}

	public static String buildDecisionLine(StrategyLogV1.DecisionLogDto dto) {
		return StrategyLogV1.buildDecisionLine(dto);
	}

	public static String buildConfirmHitLine(StrategyLogV1.ConfirmHitLogDto dto) {
		return StrategyLogV1.buildConfirmHitLine(dto);
	}

	public static String buildMissedMoveLine(StrategyLogV1.MissedMoveLogDto dto) {
		return StrategyLogV1.buildMissedMoveLine(dto);
	}

	public static String buildFlipLine(StrategyLogV1.FlipLogDto dto) {
		return StrategyLogV1.buildFlipLine(dto);
	}

	public static String buildSummaryLine(StrategyLogV1.SummaryLogDto dto) {
		return StrategyLogV1.buildSummaryLine(dto);
	}
}
