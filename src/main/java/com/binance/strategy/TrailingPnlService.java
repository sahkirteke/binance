package com.binance.strategy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;

@Component
public class TrailingPnlService {

	private static final Logger LOGGER = LoggerFactory.getLogger(TrailingPnlService.class);
	private static final Path SIGNAL_OUTPUT_DIR = Paths.get("signals");

	private final StrategyProperties strategyProperties;
	private final BinanceFuturesOrderClient orderClient;
	private final CtiLbStrategy ctiLbStrategy;
	private final ObjectMapper objectMapper;
	private final Map<String, PositionSnapshot> positionMap = new ConcurrentHashMap<>();
	private final Map<String, TrailingState> trailingMap = new ConcurrentHashMap<>();
	private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
	private final int exitConfirmTicksDefault = 2;

	public TrailingPnlService(StrategyProperties strategyProperties,
			BinanceFuturesOrderClient orderClient,
			CtiLbStrategy ctiLbStrategy,
			ObjectMapper objectMapper) {
		this.strategyProperties = strategyProperties;
		this.orderClient = orderClient;
		this.ctiLbStrategy = ctiLbStrategy;
		this.objectMapper = objectMapper;
		startPositionSync();
	}

	// TrailingPnlService.java içinde onMarkPrice metodunu güncelleyin

