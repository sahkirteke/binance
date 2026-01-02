package com.binance.strategy;

import java.math.BigDecimal;

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
		@Positive int depthLimit,
		@Positive BigDecimal marketQuantity,
		int leverage,
		String positionSide,
		boolean enableOrders,
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
		BigDecimal flipSpreadMaxBps,
		int maxTradesPer5Min,
		long hardTradeCooldownMs) {
}
