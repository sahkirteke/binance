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
	private final long debounceMsDefault = 300L;
	private final int debounceTicksDefault = 2;

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

	public void onMarkPrice(String symbol, double markPrice) {
		if (!strategyProperties.pnlTrailEnabled()) {
			return;
		}
		PositionSnapshot snapshot = positionMap.get(symbol);
		if (snapshot == null || snapshot.qty() == 0 || snapshot.entryPrice() <= 0) {
			resetState(symbol);
			return;
		}
		double pnlPct = calculatePnlPct(snapshot, markPrice);
		TrailingState state = trailingMap.computeIfAbsent(symbol, ignored -> new TrailingState());
		long now = System.currentTimeMillis();
		synchronized (state) {
			state.lastPnlPct = pnlPct;
			if (!state.profitArmed && pnlPct >= profitArmThreshold()) {
				state.profitArmed = true;
				state.peakProfitPct = pnlPct;
				logTrailEvent("TRAIL_ARM_PROFIT", symbol, snapshot, markPrice, pnlPct, state);
			}
			if (!state.lossArmed && pnlPct <= lossArmThreshold()) {
				state.lossArmed = true;
				state.troughLossPct = pnlPct;
				logTrailEvent("TRAIL_ARM_LOSS", symbol, snapshot, markPrice, pnlPct, state);
			}
			if (state.profitArmed && pnlPct > state.peakProfitPct) {
				state.peakProfitPct = pnlPct;
				logTrailEvent("TRAIL_NEW_PEAK", symbol, snapshot, markPrice, pnlPct, state);
			}
			if (state.lossArmed && pnlPct < state.troughLossPct) {
				state.troughLossPct = pnlPct;
				logTrailEvent("TRAIL_NEW_TROUGH", symbol, snapshot, markPrice, pnlPct, state);
			}

			boolean profitExit = state.profitArmed
					&& pnlPct < (state.peakProfitPct - profitGap());
			boolean lossExit = state.lossArmed
					&& pnlPct <= (state.troughLossPct - lossGap());

			updateDebounce(state, now, profitExit, lossExit);

			if (!state.closingFlag.get()
					&& shouldExit(state)) {
				String reason = state.profitExitReady ? "PROFIT_TRAIL" : "LOSS_TRAIL";
				state.closingFlag.set(true);
				logTrailEvent("TRAIL_EXIT_SIGNAL", symbol, snapshot, markPrice, pnlPct, state);
				writeTrailSnapshot(symbol, snapshot, markPrice, pnlPct, reason, state);
				ctiLbStrategy.requestTrailingExit(
						new TrailingExitRequest(symbol, snapshot.side(), snapshot.entryPrice(), markPrice,
								snapshot.leverage(), pnlPct, reason, state.profitExitCount, state.lossExitCount));
			}
		}
	}

	public void onPositionUpdate(String symbol, double entryPrice, double qty, String side, Integer leverage) {
		if (qty == 0 || entryPrice <= 0) {
			resetState(symbol);
			positionMap.remove(symbol);
			return;
		}
		CtiDirection resolvedSide = resolveSide(side, qty);
		int leverageUsed = leverage != null && leverage > 0 ? leverage : leverageDefault();
		positionMap.put(symbol, new PositionSnapshot(entryPrice, qty, resolvedSide, leverageUsed, System.currentTimeMillis()));
	}

	public void resetState(String symbol) {
		TrailingState state = trailingMap.remove(symbol);
		if (state != null) {
			synchronized (state) {
				state.reset();
			}
		}
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

	private void updateDebounce(TrailingState state, long now, boolean profitExit, boolean lossExit) {
		int debounceTicks = debounceTicks();
		long debounceMs = debounceMs();
		state.profitExitReady = false;
		state.lossExitReady = false;
		if (profitExit) {
			if (state.profitExitCount == 0) {
				state.profitExitStartMs = now;
			}
			state.profitExitCount += 1;
			if (state.profitExitCount >= debounceTicks || (now - state.profitExitStartMs) >= debounceMs) {
				state.profitExitReady = true;
			}
		} else {
			state.profitExitCount = 0;
			state.profitExitStartMs = 0L;
		}
		if (lossExit) {
			if (state.lossExitCount == 0) {
				state.lossExitStartMs = now;
			}
			state.lossExitCount += 1;
			if (state.lossExitCount >= debounceTicks || (now - state.lossExitStartMs) >= debounceMs) {
				state.lossExitReady = true;
			}
		} else {
			state.lossExitCount = 0;
			state.lossExitStartMs = 0L;
		}
	}

	private boolean shouldExit(TrailingState state) {
		return state.profitExitReady || state.lossExitReady;
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
		LOGGER.info(
				"EVENT={} symbol={} side={} entryPrice={} markPrice={} leverageUsed={} pnlPct={} peakProfitPct={} troughLossPct={} profitStop={} lossStop={} profitCount={} lossCount={}",
				event,
				symbol,
				snapshot.side(),
				snapshot.entryPrice(),
				markPrice,
				snapshot.leverage(),
				String.format("%.4f", pnlPct),
				String.format("%.4f", state.peakProfitPct),
				String.format("%.4f", state.troughLossPct),
				String.format("%.4f", state.peakProfitPct - profitGap()),
				String.format("%.4f", state.troughLossPct - lossGap()),
				state.profitExitCount,
				state.lossExitCount);
	}

	private void writeTrailSnapshot(String symbol, PositionSnapshot snapshot, double markPrice,
			double pnlPct, String reason, TrailingState state) {
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
				payload.put("peakProfitPct", state.peakProfitPct);
				payload.put("troughLossPct", state.troughLossPct);
				payload.put("profitStop", state.peakProfitPct - profitGap());
				payload.put("lossStop", state.troughLossPct - lossGap());
				payload.put("profitCount", state.profitExitCount);
				payload.put("lossCount", state.lossExitCount);
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
		return strategyProperties.leverage() > 0 ? strategyProperties.leverage() : 50;
	}

	private double profitArmThreshold() {
		return strategyProperties.pnlTrailProfitArm() > 0 ? strategyProperties.pnlTrailProfitArm() : 20.0;
	}

	private double lossArmThreshold() {
		return strategyProperties.pnlTrailLossArm() < 0 ? strategyProperties.pnlTrailLossArm() : -20.0;
	}

	private double profitGap() {
		return strategyProperties.pnlTrailProfitGap() > 0 ? strategyProperties.pnlTrailProfitGap() : 2.0;
	}

	private double lossGap() {
		return strategyProperties.pnlTrailLossGap() > 0 ? strategyProperties.pnlTrailLossGap() : 2.0;
	}

	private int debounceTicks() {
		return strategyProperties.pnlTrailDebounceTicks() > 0 ? strategyProperties.pnlTrailDebounceTicks()
				: debounceTicksDefault;
	}

	private long debounceMs() {
		return strategyProperties.pnlTrailDebounceMs() > 0 ? strategyProperties.pnlTrailDebounceMs() : debounceMsDefault;
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
		private double lastPnlPct;
		private int profitExitCount;
		private int lossExitCount;
		private long profitExitStartMs;
		private long lossExitStartMs;
		private boolean profitExitReady;
		private boolean lossExitReady;
		private final AtomicBoolean closingFlag = new AtomicBoolean(false);

		private TrailingState() {
			reset();
		}

		private void reset() {
			profitArmed = false;
			lossArmed = false;
			peakProfitPct = Double.NEGATIVE_INFINITY;
			troughLossPct = Double.POSITIVE_INFINITY;
			lastPnlPct = Double.NaN;
			profitExitCount = 0;
			lossExitCount = 0;
			profitExitStartMs = 0L;
			lossExitStartMs = 0L;
			profitExitReady = false;
			lossExitReady = false;
			closingFlag.set(false);
		}
	}
}