	public void onMarkPrice(String symbol, double markPrice) {
		boolean roiEnabled = strategyProperties.roiExitEnabled()
				&& strategyProperties.roiTakeProfitPct() > 0
				&& strategyProperties.roiStopLossPct() > 0;
		if (!strategyProperties.pnlTrailEnabled() && !roiEnabled) {
			return;
		}

		PositionSnapshot snapshot = positionMap.get(symbol);
		if (snapshot == null || snapshot.qty() == 0 || snapshot.entryPrice() <= 0) {
			CtiLbStrategy.EntryState entry = ctiLbStrategy.peekEntryStateForTrailing(symbol);
			CtiLbStrategy.PositionState posState = ctiLbStrategy.peekPositionStateForTrailing(symbol);
			if (entry != null && posState != null && posState != CtiLbStrategy.PositionState.NONE
					&& entry.entryPrice() != null && entry.quantity() != null
					&& entry.quantity().signum() != 0 && entry.entryPrice().signum() > 0) {
				onPositionUpdate(symbol,
						entry.entryPrice().doubleValue(),
						entry.quantity().doubleValue(),
						entry.side().name(),
						leverageDefault());
				snapshot = positionMap.get(symbol);
			}
			if (snapshot == null || snapshot.qty() == 0 || snapshot.entryPrice() <= 0) {
				resetState(symbol);
				return;
			}
		}

		double pnlPct = calculatePnlPct(snapshot, markPrice);
		TrailingState state = trailingMap.computeIfAbsent(symbol, ignored -> new TrailingState());

		synchronized (state) {
			state.lastPnlPct = pnlPct;

			// ✅ CRITICAL: Position age check (10 second grace period)
			long positionAgeMs = System.currentTimeMillis() - snapshot.lastUpdateTs();
			boolean isNewPosition = positionAgeMs < 10000;

			// ✅ CRITICAL: Don't process if already closing
			if (state.closingFlag.get()) {
				// Allow retry only after 15 seconds AND if there was a network error
				long timeSinceClosing = System.currentTimeMillis() - state.closingSinceMs;
				if (timeSinceClosing > 15000 && state.closingAttempts < 3) {
					LOGGER.warn("EVENT=ROI_EXIT_RETRY symbol={} pnlPct={} attempts={} timeSinceMs={}",
							symbol, String.format("%.2f", pnlPct), state.closingAttempts, timeSinceClosing);
					state.closingFlag.set(false);
				} else {
					// Still closing, skip processing
					return;
				}
			}

			// ✅ ROI Hard Exits (only after grace period)
			if (roiEnabled && !isNewPosition) {
				double tp = strategyProperties.roiTakeProfitPct();
				double sl = strategyProperties.roiStopLossPct();

				if (pnlPct >= tp) {
					state.closingFlag.set(true);
					state.closingSinceMs = System.currentTimeMillis();
					state.closingAttempts++;
					String reason = "ROI_TAKE_PROFIT";
					logTrailEvent("ROI_EXIT_SIGNAL", symbol, snapshot, markPrice, pnlPct, state);
					writeTrailSnapshot(symbol, snapshot, markPrice, pnlPct, reason, state, Double.NaN, Double.NaN,
							Double.NaN, Double.NaN);
					ctiLbStrategy.requestTrailingExit(new TrailingExitRequest(symbol, snapshot.side(),
							snapshot.entryPrice(), markPrice, snapshot.leverage(), pnlPct, reason, 0, 0, 0));
					return;
				}

				if (pnlPct <= -sl) {
					state.closingFlag.set(true);
					state.closingSinceMs = System.currentTimeMillis();
					state.closingAttempts++;
					String reason = "ROI_STOP_LOSS";
					logTrailEvent("ROI_EXIT_SIGNAL", symbol, snapshot, markPrice, pnlPct, state);
					writeTrailSnapshot(symbol, snapshot, markPrice, pnlPct, reason, state, Double.NaN, Double.NaN,
							Double.NaN, Double.NaN);
					ctiLbStrategy.requestTrailingExit(new TrailingExitRequest(symbol, snapshot.side(),
							snapshot.entryPrice(), markPrice, snapshot.leverage(), pnlPct, reason, 0, 0, 0));
					return;
				}
			}

			// Rest of the trailing logic...
			if (!state.profitArmed && pnlPct >= profitArmThreshold()) {
				state.profitArmed = true;
				state.peakProfitPct = pnlPct;
				logTrailEvent("TRAIL_ARM_PROFIT", symbol, snapshot, markPrice, pnlPct, state);
			}
			if (state.profitArmed) {
				ctiLbStrategy.setTrailingArmed(symbol, true);
			}
			if (!state.lossArmed && pnlPct <= lossArmThreshold()) {
				state.lossArmed = true;
				state.troughLossPct = pnlPct;
				state.bestRecoveryPct = pnlPct;
				logTrailEvent("TRAIL_ARM_LOSS", symbol, snapshot, markPrice, pnlPct, state);
				ctiLbStrategy.setTrailingArmed(symbol, true);
			}
			if (state.profitArmed && pnlPct > state.peakProfitPct) {
				state.peakProfitPct = pnlPct;
				logTrailEvent("TRAIL_NEW_PEAK", symbol, snapshot, markPrice, pnlPct, state);
			}
			if (state.lossArmed && pnlPct < state.troughLossPct) {
				state.troughLossPct = pnlPct;
				logTrailEvent("TRAIL_NEW_TROUGH", symbol, snapshot, markPrice, pnlPct, state);
			}
			if (state.lossArmed && pnlPct > state.bestRecoveryPct) {
				state.bestRecoveryPct = pnlPct;
				if (state.bestRecoveryPct - state.lastLoggedRecoveryPct >= 1.0) {
					state.lastLoggedRecoveryPct = state.bestRecoveryPct;
					logTrailEvent("TRAIL_NEW_RECOVERY", symbol, snapshot, markPrice, pnlPct, state);
				}
			}

			double trailWidth = profitTrailWidth(state.peakProfitPct);
			double profitStop = state.peakProfitPct - trailWidth;
			boolean profitExit = state.profitArmed && pnlPct < profitStop;
			double hardStop = state.troughLossPct - lossHardExtra();
			boolean lossHardExit = state.lossArmed && pnlPct <= hardStop;
			double recoveryStop = Double.NaN;
			boolean lossRecoveryExit = false;
			if (state.lossArmed) {
				double recoveryGain = state.bestRecoveryPct - state.troughLossPct;
				if (recoveryGain >= bonusDelta()) {
					recoveryStop = state.bestRecoveryPct - lossRecoveryTrail();
					lossRecoveryExit = pnlPct <= recoveryStop;
				}
			}

			updateDebounce(state, profitExit, lossHardExit, lossRecoveryExit);

			if (!state.closingFlag.get() && shouldExit(state)) {
				String reason = resolveExitReason(state);
				state.closingFlag.set(true);
				state.closingSinceMs = System.currentTimeMillis();
				state.closingAttempts++;
				logTrailEvent("TRAIL_EXIT_SIGNAL", symbol, snapshot, markPrice, pnlPct, state);
				writeTrailSnapshot(symbol, snapshot, markPrice, pnlPct, reason, state, trailWidth, profitStop,
						hardStop, recoveryStop);
				ctiLbStrategy.requestTrailingExit(
						new TrailingExitRequest(symbol, snapshot.side(), snapshot.entryPrice(), markPrice,
								snapshot.leverage(), pnlPct, reason, state.profitExitCount, state.lossHardExitCount,
								state.lossRecoveryExitCount));
			}
		}
	}
	public void resetState(String symbol) {
		TrailingState state = trailingMap.remove(symbol);
		if (state != null) {
			synchronized (state) {
//				LOGGER.info("EVENT=TRAIL_STATE_RESET symbol={} profitArmed={} lossArmed={} closingAttempts={} lastPnl={}",
//						symbol, state.profitArmed, state.lossArmed, state.closingAttempts,
//						String.format("%.2f", state.lastPnlPct));
				state.reset();
			}
		}
		ctiLbStrategy.setTrailingArmed(symbol, false);
	}
	public void resetClosingFlag(String symbol) {
		TrailingState state = trailingMap.get(symbol);
		if (state != null) {
			synchronized (state) {
//				LOGGER.info("EVENT=TRAIL_CLOSING_FLAG_RESET symbol={} attempts={}",
//						symbol, state.closingAttempts);
				state.closingFlag.set(false);
			}
		}
	}
	public void onPositionUpdate(String symbol, double entryPrice, double qty, String side, Integer leverage) {
		if (qty == 0 || entryPrice <= 0) {
			resetState(symbol);
			positionMap.remove(symbol);
			ctiLbStrategy.setTrailingArmed(symbol, false);
			return;
		}

		CtiDirection resolvedSide = resolveSide(side, qty);
		int leverageUsed = leverage != null && leverage > 0 ? leverage : leverageDefault();

		// ✅ CRITICAL: Reset state if entry price changed significantly
		PositionSnapshot existing = positionMap.get(symbol);
		if (existing != null) {
			double priceDiff = Math.abs(existing.entryPrice() - entryPrice);
			double priceChangePct = priceDiff / existing.entryPrice() * 100.0;

			// If entry price changed by more than 0.1%, it's a new position
			if (priceChangePct > 0.1) {
//				LOGGER.info("EVENT=POSITION_CHANGED symbol={} oldEntry={} newEntry={} changePct={}",
//						symbol, existing.entryPrice(), entryPrice, String.format("%.4f", priceChangePct));
				resetState(symbol);
			}
		}

		positionMap.put(symbol, new PositionSnapshot(entryPrice, qty, resolvedSide, leverageUsed,
				System.currentTimeMillis()));
	}

