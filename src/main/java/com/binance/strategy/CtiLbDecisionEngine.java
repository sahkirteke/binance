package com.binance.strategy;

import java.math.BigDecimal;
import java.util.List;

import com.binance.exchange.dto.OrderResponse;

public final class CtiLbDecisionEngine {

	private static final long FLIP_WINDOW_MS = 300_000L;

	private CtiLbDecisionEngine() {
	}

	public static ExitDecision evaluateExit(CtiDirection side, BigDecimal entryPrice, double currentPrice,
			BigDecimal stopLossBps, BigDecimal takeProfitBps) {
		if (side == null || entryPrice == null || entryPrice.signum() <= 0 || currentPrice <= 0) {
			return new ExitDecision(false, null, 0.0);
		}
		double entry = entryPrice.doubleValue();
		double pnlBps = estimatePnlBps(side, entry, currentPrice);
		double stopLoss = toFraction(stopLossBps);
		double takeProfit = toFraction(takeProfitBps);

		if (stopLoss > 0) {
			if (side == CtiDirection.LONG && currentPrice <= entry * (1.0 - stopLoss)) {
				return new ExitDecision(true, "EXIT_STOP_LOSS", pnlBps);
			}
			if (side == CtiDirection.SHORT && currentPrice >= entry * (1.0 + stopLoss)) {
				return new ExitDecision(true, "EXIT_STOP_LOSS", pnlBps);
			}
		}
		if (takeProfit > 0) {
			if (side == CtiDirection.LONG && currentPrice >= entry * (1.0 + takeProfit)) {
				return new ExitDecision(true, "EXIT_TAKE_PROFIT", pnlBps);
			}
			if (side == CtiDirection.SHORT && currentPrice <= entry * (1.0 - takeProfit)) {
				return new ExitDecision(true, "EXIT_TAKE_PROFIT", pnlBps);
			}
		}
		return new ExitDecision(false, null, pnlBps);
	}

	public static BigDecimal resolveTargetNotional(BigDecimal notionalUsdt, BigDecimal maxPositionUsdt,
			BigDecimal defaultNotionalUsdt) {
		BigDecimal effectiveNotional = (notionalUsdt == null || notionalUsdt.signum() <= 0)
				? defaultNotionalUsdt
				: notionalUsdt;
		BigDecimal effectiveMax = (maxPositionUsdt == null || maxPositionUsdt.signum() <= 0)
				? defaultNotionalUsdt
				: maxPositionUsdt;
		if (maxPositionUsdt != null && maxPositionUsdt.signum() <= 0) {
			return effectiveNotional;
		}
		return effectiveNotional.min(effectiveMax);
	}

	public static BigDecimal resolveQuantity(BigDecimal targetNotional, double price, BigDecimal quantityStep) {
		if (targetNotional == null || targetNotional.signum() <= 0 || price <= 0) {
			return null;
		}
		BigDecimal rawQty = targetNotional.divide(BigDecimal.valueOf(price), java.math.MathContext.DECIMAL64);
		return floorToStep(rawQty, quantityStep);
	}

	public static boolean shouldProceedAfterClose(OrderResponse response) {
		return response != null && response.orderId() != null
				&& (response.status() == null || !"REJECTED".equalsIgnoreCase(response.status()));
	}

	public static int effectiveConfirmBars(int configured) {
		return configured > 0 ? configured : 1;
	}

	public static String resolveExitDecisionBlockReason() {
		return "OK_EXIT";
	}

	public static BlockDecision evaluateEntryBlocks(BlockInput input) {
		if (input == null) {
			return new BlockDecision(false, null);
		}
		if (input.isFlip() && input.minHoldMs() > 0 && input.entryTimeMs() != null
				&& input.nowMs() - input.entryTimeMs() < input.minHoldMs()) {
			return new BlockDecision(true, "BLOCK_MIN_HOLD");
		}
		if (input.flipCooldownMs() > 0 && input.lastFlipTimeMs() != null
				&& input.nowMs() - input.lastFlipTimeMs() < input.flipCooldownMs()) {
			return new BlockDecision(true, "BLOCK_COOLDOWN");
		}
		if (input.maxFlipsPer5Min() > 0
				&& countRecentFlips(input.flipTimes(), input.nowMs()) >= input.maxFlipsPer5Min()) {
			return new BlockDecision(true, "BLOCK_MAX_FLIPS");
		}
		if (input.isFlip() && input.minBfrDelta() != null && input.minBfrDelta().signum() > 0) {
			double delta = Math.abs(input.bfrValue() - input.bfrPrev());
			if (delta < input.minBfrDelta().doubleValue()) {
				return new BlockDecision(true, "BLOCK_MIN_BFR_DELTA");
			}
		}
		if (input.isFlip() && input.minPriceMoveBps() != null && input.minPriceMoveBps().signum() > 0
				&& input.lastFlipPrice() != null && input.lastFlipPrice().signum() > 0) {
			double lastPrice = input.lastFlipPrice().doubleValue();
			double moveBps = Math.abs((input.currentPrice() - lastPrice) / lastPrice) * 10000.0;
			if (moveBps < input.minPriceMoveBps().doubleValue()) {
				return new BlockDecision(true, "BLOCK_MIN_PRICE_MOVE");
			}
		}
		return new BlockDecision(false, null);
	}

	public static String resolveHoldReason(boolean hasSignal, boolean confirmationMet) {
		if (hasSignal && !confirmationMet) {
			return "BLOCK_CONFIRMATION_PENDING";
		}
		return "HOLD_NO_CHANGE";
	}

	private static double estimatePnlBps(CtiDirection side, double entry, double currentPrice) {
		double delta = (currentPrice - entry) / entry;
		double signedDelta = side == CtiDirection.SHORT ? -delta : delta;
		return signedDelta * 10000.0;
	}

	private static double toFraction(BigDecimal bps) {
		if (bps == null) {
			return 0.0;
		}
		return bps.doubleValue() / 10000.0;
	}

	private static int countRecentFlips(List<Long> flipTimes, long nowMs) {
		if (flipTimes == null || flipTimes.isEmpty()) {
			return 0;
		}
		long cutoff = nowMs - FLIP_WINDOW_MS;
		int count = 0;
		for (Long flipTime : flipTimes) {
			if (flipTime != null && flipTime >= cutoff) {
				count++;
			}
		}
		return count;
	}

	private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
		if (value == null || step == null || step.signum() <= 0) {
			return value;
		}
		BigDecimal ratio = value.divide(step, 0, java.math.RoundingMode.DOWN);
		return ratio.multiply(step, java.math.MathContext.DECIMAL64);
	}

	public record ExitDecision(boolean exit, String reason, double pnlBps) {
	}

	public record BlockDecision(boolean blocked, String reason) {
	}

	public record BlockInput(
			long nowMs,
			boolean isFlip,
			Long entryTimeMs,
			Long lastFlipTimeMs,
			List<Long> flipTimes,
			long minHoldMs,
			long flipCooldownMs,
			int maxFlipsPer5Min,
			double currentPrice,
			double bfrValue,
			double bfrPrev,
			BigDecimal minBfrDelta,
			BigDecimal minPriceMoveBps,
			BigDecimal lastFlipPrice) {
	}
}
