package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class StopLossAuditService {
	private static final Path DEFAULT_BASE_DIR = Paths.get("out", "stoploss_audit");
	private static final double STOP_LOSS_TOLERANCE = 0.0002;
	private static final double STOP_PRICE_TOLERANCE_PCT = 0.0005;
	private static final int MAX_FAILURE_SAMPLES = 50;

	private final CtiLbStrategy strategy;
	private final StopLossAuditWriter writer;
	private final TradeTruthRecorder tradeTruthRecorder;
	private final Map<String, Integer> mismatchCounts = new ConcurrentHashMap<>();
	private final Map<String, Integer> symbolMismatchCounts = new ConcurrentHashMap<>();
	private int stopLossTotal;
	private int stopLossPass;
	private BigDecimal deltaPnlSum = BigDecimal.ZERO;

	public StopLossAuditService(CtiLbStrategy strategy, ObjectMapper objectMapper) {
		this.strategy = strategy;
		this.writer = new StopLossAuditWriter(DEFAULT_BASE_DIR, objectMapper);
		this.tradeTruthRecorder = new TradeTruthRecorder();
	}

	public void recordOrderUpdate(OrderTracker.OrderUpdate update) {
		writer.recordOrderUpdate(update);
		TradeTruthRecorder.ExitContext exitContext = null;
		if (update != null && update.reduceOnly() && "TRADE".equals(update.execType())) {
			exitContext = strategy.peekExitContext(update.symbol());
		}
		tradeTruthRecorder.onOrderUpdate(update, exitContext, this::recordTradeTruth);
	}

	public void recordTrailingUpdate(String symbol, String reason, long eventTime,
			Double oldStopPrice, Double newStopPrice, Double trailWidth, Double profitStop,
			Double hardStop, Double recoveryStop) {
		writer.recordTrailingUpdate(symbol, reason, eventTime, oldStopPrice, newStopPrice,
				trailWidth, profitStop, hardStop, recoveryStop);
	}

	private void recordTradeTruth(TradeTruthRecorder.TradeTruth tradeTruth) {
		if (tradeTruth == null) {
			return;
		}
		String mismatchReason = classifyMismatch(tradeTruth);
		if (mismatchReason != null) {
			mismatchCounts.merge(mismatchReason, 1, Integer::sum);
			symbolMismatchCounts.merge(tradeTruth.symbol(), 1, Integer::sum);
		}
		writer.recordTradeTruth(new StopLossAuditWriter.TradeTruthRecord(
				tradeTruth.symbol(),
				tradeTruth.side(),
				tradeTruth.entryTime(),
				tradeTruth.exitTime(),
				tradeTruth.entryQty(),
				tradeTruth.exitQty(),
				tradeTruth.entryAvgPrice(),
				tradeTruth.exitAvgPrice(),
				tradeTruth.exitReason(),
				tradeTruth.expectedSlPct(),
				tradeTruth.expectedStopPrice(),
				tradeTruth.stopPriceUsed(),
				tradeTruth.actualMovePct(),
				tradeTruth.realizedPnl(),
				tradeTruth.recomputedPnl(),
				tradeTruth.deltaPnl(),
				mismatchReason == null ? "OK" : mismatchReason));
		updateSummary(tradeTruth, mismatchReason);
	}

	private void updateSummary(TradeTruthRecorder.TradeTruth tradeTruth, String mismatchReason) {
		if (tradeTruth.isStopLossExit()) {
			stopLossTotal += 1;
			if (tradeTruth.isStopLossDistanceOk(STOP_LOSS_TOLERANCE)) {
				stopLossPass += 1;
			}
		}
		deltaPnlSum = deltaPnlSum.add(tradeTruth.deltaPnlValue());
		ObjectNode summary = buildSummary();
		writer.recordSummary(summary);
	}

	private ObjectNode buildSummary() {
		ObjectNode summary = new ObjectMapper().createObjectNode();
		summary.put("stopLossTotal", stopLossTotal);
		summary.put("stopLossPass", stopLossPass);
		double passRate = stopLossTotal == 0 ? 1.0 : stopLossPass / (double) stopLossTotal;
		summary.put("stopLossPassRate", passRate);
		BigDecimal avgDelta = stopLossTotal == 0 ? BigDecimal.ZERO
				: deltaPnlSum.divide(BigDecimal.valueOf(Math.max(1, stopLossTotal)), MathContext.DECIMAL64);
		summary.put("avgDeltaPnl", avgDelta.stripTrailingZeros().toPlainString());
		ObjectNode mismatchNode = summary.putObject("mismatchCounts");
		mismatchCounts.forEach(mismatchNode::put);
		ArrayNode topSymbols = summary.putArray("topMismatchSymbols");
		symbolMismatchCounts.entrySet().stream()
				.sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
				.limit(10)
				.forEach(entry -> {
					ObjectNode node = new ObjectMapper().createObjectNode();
					node.put("symbol", entry.getKey());
					node.put("count", entry.getValue());
					topSymbols.add(node);
				});
		return summary;
	}

	private String classifyMismatch(TradeTruthRecorder.TradeTruth tradeTruth) {
		if (tradeTruth == null) {
			return null;
		}
		if (tradeTruth.isStopLossExit()) {
			if (!tradeTruth.isStopLossDistanceOk(STOP_LOSS_TOLERANCE)) {
				return "STOP_LOSS_DISTANCE_MISMATCH";
			}
			if (!tradeTruth.isStopPriceConsistent(STOP_PRICE_TOLERANCE_PCT)) {
				return "STOP_PRICE_MISMATCH";
			}
		}
		if (!tradeTruth.isDeltaPnlOk(BigDecimal.valueOf(0.01))) {
			return "PNL_MISMATCH";
		}
		return null;
	}

	static class TradeTruthRecorder {
		private final Map<String, TradeAccumulator> openTrades = new ConcurrentHashMap<>();

		void onOrderUpdate(OrderTracker.OrderUpdate update, ExitContext exitContext,
				java.util.function.Consumer<TradeTruth> consumer) {
			if (update == null || update.symbol() == null || update.execType() == null) {
				return;
			}
			if (!"TRADE".equals(update.execType())) {
				return;
			}
			BigDecimal avgPrice = parseDecimal(update.avgPrice());
			BigDecimal executedQty = parseDecimal(update.executedQty());
			if (avgPrice == null || executedQty == null || executedQty.signum() <= 0) {
				return;
			}
			if (!update.reduceOnly()) {
				TradeAccumulator accumulator = openTrades.computeIfAbsent(update.symbol(), ignored -> new TradeAccumulator());
				accumulator.entryAvgPrice = avgPrice;
				accumulator.entryQty = executedQty;
				accumulator.entryTime = update.eventTime();
				accumulator.side = update.side();
				return;
			}
			TradeAccumulator entry = openTrades.remove(update.symbol());
			if (entry == null) {
				return;
			}
			TradeTruth truth = TradeTruth.from(entry, update, exitContext);
			consumer.accept(truth);
		}

		private static BigDecimal parseDecimal(String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			try {
				return new BigDecimal(value);
			} catch (NumberFormatException ex) {
				return null;
			}
		}

		static class TradeAccumulator {
			private BigDecimal entryAvgPrice;
			private BigDecimal entryQty;
			private long entryTime;
			private String side;
		}

		static class ExitContext {
			private final String exitReason;
			private final Double expectedSlPct;
			private final Double expectedStopPrice;
			private final Double stopPriceUsed;

			ExitContext(String exitReason, Double expectedSlPct, Double expectedStopPrice, Double stopPriceUsed) {
				this.exitReason = exitReason;
				this.expectedSlPct = expectedSlPct;
				this.expectedStopPrice = expectedStopPrice;
				this.stopPriceUsed = stopPriceUsed;
			}
		}

		record TradeTruth(
				String symbol,
				String side,
				long entryTime,
				long exitTime,
				String entryQty,
				String exitQty,
				String entryAvgPrice,
				String exitAvgPrice,
				String exitReason,
				String expectedSlPct,
				String expectedStopPrice,
				String stopPriceUsed,
				String actualMovePct,
				String realizedPnl,
				String recomputedPnl,
				String deltaPnl) {

			static TradeTruth from(TradeAccumulator entry, OrderTracker.OrderUpdate exitUpdate, ExitContext exitContext) {
				BigDecimal entryAvg = entry.entryAvgPrice;
				BigDecimal entryQty = entry.entryQty;
				BigDecimal exitAvg = parseDecimal(exitUpdate.avgPrice());
				BigDecimal exitQty = parseDecimal(exitUpdate.executedQty());
				String exitReason = exitContext == null ? "NA" : safe(exitContext.exitReason);
				Double expectedSlPct = exitContext == null ? null : exitContext.expectedSlPct;
				Double expectedStopPrice = exitContext == null ? null : exitContext.expectedStopPrice;
				Double stopPriceUsed = resolveStopPriceUsed(exitUpdate.stopPrice(), exitContext);
				BigDecimal pnl = calculatePnl(entryAvg, exitAvg, exitQty, entry.side);
				BigDecimal delta = BigDecimal.ZERO;
				double actualMove = calculateMovePct(entryAvg, exitAvg, entry.side);
				return new TradeTruth(
						exitUpdate.symbol(),
						resolveSide(entry.side),
						entry.entryTime,
						exitUpdate.eventTime(),
						format(entryQty),
						format(exitQty),
						format(entryAvg),
						format(exitAvg),
						exitReason,
						expectedSlPct == null ? "NA" : expectedSlPct.toString(),
						expectedStopPrice == null ? "NA" : format(expectedStopPrice),
						stopPriceUsed == null ? "NA" : format(stopPriceUsed),
						format(actualMove),
						format(pnl),
						format(pnl),
						format(delta));
			}

			boolean isStopLossExit() {
				return exitReason != null && (exitReason.contains("STOP_LOSS") || exitReason.contains("ROI_STOP_LOSS"));
			}

			boolean isStopLossDistanceOk(double tolerance) {
				if (!isStopLossExit()) {
					return true;
				}
				double expected = parseDouble(expectedSlPct);
				double actual = parseDouble(actualMovePct);
				return Double.isFinite(expected) && Double.isFinite(actual)
						&& Math.abs(actual - expected) <= tolerance;
			}

			boolean isStopPriceConsistent(double tolerancePct) {
				if (!isStopLossExit()) {
					return true;
				}
				double expected = parseDouble(expectedStopPrice);
				double used = parseDouble(stopPriceUsed);
				double entry = parseDouble(entryAvgPrice);
				if (!Double.isFinite(expected) || !Double.isFinite(used) || !Double.isFinite(entry) || entry <= 0) {
					return false;
				}
				return Math.abs(used - expected) / entry <= tolerancePct;
			}

			boolean isDeltaPnlOk(BigDecimal tolerance) {
				BigDecimal deltaValue = deltaPnlValue();
				return deltaValue.abs().compareTo(tolerance) <= 0;
			}

			BigDecimal deltaPnlValue() {
				try {
					return new BigDecimal(deltaPnl);
				} catch (NumberFormatException ex) {
					return BigDecimal.ZERO;
				}
			}

			private static double calculateMovePct(BigDecimal entry, BigDecimal exit, String side) {
				if (entry == null || exit == null || entry.signum() <= 0) {
					return Double.NaN;
				}
				double entryVal = entry.doubleValue();
				double exitVal = exit.doubleValue();
				return "SHORT".equals(side)
						? (exitVal - entryVal) / entryVal
						: (entryVal - exitVal) / entryVal;
			}

			private static BigDecimal calculatePnl(BigDecimal entry, BigDecimal exit, BigDecimal qty, String side) {
				if (entry == null || exit == null || qty == null || qty.signum() <= 0) {
					return BigDecimal.ZERO;
				}
				BigDecimal diff = exit.subtract(entry);
				if ("SHORT".equals(side)) {
					diff = entry.subtract(exit);
				}
				return diff.multiply(qty);
			}

			private static Double resolveStopPriceUsed(String stopPrice, ExitContext context) {
				BigDecimal parsed = parseDecimal(stopPrice);
				if (parsed != null) {
					return parsed.doubleValue();
				}
				return context == null ? null : context.stopPriceUsed;
			}

			private static String resolveSide(String side) {
				if ("SELL".equalsIgnoreCase(side)) {
					return "SHORT";
				}
				if ("BUY".equalsIgnoreCase(side)) {
					return "LONG";
				}
				return "NA";
			}

			private static String safe(String value) {
				return value == null ? "NA" : value;
			}

			private static String format(BigDecimal value) {
				return value == null ? "NA" : value.stripTrailingZeros().toPlainString();
			}

			private static String format(double value) {
				if (!Double.isFinite(value)) {
					return "NA";
				}
				return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
			}

			private static double parseDouble(String value) {
				if (value == null || value.isBlank() || "NA".equals(value)) {
					return Double.NaN;
				}
				try {
					return Double.parseDouble(value);
				} catch (NumberFormatException ex) {
					return Double.NaN;
				}
			}
		}
	}
}