	private void startPositionSync() {
		long intervalMs = strategyProperties.pnlTrailPositionSyncMs() > 0
				? strategyProperties.pnlTrailPositionSyncMs()
				: Duration.ofSeconds(60).toMillis();
		Flux.interval(Duration.ofMillis(intervalMs))
				.flatMap(ignored -> Flux.fromIterable(strategyProperties.resolvedTradeSymbols())
						.flatMap(symbol -> orderClient.fetchPosition(symbol)
								.doOnNext(position -> onPositionUpdate(
										symbol,
										position.entryPrice() == null ? 0.0 : position.entryPrice().doubleValue(),
										position.positionAmt() == null ? 0.0 : position.positionAmt().doubleValue(),
										position.positionSide(),
										null))
								.onErrorResume(error -> {
									LOGGER.warn("EVENT=TRAIL_SYNC_FAIL symbol={} reason={}", symbol, error.getMessage());
									return reactor.core.publisher.Mono.empty();
								})))
				.subscribe();
	}

	private void updateDebounce(TrailingState state, boolean profitExit, boolean lossHardExit,
			boolean lossRecoveryExit) {
		int confirmTicks = exitConfirmTicks();
		state.profitExitReady = false;
		state.lossHardExitReady = false;
		state.lossRecoveryExitReady = false;
		if (profitExit) {
			state.profitExitCount += 1;
			if (state.profitExitCount >= confirmTicks) {
				state.profitExitReady = true;
			}
		} else {
			state.profitExitCount = 0;
		}
		if (lossHardExit) {
			state.lossHardExitCount += 1;
			if (state.lossHardExitCount >= confirmTicks) {
				state.lossHardExitReady = true;
			}
		} else {
			state.lossHardExitCount = 0;
		}
		if (lossRecoveryExit) {
			state.lossRecoveryExitCount += 1;
			if (state.lossRecoveryExitCount >= confirmTicks) {
				state.lossRecoveryExitReady = true;
			}
		} else {
			state.lossRecoveryExitCount = 0;
		}
	}

