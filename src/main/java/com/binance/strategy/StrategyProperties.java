package com.binance.strategy;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "strategy")
public record StrategyProperties(
		@NotNull StrategyType active,
		@NotBlank String referenceSymbol,
		@NotBlank String tradeSymbol,
		List<String> tradeSymbols,
		@Positive int depthLimit,
		@Positive BigDecimal marketQuantity,
		int leverage,
		String positionSide,
		boolean enableOrders,
		boolean startupTestOrderEnabled,
		@Positive int pollIntervalMs,
		int tickIntervalMs,
		int rollingWindowMs,
		int depthLevels,
		BigDecimal weightLambda,
		BigDecimal obiEntry,
		BigDecimal obiEntryLong,
		BigDecimal obiEntryShort,
		BigDecimal obiExit,
		BigDecimal toiMin,
		BigDecimal toiMinLong,
		BigDecimal toiMinShort,
		BigDecimal cancelMax,
		BigDecimal cancelMaxLong,
		BigDecimal cancelMaxShort,
		int persistMs,
		long exitPersistMs,
		int cooldownMs,
		BigDecimal maxSpreadBps,
		BigDecimal positionNotionalUsdt,
		BigDecimal stopLossBps,
		BigDecimal takeProfitBps,
		BigDecimal quantityStep,
		BigDecimal priceTick,
		BigDecimal maxDailyLossUsdt,
		int maxConsecutiveLosses,
		BigDecimal maxPositionUsdt,
		boolean flipEnabled,
		int minHoldMs,
		int flipCooldownMs,
		BigDecimal strongObi,
		BigDecimal strongToi,
		int maxFlipsPer5Min,
		BigDecimal minBfrDelta,
		BigDecimal minPriceMoveBps,
		BigDecimal flipSpreadMaxBps,
		int maxTradesPer5Min,
		long hardTradeCooldownMs,
		boolean enableTieBreakBias,
		boolean startupSmokeTestEnabled,
		long filterRefreshTtlMs,
		int armingWindowMinutes,
		int lateLimitMinutes,
		BigDecimal movedDirPctLateGate,
		BigDecimal chaseMaxMovePct,
		boolean enableFlipOnOppositeExit,
		long oppositeExitFlipCooldownMs,
		double rsiLongMin,
		double rsiLongMax,
		double rsiShortMin,
		double rsiShortMax,
		double volSpikeMult,
		double atrCapMult,
		int entryQualityMin,
		boolean extremeRsiBlock,
		double extremeRsiHigh,
		double extremeRsiLow,
		double extremeAtrBlockMult,
		double trailActivatePct,
		double trailRetracePct,
		double trendHoldMinProfitPct,
		int continuationQualityMin,
		int continuationMinBars,
		double continuationMaxRetracePct,
		int flipFastQuality,
		boolean trendHoldEnabled,
		double trendHoldMinAdx,
		double ema20HoldBufferPct,
		int flipQualityMin,
		int trendHoldMaxBars,
		boolean tpTrailingEnabled,
		double tpStartPct,
		double tpTrailPct,
		boolean pnlTrailEnabled,
		long pnlTrailPositionSyncMs,
		double pnlTrailProfitArm,
		double pnlTrailLossArm,
		double pnlTrailBonusDelta,
		double pnlTrailProfitTrailBase,
		double pnlTrailProfitTrailBonus,
		double pnlTrailLossHardExtra,
		double pnlTrailLossRecoveryTrail,
		int pnlTrailExitConfirmTicks,
		boolean roiExitEnabled,
		double roiTakeProfitPct,
		double roiStopLossPct) {


	public List<String> resolvedTradeSymbols() {
		if (tradeSymbols != null && !tradeSymbols.isEmpty()) {
			return tradeSymbols;
		}
		return List.of(tradeSymbol);
	}
}