	private boolean shouldExit(TrailingState state) {
		return state.lossHardExitReady || state.lossRecoveryExitReady || state.profitExitReady;
	}

	private String resolveExitReason(TrailingState state) {
		if (state.lossHardExitReady) {
			return "LOSS_HARD";
		}
		if (state.lossRecoveryExitReady) {
			return "LOSS_RECOVERY";
		}
		return "PROFIT_TRAIL";
	}

	private double calculatePnlPct(PositionSnapshot snapshot, double markPrice) {
		double entryPrice = snapshot.entryPrice();
		double ratio = markPrice / entryPrice;
		double leverage = snapshot.leverage();
		if (snapshot.side() == CtiDirection.SHORT) {
			return (1.0 - ratio) * leverage * 100.0;
		}
		return (ratio - 1.0) * leverage * 100.0;
	}

	private void logTrailEvent(String event, String symbol, PositionSnapshot snapshot, double markPrice,
			double pnlPct, TrailingState state) {
		double trailWidth = Double.NaN;
		double profitStop = Double.NaN;
		if (state.profitArmed) {
			trailWidth = profitTrailWidth(state.peakProfitPct);
			profitStop = state.peakProfitPct - trailWidth;
		}
		double hardStop = Double.NaN;
		double recoveryStop = Double.NaN;
		if (state.lossArmed) {
			hardStop = state.troughLossPct - lossHardExtra();
			double recoveryGain = state.bestRecoveryPct - state.troughLossPct;
			if (recoveryGain >= bonusDelta()) {
				recoveryStop = state.bestRecoveryPct - lossRecoveryTrail();
			}
		}
		LOGGER.info(
				"EVENT={} symbol={} side={} entryPrice={} markPrice={} leverageUsed={} pnlPct={} profitArmed={} peakProfitPct={} trailWidth={} profitStop={} lossArmed={} troughLossPct={} bestRecoveryPct={} hardStop={} recoveryStop={} profitCount={} lossHardCount={} lossRecoveryCount={}",
				event,
				symbol,
				snapshot.side(),
				snapshot.entryPrice(),
				markPrice,
				snapshot.leverage(),
				String.format("%.4f", pnlPct),
				state.profitArmed,
				String.format("%.4f", state.peakProfitPct),
				Double.isNaN(trailWidth) ? "NA" : String.format("%.4f", trailWidth),
				Double.isNaN(profitStop) ? "NA" : String.format("%.4f", profitStop),
				state.lossArmed,
				String.format("%.4f", state.troughLossPct),
				String.format("%.4f", state.bestRecoveryPct),
				Double.isNaN(hardStop) ? "NA" : String.format("%.4f", hardStop),
				Double.isNaN(recoveryStop) ? "NA" : String.format("%.4f", recoveryStop),
				state.profitExitCount,
				state.lossHardExitCount,
				state.lossRecoveryExitCount);
	}

	private void writeTrailSnapshot(String symbol, PositionSnapshot snapshot, double markPrice,
			double pnlPct, String reason, TrailingState state, double trailWidth, double profitStop,
			double hardStop, double recoveryStop) {
		try {
			Files.createDirectories(SIGNAL_OUTPUT_DIR);
			Path outputFile = SIGNAL_OUTPUT_DIR.resolve(symbol + "-trailing.json");
			Object lock = fileLocks.computeIfAbsent(symbol, ignored -> new Object());
			synchronized (lock) {
				ObjectNode payload = objectMapper.createObjectNode();
				payload.put("symbol", symbol);
				payload.put("reason", reason);
				payload.put("side", snapshot.side() == null ? "NA" : snapshot.side().name());
				payload.put("entryPrice", snapshot.entryPrice());
				payload.put("markPrice", markPrice);
				payload.put("leverageUsed", snapshot.leverage());
				payload.put("pnlPct", pnlPct);
				payload.put("profitArmed", state.profitArmed);
				payload.put("peakProfitPct", state.peakProfitPct);
				payload.put("trailWidth", trailWidth);
				payload.put("profitStop", profitStop);
				payload.put("lossArmed", state.lossArmed);
				payload.put("troughLossPct", state.troughLossPct);
				payload.put("bestRecoveryPct", state.bestRecoveryPct);
				payload.put("hardStop", hardStop);
				payload.put("recoveryStop", recoveryStop);
				payload.put("profitExitHits", state.profitExitCount);
				payload.put("lossHardExitHits", state.lossHardExitCount);
				payload.put("lossRecoveryExitHits", state.lossRecoveryExitCount);
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), payload);
			}
		} catch (IOException error) {
			LOGGER.warn("EVENT=TRAIL_SNAPSHOT_FAILED symbol={} reason={}", symbol, error.getMessage());
		}
	}

	private CtiDirection resolveSide(String side, double qty) {
		if ("LONG".equalsIgnoreCase(side)) {
			return CtiDirection.LONG;
		}
		if ("SHORT".equalsIgnoreCase(side)) {
			return CtiDirection.SHORT;
		}
		if (qty > 0) {
			return CtiDirection.LONG;
		}
		if (qty < 0) {
			return CtiDirection.SHORT;
		}
		return CtiDirection.NEUTRAL;
	}

	private int leverageDefault() {
		// ROI exits are calibrated for 50x as requested.
		if (strategyProperties.roiExitEnabled()) {
			return 50;
		}
		return strategyProperties.leverage() > 0 ? strategyProperties.leverage() : 50;
	}

	private double profitArmThreshold() {
		return strategyProperties.pnlTrailProfitArm() > 0 ? strategyProperties.pnlTrailProfitArm() : 20.0;
	}

	private double lossArmThreshold() {
		return strategyProperties.pnlTrailLossArm() < 0 ? strategyProperties.pnlTrailLossArm() : -20.0;
	}

	private double bonusDelta() {
		return strategyProperties.pnlTrailBonusDelta() > 0 ? strategyProperties.pnlTrailBonusDelta() : 7.0;
	}

	private double profitTrailWidth(double peakProfitPct) {
		double bonusThreshold = profitArmThreshold() + bonusDelta();
		if (peakProfitPct >= bonusThreshold) {
			return strategyProperties.pnlTrailProfitTrailBonus() > 0 ? strategyProperties.pnlTrailProfitTrailBonus()
					: 4.0;
		}
		return strategyProperties.pnlTrailProfitTrailBase() > 0 ? strategyProperties.pnlTrailProfitTrailBase() : 2.0;
	}

	private double lossHardExtra() {
		return strategyProperties.pnlTrailLossHardExtra() > 0 ? strategyProperties.pnlTrailLossHardExtra() : 2.0;
	}

	private double lossRecoveryTrail() {
		return strategyProperties.pnlTrailLossRecoveryTrail() > 0 ? strategyProperties.pnlTrailLossRecoveryTrail() : 4.0;
	}

	private int exitConfirmTicks() {
		return strategyProperties.pnlTrailExitConfirmTicks() > 0 ? strategyProperties.pnlTrailExitConfirmTicks()
				: exitConfirmTicksDefault;
	}

	private record PositionSnapshot(
			double entryPrice,
			double qty,
			CtiDirection side,
			int leverage,
			long lastUpdateTs) {
	}

	private static class TrailingState {
		private boolean profitArmed;
		private boolean lossArmed;
		private double peakProfitPct;
		private double troughLossPct;
		private double bestRecoveryPct;
		private double lastLoggedRecoveryPct;
		private double lastPnlPct;
		private int profitExitCount;
		private int lossHardExitCount;
		private int lossRecoveryExitCount;
		private boolean profitExitReady;
		private boolean lossHardExitReady;
		private boolean lossRecoveryExitReady;
		private final AtomicBoolean closingFlag = new AtomicBoolean(false);
		private long closingSinceMs;
		private int closingAttempts;

		private TrailingState() {
			reset();
		}

		private void reset() {
			profitArmed = false;
			lossArmed = false;
			peakProfitPct = Double.NEGATIVE_INFINITY;
			troughLossPct = Double.POSITIVE_INFINITY;
			bestRecoveryPct = Double.NEGATIVE_INFINITY;
			lastLoggedRecoveryPct = Double.NEGATIVE_INFINITY;
			lastPnlPct = Double.NaN;
			profitExitCount = 0;
			lossHardExitCount = 0;
			lossRecoveryExitCount = 0;
			profitExitReady = false;
			lossHardExitReady = false;
			lossRecoveryExitReady = false;
			closingFlag.set(false);
			closingSinceMs = 0L;
			closingAttempts = 0;
		}
	}
}
