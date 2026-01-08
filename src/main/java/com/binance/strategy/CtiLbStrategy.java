package com.binance.strategy;

import java.io.IOException;
import java.math.MathContext;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.exchange.dto.OrderResponse;
import com.binance.strategy.StrategyLogV1.DecisionLogDto;
import com.binance.strategy.StrategyLogV1.FlipLogDto;
import com.binance.strategy.StrategyLogV1.SummaryLogDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import java.math.BigDecimal;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.netty.http.client.PrematureCloseException;

@Component
public class CtiLbStrategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(CtiLbStrategy.class);
	private static final int EMA_20_PERIOD = 20;
	private static final int EMA_200_PERIOD = 200;
	private static final int RSI_9_PERIOD = 9;
	private static final int ATR_14_PERIOD = 14;
	private static final int ATR_SMA_20_PERIOD = 20;
	private static final int VOLUME_SMA_10_PERIOD = 10;
	private static final Pattern BINANCE_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
	private static final Pattern BINANCE_STATUS_PATTERN = Pattern.compile("status=(\\d+)");
	private static final Set<Integer> NON_RETRYABLE_BINANCE_CODES = Set.of(
			-1013,
			-1111,
			-1102,
			-2010,
			-2011,
			-2019,
			-2022
	);

	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final WarmupProperties warmupProperties;
	private final SymbolFilterService symbolFilterService;
	private final OrderTracker orderTracker;
	private final ObjectMapper objectMapper;
	private final Map<String, Long> lastCloseTimes = new ConcurrentHashMap<>();
	private final Map<String, PositionState> positionStates = new ConcurrentHashMap<>();
	private final Map<String, EntryState> entryStates = new ConcurrentHashMap<>();
	private final LongAdder flipCount = new LongAdder();
	private final java.util.concurrent.atomic.AtomicLong lastSummaryAtMs = new java.util.concurrent.atomic.AtomicLong();
	private final Map<String, Long> lastPositionSyncMs = new ConcurrentHashMap<>();
	private final Map<String, Long> lastFlipTimes = new ConcurrentHashMap<>();
	private final Map<String, BigDecimal> lastFlipPrices = new ConcurrentHashMap<>();
	private final Map<String, java.util.Deque<Long>> flipTimesBySymbol = new ConcurrentHashMap<>();
	private final Map<String, BinanceFuturesOrderClient.ExchangePosition> exchangePositions = new ConcurrentHashMap<>();
	private final Map<String, Boolean> stateDesyncBySymbol = new ConcurrentHashMap<>();
	private final Map<String, Boolean> hedgeModeBySymbol = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> warmupDecisionCounters = new ConcurrentHashMap<>();
	private final Map<String, SymbolState> symbolStates = new ConcurrentHashMap<>();
	private final Map<String, Long> lastOppositeExitAtMs = new ConcurrentHashMap<>();
	private final Map<String, Integer> pendingFlipDirs = new ConcurrentHashMap<>();
	private static final long POSITION_SYNC_INTERVAL_MS = 60_000L;
	private static final BigDecimal DEFAULT_NOTIONAL_USDT = BigDecimal.valueOf(50);
	private static final long DEFAULT_MIN_HOLD_MS = 30_000L;
	private static final BigDecimal DEFAULT_FEE_BPS = BigDecimal.valueOf(2);
	private static final Path SIGNAL_OUTPUT_DIR = Paths.get("signals");
	private final Map<String, Object> signalFileLocks = new ConcurrentHashMap<>();
	private volatile boolean warmupMode;
	private volatile boolean ordersEnabledOverride = true;

	public CtiLbStrategy(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties,
			WarmupProperties warmupProperties, SymbolFilterService symbolFilterService, OrderTracker orderTracker,
			ObjectMapper objectMapper) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.symbolFilterService = symbolFilterService;
		this.orderTracker = orderTracker;
		this.objectMapper = objectMapper;
		logConfigSnapshot();
	}

	public void setWarmupMode(boolean warmupMode) {
		this.warmupMode = warmupMode;
		if (warmupMode) {
			ordersEnabledOverride = false;
		}
	}

	public void enableOrdersAfterWarmup() {
		ordersEnabledOverride = true;
	}

	public void syncPositionNow(String symbol, long eventTime) {
		orderClient.fetchPosition(symbol)
				.doOnNext(position -> applyExchangePosition(symbol, position, eventTime))
				.doOnError(error -> LOGGER.warn("EVENT=POSITION_SYNC symbol={} error={}", symbol, error.getMessage()))
				.subscribe();
	}

	public Mono<Void> refreshAfterWarmup(String symbol) {
		triggerFilterRefresh();
		return orderClient.cancelAllOpenOrders(symbol)
				.onErrorResume(error -> {
					LOGGER.warn("EVENT=WARMUP_REFRESH symbol={} cancelError={}", symbol, error.getMessage());
					return Mono.empty();
				})
				.then(orderClient.fetchPosition(symbol)
						.doOnNext(position -> applyExchangePosition(symbol, position, System.currentTimeMillis()))
						.onErrorResume(error -> {
							LOGGER.warn("EVENT=WARMUP_REFRESH symbol={} positionError={}", symbol, error.getMessage());
							return Mono.empty();
						})
						.then());
	}

	public void onScoreSignal(String symbol, ScoreSignal signal, Candle candle) {
		if (signal == null) {
			return;
		}
		SignalAction action = SignalAction.HOLD;
		long closeTime = signal.closeTime();
		Long previousCloseTime = lastCloseTimes.put(symbol, closeTime);
		if (previousCloseTime != null && previousCloseTime == closeTime) {
			return;
		}
		PositionState current = positionStates.getOrDefault(symbol, PositionState.NONE);
		SymbolState symbolState = symbolStates.computeIfAbsent(symbol, ignored -> new SymbolState());
		double close = candle.close();
		double prevClose = symbolState.prevClose1m;
		int fiveMinDir = symbolState.lastFiveMinDir;
		symbolState.updateOneMinuteIndicators(candle);
		if (signal.cti5mReady()) {
			int newDir = resolveFiveMinDir(signal.score5m());
			if (newDir != 0 && newDir != symbolState.lastFiveMinDir) {
				symbolState.fiveMinFlipTimeMs = closeTime;
				symbolState.fiveMinFlipPrice = close;
				symbolState.prevFiveMinDir = symbolState.lastFiveMinDir;
			}
			symbolState.lastFiveMinDir = newDir;
			fiveMinDir = newDir;
		}
		symbolState.prevClose1m = close;
		EntryFilterState entryFilterState = resolveEntryFilterState(
				fiveMinDir,
				signal.score1m(),
				close,
				candle.volume(),
				closeTime,
				symbolState);
		EntryDecision entryDecision = resolveEntryDecision(current, symbolState, entryFilterState);
		entryDecision = applyPendingFlipGate(symbol, current, entryDecision, entryFilterState, closeTime);
		logEntryFilterState(symbol, symbolState, closeTime, close, prevClose, entryFilterState, entryDecision);

		CtiDirection recommendationRaw = signal.recommendation();
		CtiDirection recommendationUsed = signal.insufficientData() ? CtiDirection.NEUTRAL : recommendationRaw;
		CtiDirection confirmedRec = recommendationUsed;
		if (current == PositionState.NONE && entryDecision.confirmedRec() != null) {
			confirmedRec = entryDecision.confirmedRec();
		}

		boolean trendAlignedWithPosition = resolveTrendAlignedWithPosition(
				current,
				fiveMinDir,
				signal.cti1mValue(),
				signal.cti1mPrev());
		int finalScore = signal.finalScore();
		boolean finalScoreOpposite = (current == PositionState.LONG && finalScore <= -1)
				|| (current == PositionState.SHORT && finalScore >= 1);
		boolean scoreExitConfirmed = current != PositionState.NONE && finalScoreOpposite;

		int qualityScoreForLog = entryFilterState.qualityScore();
		String qualityConfirmReason = null;
		if (warmupMode) {
			if (shouldLogWarmupDecision(symbol)) {
				logDecision(symbol, signal, close, SignalAction.HOLD, confirmedRec, recommendationUsed,
						recommendationRaw, null, null, null, "WARMUP_MODE", "WARMUP_MODE", TrailState.empty(),
						ContinuationState.empty(), null, qualityScoreForLog, qualityConfirmReason,
						false, null, 0, TpTrailingState.empty(),
						trendAlignedWithPosition, false);
			}
			return;
		}

		syncPositionIfNeeded(symbol, closeTime);
		EntryState entryState = resolveEntryState(symbol, current, close, closeTime);
		TrailState trailState = resolveTrailState(symbolState, current, entryState, fiveMinDir, close);
		TrailState decisionTrailState = trailState;
		boolean trendStillStrong = resolveTrendStillStrong(entryState == null ? null : entryState.side(),
				fiveMinDir, close, symbolState);
		boolean confirmedOpposite = current == PositionState.LONG
				? confirmedRec == CtiDirection.SHORT
				: current == PositionState.SHORT && confirmedRec == CtiDirection.LONG;
		TpTrailingState tpTrailingState = resolveTpTrailingState(symbolState, current, entryState, close);
		TpTrailingState decisionTpTrailingState = tpTrailingState;
		ContinuationState continuationState = resolveContinuationState(
				symbolState,
				current,
				entryState,
				fiveMinDir,
				signal.score1m(),
				entryFilterState.qualityScore(),
				close,
				closeTime);
		ContinuationState decisionContinuationState = continuationState;
		String flipGateReason = null;
		int flipQualityScore = 0;
		boolean exitBlockedByTrendAlignedAction = false;
		int barsInPosition = resolveBarsInPosition(entryState, closeTime);
		IndicatorsSnapshot indicatorsSnapshot = new IndicatorsSnapshot(
				symbolState.ema200_5mValue,
				symbolState.isEma200Ready(),
				symbolState.ema20_1mValue,
				symbolState.isEma20Ready(),
				symbolState.rsi9Value,
				symbolState.isRsiReady(),
				signal.adx5m(),
				signal.adxReady(),
				signal.cti1mDir());
		boolean trendHoldActive = shouldHoldPosition(
				current,
				close,
				indicatorsSnapshot,
				resolveDir(recommendationUsed),
				resolveDir(confirmedRec),
				barsInPosition,
				strategyProperties);
		String trendHoldReason = null;
		CtiLbDecisionEngine.ExitDecision exitDecision = CtiLbDecisionEngine.evaluateExit(
				entryState == null ? null : entryState.side(),
				entryState == null ? null : entryState.entryPrice(),
				close,
				strategyProperties.stopLossBps(),
				strategyProperties.takeProfitBps(),
				resolveFeeBps(),
				strategyProperties.maxSpreadBps(),
				strategyProperties.flipSpreadMaxBps(),
				closeTime,
				entryState == null ? null : entryState.entryTimeMs(),
				resolveMinHoldMs(),
				scoreExitConfirmed);
		Double estimatedPnlPct = exitDecision.pnlBps() / 100.0;

		if (current != PositionState.NONE && exitDecision.exit()) {
			SignalAction exitAction = SignalAction.HOLD;
			String decisionActionReason = exitDecision.reason();
			String decisionBlockReason = CtiLbDecisionEngine.resolveExitDecisionBlockReason();
			BigDecimal exitQty = resolveExitQuantity(symbol, entryState, close);
			boolean stopLossExit = isStopLossExit(decisionActionReason);
			boolean exitBlockedByTrendAligned = false;
			if (trendHoldActive && !stopLossExit && isTakeProfitExit(decisionActionReason)
					&& strategyProperties.tpTrailingEnabled()) {
				if (!decisionTpTrailingState.allowExit()) {
					decisionActionReason = "TREND_HOLD_ACTIVE";
					decisionBlockReason = "TREND_HOLD_ACTIVE";
					trendHoldReason = "TREND_HOLD_ACTIVE";
					logDecision(symbol, signal, close, exitAction, confirmedRec, recommendationUsed,
							recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason,
							decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
							qualityScoreForLog, qualityConfirmReason, trendHoldActive,
							trendHoldReason, flipQualityScore, decisionTpTrailingState,
						trendAlignedWithPosition, false);
					return;
				}
			}
			ExitHoldDecision exitHoldDecision = evaluateExitHoldDecision(stopLossExit, scoreExitConfirmed,
					trendAlignedWithPosition);
			if (exitHoldDecision.hold()) {
				decisionActionReason = exitHoldDecision.reason();
				decisionBlockReason = exitHoldDecision.reason();
				exitBlockedByTrendAligned = exitHoldDecision.exitBlockedByTrendAligned();
				logDecision(symbol, signal, close, exitAction, confirmedRec, recommendationUsed,
						recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason,
						decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
						qualityScoreForLog, qualityConfirmReason, trendHoldActive,
						trendHoldReason, flipQualityScore, decisionTpTrailingState,
					trendAlignedWithPosition, exitBlockedByTrendAligned);
				return;
			}
			boolean continuationHold = decisionContinuationState.holdApplied() && !stopLossExit;
			boolean holdExit = shouldHoldTrendStrong(
					entryState == null ? null : entryState.side(),
					entryState == null ? null : entryState.entryPrice(),
					close,
					fiveMinDir,
					symbolState.ema20_1mValue,
					symbolState.ema200_5mValue,
					symbolState.isTrendEmaReady(),
					trailState.trailStopHit(),
					stopLossExit,
					confirmedRec,
					strategyProperties.trendHoldMinProfitPct());
			if (continuationHold) {
				decisionActionReason = "EXIT_HOLD_CONTINUATION";
				decisionBlockReason = "EXIT_HOLD_CONTINUATION";
				decisionContinuationState = decisionContinuationState.withHoldApplied(true);
			} else if (holdExit) {
				decisionActionReason = "EXIT_HOLD_TREND_STRONG";
				decisionBlockReason = "EXIT_HOLD_TREND_STRONG";
				decisionTrailState = decisionTrailState.withExitHoldApplied(true);
			}
			logDecision(symbol, signal, close, exitAction, confirmedRec, recommendationUsed,
					recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason, decisionBlockReason,
					decisionTrailState, decisionContinuationState, flipGateReason,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive, trendHoldReason,
					flipQualityScore, decisionTpTrailingState, trendAlignedWithPosition,
					exitBlockedByTrendAligned);
			if (!continuationHold && !holdExit && exitQty != null && exitQty.signum() > 0) {
				recordSignalSnapshot(symbol, "EXIT", exitAction, signal, closeTime, close, exitQty,
						current, null, entryState, decisionActionReason, decisionBlockReason, recommendationUsed,
						recommendationRaw, confirmedRec);
			}
			if (continuationHold || holdExit || !effectiveEnableOrders()) {
				if (!effectiveEnableOrders()) {
					positionStates.put(symbol, PositionState.NONE);
					entryStates.remove(symbol);
				}
				return;
			}
			if (exitQty == null || exitQty.signum() <= 0) {
				return;
			}
			final String decisionActionReasonFinal = decisionActionReason;
			final BigDecimal exitQtyFinal = exitQty;
			final PositionState currentFinal = current;
			orderClient.fetchHedgeModeEnabled()
					.flatMap(hedgeMode -> {
						String correlationId = orderTracker.nextCorrelationId(symbol, "EXIT");
						return closePosition(symbol, currentFinal, exitQtyFinal, hedgeMode, correlationId)
								.doOnNext(response -> {
									orderTracker.registerSubmitted(symbol, correlationId, response, true);
									logOrderEvent("EXIT_ORDER", symbol, decisionActionReasonFinal,
											currentFinal == PositionState.LONG ? "SELL" : "BUY", exitQtyFinal, true,
											hedgeMode ? currentFinal.name() : "", correlationId, response, null);
								})
								.doOnError(error -> logOrderEvent("EXIT_ORDER", symbol, decisionActionReasonFinal,
										currentFinal == PositionState.LONG ? "SELL" : "BUY", exitQtyFinal, true,
										hedgeMode ? currentFinal.name() : "", correlationId, null, error.getMessage()));
					})
					.doOnNext(response -> {
						positionStates.put(symbol, PositionState.NONE);
						entryStates.remove(symbol);
						recordFlip(symbol, closeTime, BigDecimal.valueOf(close));
					})
					.doOnError(error -> LOGGER.warn("Failed to execute CTI LB exit {}: {}", decisionActionReasonFinal,
							error.getMessage()))
					.onErrorResume(error -> Mono.empty())
					.subscribe();
			return;
		}

		action = resolveAction(current, confirmedRec);
		BigDecimal resolvedQty = resolveQuantity(symbol, close);
		BigDecimal closeQty = current == PositionState.NONE ? resolvedQty : resolveExitQuantity(symbol, entryState, close);
		boolean hasSignal = recommendationUsed != CtiDirection.NEUTRAL;
		boolean confirmationMet = confirmedRec != CtiDirection.NEUTRAL;
		PositionState target = confirmedRec == CtiDirection.LONG ? PositionState.LONG : PositionState.SHORT;
		String decisionActionReason = action == SignalAction.HOLD
				? resolveHoldReason(signal, hasSignal, confirmationMet)
				: current == PositionState.NONE ? "OK" : resolveFlipReason(current, target);
		String decisionBlockReason = resolveDecisionBlockReason(signal, action, confirmedRec, current);
		if (current == PositionState.NONE && entryDecision.blockReason() != null) {
			action = SignalAction.HOLD;
			decisionActionReason = entryDecision.blockReason();
			decisionBlockReason = entryDecision.blockReason();
		}
		if (current != PositionState.NONE && action != SignalAction.HOLD) {
			if (decisionContinuationState.holdApplied()) {
				action = SignalAction.HOLD;
				decisionActionReason = "EXIT_HOLD_CONTINUATION";
				decisionBlockReason = "EXIT_HOLD_CONTINUATION";
				decisionContinuationState = decisionContinuationState.withHoldApplied(true);
			}
		}
		if (current != PositionState.NONE && action != SignalAction.HOLD && trendHoldActive) {
			TrendHoldFlipDecision trendHoldFlipDecision = evaluateTrendHoldFlipDecision(
					trendHoldActive,
					resolveDir(confirmedRec),
					resolveOppositeDir(current),
					resolveFlipQualityScore(current, close, entryFilterState, symbolState),
					strategyProperties.flipQualityMin());
			flipQualityScore = trendHoldFlipDecision.flipQualityScore();
				if (!trendHoldFlipDecision.allowFlip()) {
				action = SignalAction.HOLD;
				decisionActionReason = "TREND_HOLD_ACTIVE";
				decisionBlockReason = "TREND_HOLD_ACTIVE";
				trendHoldReason = "TREND_HOLD_ACTIVE";
			}
		}
		if (current != PositionState.NONE && action != SignalAction.HOLD && confirmedOpposite && !scoreExitConfirmed) {
			action = SignalAction.HOLD;
			decisionActionReason = "HOLD_SCORE_NEUTRAL";
			decisionBlockReason = "HOLD_SCORE_NEUTRAL";
		}
		if (current != PositionState.NONE && action != SignalAction.HOLD) {
			boolean holdExit = shouldHoldTrendStrong(
					entryState == null ? null : entryState.side(),
					entryState == null ? null : entryState.entryPrice(),
					close,
					fiveMinDir,
					symbolState.ema20_1mValue,
					symbolState.ema200_5mValue,
					symbolState.isTrendEmaReady(),
					trailState.trailStopHit(),
					false,
					confirmedRec,
					strategyProperties.trendHoldMinProfitPct());
			if (holdExit) {
				action = SignalAction.HOLD;
				decisionActionReason = "EXIT_HOLD_TREND_STRONG";
				decisionBlockReason = "EXIT_HOLD_TREND_STRONG";
				decisionTrailState = decisionTrailState.withExitHoldApplied(true);
			}
		}
		if (current == PositionState.NONE && entryDecision.decisionActionReason() != null
				&& action != SignalAction.HOLD) {
			decisionActionReason = entryDecision.decisionActionReason();
		}
		if (action != SignalAction.HOLD && current != PositionState.NONE && target != current) {
			if (decisionContinuationState.holdApplied()) {
				action = SignalAction.HOLD;
				decisionActionReason = "FLIP_BLOCK_CONTINUATION";
				decisionBlockReason = "FLIP_BLOCK_CONTINUATION";
				flipGateReason = "FLIP_BLOCK_CONTINUATION";
			} else {
				FlipGateResult flipGate = evaluateFlipGate(
						entryFilterState.qualityScore(),
						strategyProperties.flipFastQuality());
				if (!flipGate.allowFlip()) {
					action = SignalAction.HOLD;
					decisionActionReason = "FLIP_BLOCK_QUALITY_GATE";
					decisionBlockReason = "FLIP_BLOCK_QUALITY_GATE";
					flipGateReason = flipGate.reason();
				}
			}
		}
		if (action != SignalAction.HOLD) {
			CtiLbDecisionEngine.BlockDecision blockDecision = CtiLbDecisionEngine.evaluateEntryBlocks(
					new CtiLbDecisionEngine.BlockInput(
							closeTime,
							current != PositionState.NONE,
							entryState == null ? null : entryState.entryTimeMs(),
							lastFlipTimes.get(symbol),
							new java.util.ArrayList<>(flipTimesBySymbol.getOrDefault(symbol, new java.util.ArrayDeque<>())),
							resolveMinHoldMs(),
							strategyProperties.flipCooldownMs(),
							strategyProperties.maxFlipsPer5Min(),
							close,
							signal.cti1mValue(),
							signal.cti1mPrev(),
							strategyProperties.minBfrDelta(),
							strategyProperties.minPriceMoveBps(),
							lastFlipPrices.get(symbol)));
			if (blockDecision.blocked()) {
				decisionActionReason = blockDecision.reason();
				action = SignalAction.HOLD;
			}
		}
		if (action == SignalAction.HOLD || !"OK_EXECUTED".equals(decisionBlockReason)) {
			if (action != SignalAction.HOLD && "ORDERS_DISABLED".equals(decisionBlockReason)) {
				if (current == PositionState.NONE) {
					EntryState entrySnapshot = new EntryState(
							target == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
							BigDecimal.valueOf(close),
							closeTime,
							resolvedQty);
					recordSignalSnapshot(symbol, "ENTRY", action, signal, closeTime, close, resolvedQty,
							target, target, entrySnapshot, decisionActionReason, decisionBlockReason,
							recommendationUsed, recommendationRaw, confirmedRec);
					positionStates.put(symbol, target);
					entryStates.put(symbol, entrySnapshot);
				} else if (target != current) {
					recordSignalSnapshot(symbol, "EXIT", action, signal, closeTime, close, closeQty,
							current, target, entryState, decisionActionReason, decisionBlockReason,
							recommendationUsed, recommendationRaw, confirmedRec);
					EntryState entrySnapshot = new EntryState(
							target == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
							BigDecimal.valueOf(close),
							closeTime,
							resolvedQty);
					recordSignalSnapshot(symbol, "ENTRY", action, signal, closeTime, close, resolvedQty,
							target, target, entrySnapshot, decisionActionReason, decisionBlockReason,
							recommendationUsed, recommendationRaw, confirmedRec);
					positionStates.put(symbol, target);
					entryStates.put(symbol, entrySnapshot);
				}
			}
			action = SignalAction.HOLD;
			logDecision(symbol, signal, close, action, confirmedRec, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, decisionActionReason,
					decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive,
					trendHoldReason, flipQualityScore, decisionTpTrailingState,
					trendAlignedWithPosition, exitBlockedByTrendAlignedAction);
			return;
		}

		String minTradeBlockReason = validateMinTrade(symbol, resolvedQty, close);
		if (minTradeBlockReason != null) {
			action = SignalAction.HOLD;
			logDecision(symbol, signal, close, action, confirmedRec, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, minTradeBlockReason,
					"OK_EXECUTED", decisionTrailState, decisionContinuationState, flipGateReason,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive,
					trendHoldReason, flipQualityScore, decisionTpTrailingState,
					trendAlignedWithPosition, exitBlockedByTrendAlignedAction);
			return;
		}

		if (action != SignalAction.HOLD
				&& (resolvedQty == null || resolvedQty.signum() <= 0
						|| (current != PositionState.NONE && (closeQty == null || closeQty.signum() <= 0)))) {
			action = SignalAction.HOLD;
			decisionActionReason = "QTY_ZERO_AFTER_STEP";
			decisionBlockReason = "QTY_ZERO_AFTER_STEP";
			logDecision(symbol, signal, close, action, confirmedRec, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, decisionActionReason,
					decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive,
					trendHoldReason, flipQualityScore, decisionTpTrailingState,
					trendAlignedWithPosition, exitBlockedByTrendAlignedAction);
			return;
		}

		SignalAction actionForLog = action;
		String decisionBlock = decisionBlockReason;
		String decisionActionReasonForLog = decisionActionReason;
		BigDecimal resolvedQtyForLog = resolvedQty;
		CtiDirection recommendationRawForLog = recommendationRaw;
		CtiDirection recommendationUsedForLog = recommendationUsed;
		PositionState currentForLog = current;
		PositionState targetForLog = target;
		CtiDirection confirmedRecForLog = confirmedRec;
		TrailState trailStateForLog = decisionTrailState;
		ContinuationState continuationStateForLog = decisionContinuationState;
		String flipGateReasonForLog = flipGateReason;
		int qualityScoreForLogFinal = qualityScoreForLog;
		String qualityConfirmReasonForLog = qualityConfirmReason;
		boolean trendHoldActiveForLog = trendHoldActive;
		String trendHoldReasonForLog = trendHoldReason;
		int flipQualityScoreForLog = flipQualityScore;
		TpTrailingState tpTrailingStateForLog = decisionTpTrailingState;
		final boolean trendAlignedWithPositionFinal = trendAlignedWithPosition;
		final boolean exitBlockedByTrendAlignedActionFinal = exitBlockedByTrendAlignedAction;
		if (actionForLog != SignalAction.HOLD) {
			if (currentForLog == PositionState.NONE) {
				EntryState entrySnapshot = new EntryState(
						targetForLog == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
						BigDecimal.valueOf(close),
						closeTime,
						resolvedQtyForLog);
				recordSignalSnapshot(symbol, "ENTRY", actionForLog, signal, closeTime, close, resolvedQtyForLog,
						targetForLog, targetForLog, entrySnapshot, decisionActionReasonForLog, decisionBlock,
						recommendationUsedForLog, recommendationRawForLog, confirmedRecForLog);
				if (!effectiveEnableOrders()) {
					positionStates.put(symbol, targetForLog);
					entryStates.put(symbol, entrySnapshot);
				}
			} else if (targetForLog != currentForLog) {
				recordSignalSnapshot(symbol, "EXIT", actionForLog, signal, closeTime, close, closeQty, currentForLog,
						targetForLog, entryState, decisionActionReasonForLog, decisionBlock, recommendationUsedForLog,
						recommendationRawForLog, confirmedRecForLog);
				EntryState entrySnapshot = new EntryState(
						targetForLog == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
						BigDecimal.valueOf(close),
						closeTime,
						resolvedQtyForLog);
				recordSignalSnapshot(symbol, "ENTRY", actionForLog, signal, closeTime, close, resolvedQtyForLog,
						targetForLog, targetForLog, entrySnapshot, decisionActionReasonForLog, decisionBlock,
						recommendationUsedForLog, recommendationRawForLog, confirmedRecForLog);
				if (!effectiveEnableOrders()) {
					positionStates.put(symbol, targetForLog);
					entryStates.put(symbol, entrySnapshot);
				}
			}
			if (!effectiveEnableOrders()) {
				logDecision(symbol, signal, close, actionForLog, confirmedRecForLog,
						recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
						estimatedPnlPct, decisionActionReasonForLog, decisionBlock, trailStateForLog,
						continuationStateForLog, flipGateReasonForLog, qualityScoreForLogFinal,
						qualityConfirmReasonForLog, trendHoldActiveForLog,
						trendHoldReasonForLog, flipQualityScoreForLog,
						tpTrailingStateForLog, trendAlignedWithPositionFinal, exitBlockedByTrendAlignedActionFinal);
				return;
			}
		}
		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> {
					hedgeModeBySymbol.put(symbol, hedgeMode);
					logDecision(symbol, signal, close, actionForLog, confirmedRecForLog,
							recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
							estimatedPnlPct, decisionActionReasonForLog, decisionBlock, trailStateForLog,
							continuationStateForLog, flipGateReasonForLog, qualityScoreForLogFinal,
							qualityConfirmReasonForLog, trendHoldActiveForLog,
							trendHoldReasonForLog, flipQualityScoreForLog,
							tpTrailingStateForLog, trendAlignedWithPositionFinal, exitBlockedByTrendAlignedActionFinal);
					if (actionForLog == SignalAction.ENTER_LONG || actionForLog == SignalAction.ENTER_SHORT) {
						String correlationId = orderTracker.nextCorrelationId(symbol, "ENTRY");
						return openPosition(symbol, targetForLog, resolvedQtyForLog, hedgeMode, correlationId)
								.doOnNext(response -> {
									orderTracker.registerSubmitted(symbol, correlationId, response, false);
									logOrderEvent("ENTRY_ORDER", symbol, decisionActionReasonForLog,
											targetForLog == PositionState.LONG ? "BUY" : "SELL", resolvedQtyForLog, false,
											hedgeMode ? targetForLog.name() : "", correlationId, response, null);
								})
								.doOnError(error -> logOrderEvent("ENTRY_ORDER", symbol, decisionActionReasonForLog,
										targetForLog == PositionState.LONG ? "BUY" : "SELL", resolvedQtyForLog, false,
										hedgeMode ? targetForLog.name() : "", correlationId, null, error.getMessage()))
								.doOnNext(response -> positionStates.put(symbol, targetForLog))
								.doOnNext(response -> {
									recordEntry(symbol, targetForLog, response, resolvedQtyForLog, closeTime, close);
									flipCount.increment();
									logFlip(symbol, currentForLog, targetForLog, signal, close,
											recommendationUsedForLog, confirmedRecForLog, actionForLog,
											response == null ? null : response.orderId());
								})
								.then();
					}
					String closeCorrelationId = orderTracker.nextCorrelationId(symbol, "FLIP_CLOSE");
					String openCorrelationId = orderTracker.nextCorrelationId(symbol, "FLIP_OPEN");
					return closePosition(symbol, currentForLog, closeQty, hedgeMode, closeCorrelationId)
							.doOnNext(response -> {
								orderTracker.registerSubmitted(symbol, closeCorrelationId, response, true);
								logOrderEvent("FLIP_ORDER", symbol, decisionActionReasonForLog,
										currentForLog == PositionState.LONG ? "SELL" : "BUY", closeQty, true,
										hedgeMode ? currentForLog.name() : "", closeCorrelationId, response, null);
							})
							.doOnError(error -> logOrderEvent("FLIP_ORDER", symbol, decisionActionReasonForLog,
									currentForLog == PositionState.LONG ? "SELL" : "BUY", closeQty, true,
									hedgeMode ? currentForLog.name() : "", closeCorrelationId, null, error.getMessage()))
							.flatMap(response -> openPosition(symbol, targetForLog, resolvedQtyForLog, hedgeMode,
									openCorrelationId)
									.doOnNext(openResponse -> {
										orderTracker.registerSubmitted(symbol, openCorrelationId, openResponse, false);
										logOrderEvent("FLIP_ORDER", symbol,
												decisionActionReasonForLog, targetForLog == PositionState.LONG ? "BUY" : "SELL",
												resolvedQtyForLog, false, hedgeMode ? targetForLog.name() : "",
												openCorrelationId, openResponse, null);
									})
									.doOnError(error -> logOrderEvent("FLIP_ORDER", symbol, decisionActionReasonForLog,
											targetForLog == PositionState.LONG ? "BUY" : "SELL", resolvedQtyForLog, false,
											hedgeMode ? targetForLog.name() : "", openCorrelationId, null,
											error.getMessage())))
							.doOnNext(response -> positionStates.put(symbol, targetForLog))
							.doOnNext(response -> {
								recordEntry(symbol, targetForLog, response, resolvedQtyForLog, closeTime, close);
								recordFlip(symbol, closeTime, BigDecimal.valueOf(close));
								flipCount.increment();
								logFlip(symbol, currentForLog, targetForLog, signal, close,
										recommendationUsedForLog, confirmedRecForLog, actionForLog,
										response == null ? null : response.orderId());
							})
							.then();
				})
				.doOnError(error -> {
					LOGGER.warn("Failed to execute CTI LB action {}: {}", actionForLog, error.getMessage());
					logDecision(symbol, signal, close, SignalAction.HOLD, confirmedRecForLog,
							recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
							estimatedPnlPct, decisionActionReasonForLog, "ORDER_ERROR", trailStateForLog,
							continuationStateForLog, flipGateReasonForLog, qualityScoreForLogFinal,
							qualityConfirmReasonForLog, trendHoldActiveForLog,
							trendHoldReasonForLog, flipQualityScoreForLog,
							tpTrailingStateForLog, trendAlignedWithPositionFinal, exitBlockedByTrendAlignedActionFinal);
				})
				.onErrorResume(error -> Mono.empty())
				.subscribe();
	}

	public void onWarmupFiveMinuteCandle(String symbol, Candle candle) {
		SymbolState symbolState = symbolStates.computeIfAbsent(symbol, ignored -> new SymbolState());
		symbolState.updateFiveMinuteIndicators(candle);
	}

	public void onClosedFiveMinuteCandle(String symbol, Candle candle) {
		SymbolState symbolState = symbolStates.computeIfAbsent(symbol, ignored -> new SymbolState());
		symbolState.updateFiveMinuteIndicators(candle);
	}

	private int resolveFiveMinDir(int score5m) {
		if (score5m > 0) {
			return 1;
		}
		if (score5m < 0) {
			return -1;
		}
		return 0;
	}

	private TrailState resolveTrailState(SymbolState state, PositionState current, EntryState entryState,
			int fiveMinDir, double close) {
		if (current == PositionState.NONE || entryState == null || entryState.entryPrice() == null
				|| entryState.entryPrice().signum() <= 0) {
			state.resetTrailState();
			return TrailState.empty();
		}
		if (state.entryTimeMs != entryState.entryTimeMs()) {
			state.peakPriceSinceEntry = close;
			state.troughPriceSinceEntry = close;
			state.trailActive = false;
			state.entryTimeMs = entryState.entryTimeMs();
		}
		if (current == PositionState.LONG) {
			state.peakPriceSinceEntry = Double.isNaN(state.peakPriceSinceEntry)
					? close
					: Math.max(state.peakPriceSinceEntry, close);
		} else {
			state.troughPriceSinceEntry = Double.isNaN(state.troughPriceSinceEntry)
					? close
					: Math.min(state.troughPriceSinceEntry, close);
		}
		double profitPct = resolveProfitPct(entryState.side(), entryState.entryPrice(), close);
		if (!Double.isNaN(profitPct) && profitPct >= strategyProperties.trailActivatePct()) {
			state.trailActive = true;
		}
		Double trailStop = null;
		boolean trailStopHit = false;
		if (state.trailActive) {
			double retracePct = strategyProperties.trailRetracePct() / 100.0;
			if (current == PositionState.LONG) {
				double peak = Double.isNaN(state.peakPriceSinceEntry) ? close : state.peakPriceSinceEntry;
				trailStop = peak * (1.0 - retracePct);
				trailStopHit = close <= trailStop;
			} else {
				double trough = Double.isNaN(state.troughPriceSinceEntry) ? close : state.troughPriceSinceEntry;
				trailStop = trough * (1.0 + retracePct);
				trailStopHit = close >= trailStop;
			}
		}
		boolean trendStillStrong = resolveTrendStillStrong(entryState.side(), fiveMinDir, close, state);
		return new TrailState(state.trailActive, state.peakPriceSinceEntry, state.troughPriceSinceEntry, trailStop,
				profitPct, trendStillStrong, false, trailStopHit);
	}

	private TpTrailingState resolveTpTrailingState(SymbolState state, PositionState current, EntryState entryState,
			double close) {
		if (current == PositionState.NONE || entryState == null || entryState.entryPrice() == null
				|| entryState.entryPrice().signum() <= 0) {
			state.resetTpTrailingState();
			return TpTrailingState.empty();
		}
		if (state.tpEntryTimeMs != entryState.entryTimeMs()) {
			state.resetTpTrailingState();
			state.tpEntryTimeMs = entryState.entryTimeMs();
		}
		double pnlPct = resolveProfitPct(entryState.side(), entryState.entryPrice(), close);
		TpTrailingDecision decision = evaluateTpTrailingDecision(
				strategyProperties.tpTrailingEnabled(),
				pnlPct,
				state.tpTrailingActive,
				state.maxPnlSeenPct,
				strategyProperties.tpStartPct(),
				strategyProperties.tpTrailPct());
		state.tpTrailingActive = decision.tpTrailingActive();
		state.maxPnlSeenPct = decision.maxPnlSeenPct();
		return new TpTrailingState(decision.tpTrailingActive(), decision.maxPnlSeenPct(),
				decision.trailingStopPct(), decision.allowExit());
	}

	private ContinuationState resolveContinuationState(SymbolState state, PositionState current, EntryState entryState,
			int fiveMinDir, int score1m, int qualityScore, double close, long nowMs) {
		if (current == PositionState.NONE || entryState == null || entryState.entryPrice() == null
				|| entryState.entryPrice().signum() <= 0) {
			state.resetContinuationState();
			return ContinuationState.empty();
		}
		if (state.continuationEntryTimeMs != entryState.entryTimeMs()) {
			state.resetContinuationState();
			state.bestFavorablePriceSinceEntry = close;
			state.continuationEntryTimeMs = entryState.entryTimeMs();
		}
		ContinuationDecision decision = evaluateContinuationDecision(
				entryState.side(),
				fiveMinDir,
				score1m,
				qualityScore,
				state.continuationActive,
				state.continuationStartTimeMs,
				state.bestFavorablePriceSinceEntry,
				close,
				nowMs,
				strategyProperties.continuationQualityMin(),
				strategyProperties.continuationMinBars(),
				strategyProperties.continuationMaxRetracePct());
		state.continuationActive = decision.continuationActive();
		state.continuationStartTimeMs = decision.continuationStartTimeMs();
		state.bestFavorablePriceSinceEntry = decision.bestFavorablePrice();
		return new ContinuationState(decision.continuationActive(), decision.continuationBars(),
				decision.bestFavorablePrice(), decision.retracePct(), decision.holdApplied());
	}

	static boolean shouldHoldTrendStrong(CtiDirection side, BigDecimal entryPrice, double close,
			int fiveMinDir, double ema20, double ema200, boolean emaReady, boolean trailStopHit,
			boolean stopLossExit, CtiDirection confirmedRec, double trendHoldMinProfitPct) {
		if (side == null || entryPrice == null || entryPrice.signum() <= 0 || close <= 0 || stopLossExit
				|| trailStopHit) {
			return false;
		}
		if (!emaReady) {
			return false;
		}
		double profitPct = resolveProfitPct(side, entryPrice, close);
		if (Double.isNaN(profitPct) || profitPct < trendHoldMinProfitPct) {
			return false;
		}
		boolean trendStillStrong = false;
		if (side == CtiDirection.LONG) {
			trendStillStrong = fiveMinDir == 1 && close > ema20 && close > ema200;
		} else if (side == CtiDirection.SHORT) {
			trendStillStrong = fiveMinDir == -1 && close < ema20 && close < ema200;
		}
		if (!trendStillStrong) {
			return false;
		}
		boolean oppositeStrong = side == CtiDirection.LONG
				? confirmedRec == CtiDirection.SHORT
				: side == CtiDirection.SHORT && confirmedRec == CtiDirection.LONG;
		return !oppositeStrong;
	}

	private boolean resolveTrendStillStrong(CtiDirection side, int fiveMinDir, double close, SymbolState state) {
		if (side == null || !state.isTrendEmaReady()) {
			return false;
		}
		if (side == CtiDirection.LONG) {
			return fiveMinDir == 1 && close > state.ema20_1mValue && close > state.ema200_5mValue;
		}
		if (side == CtiDirection.SHORT) {
			return fiveMinDir == -1 && close < state.ema20_1mValue && close < state.ema200_5mValue;
		}
		return false;
	}

	private boolean isEma20Break(PositionState current, SymbolState state, double close) {
		if (current == null || current == PositionState.NONE || !state.isEma20Ready()) {
			return false;
		}
		if (current == PositionState.LONG) {
			return close < state.ema20_1mValue;
		}
		if (current == PositionState.SHORT) {
			return close > state.ema20_1mValue;
		}
		return false;
	}

	private boolean resolveTrendAlignedWithPosition(PositionState current, int fiveMinDir, double bfr1m,
			double bfrPrev) {
		if (current == PositionState.LONG) {
			return fiveMinDir == 1 && (!Double.isNaN(bfr1m) && !Double.isNaN(bfrPrev) && bfr1m >= bfrPrev);
		}
		if (current == PositionState.SHORT) {
			return fiveMinDir == -1 && (!Double.isNaN(bfr1m) && !Double.isNaN(bfrPrev) && bfr1m <= bfrPrev);
		}
		return false;
	}

	private static double resolveProfitPct(CtiDirection side, BigDecimal entryPrice, double close) {
		if (side == null || entryPrice == null || entryPrice.signum() <= 0 || close <= 0) {
			return Double.NaN;
		}
		double entry = entryPrice.doubleValue();
		double profitPct = (close - entry) / entry * 100.0;
		return side == CtiDirection.SHORT ? -profitPct : profitPct;
	}

	private static boolean isStopLossExit(String reason) {
		return reason != null && reason.startsWith("EXIT_STOP_LOSS");
	}

	private static boolean isTakeProfitExit(String reason) {
		return reason != null && reason.startsWith("EXIT_TAKE_PROFIT");
	}

	static ExitHoldDecision evaluateExitHoldDecision(boolean stopLossExit, boolean scoreExitConfirmed,
			boolean trendAlignedWithPosition) {
		if (stopLossExit) {
			return new ExitHoldDecision(false, null, false);
		}
		if (!scoreExitConfirmed) {
			if (trendAlignedWithPosition) {
				return new ExitHoldDecision(true, "HOLD_TREND_ALIGNED", true);
			}
			return new ExitHoldDecision(true, "EXIT_WAIT_REVERSAL", false);
		}
		return new ExitHoldDecision(false, null, false);
	}

	static int resolveDir(CtiDirection direction) {
		if (direction == null || direction == CtiDirection.NEUTRAL) {
			return 0;
		}
		return direction == CtiDirection.LONG ? 1 : -1;
	}

	private static int resolveOppositeDir(PositionState current) {
		return current == PositionState.LONG ? -1 : current == PositionState.SHORT ? 1 : 0;
	}

	private static int resolveBarsInPosition(EntryState entryState, long nowMs) {
		if (entryState == null || entryState.entryTimeMs() <= 0) {
			return 0;
		}
		return (int) ((nowMs - entryState.entryTimeMs()) / 60_000L) + 1;
	}

	static boolean shouldHoldPosition(PositionState posSide,
			double close1m,
			IndicatorsSnapshot ind,
			int recommendationDir,
			int confirmedRecDir,
			int barsInPosition,
			StrategyProperties properties) {
		if (posSide == null || posSide == PositionState.NONE || ind == null || !properties.trendHoldEnabled()) {
			return false;
		}
		if (barsInPosition <= 0 || barsInPosition > properties.trendHoldMaxBars()) {
			return false;
		}
		if (!ind.adxReady() || ind.adx5m() == null || ind.adx5m() < properties.trendHoldMinAdx()) {
			return false;
		}
		double buffer = properties.ema20HoldBufferPct();
		if (posSide == PositionState.LONG) {
			return ind.ema200Ready()
					&& close1m > ind.ema200()
					&& ind.ema20Ready()
					&& close1m > ind.ema20() * (1.0 - buffer)
					&& ind.ctiTrendDir() == CtiDirection.LONG;
		}
		if (posSide == PositionState.SHORT) {
			return ind.ema200Ready()
					&& close1m < ind.ema200()
					&& ind.ema20Ready()
					&& close1m < ind.ema20() * (1.0 + buffer)
					&& ind.ctiTrendDir() == CtiDirection.SHORT;
		}
		return false;
	}

	static TpTrailingDecision evaluateTpTrailingDecision(boolean enabled, double pnlPct,
			boolean tpTrailingActive, double maxPnlSeenPct, double tpStartPct, double tpTrailPct) {
		if (!enabled || Double.isNaN(pnlPct)) {
			return new TpTrailingDecision(false, Double.NaN, null, true);
		}
		double maxSeen = Double.isNaN(maxPnlSeenPct) ? pnlPct : Math.max(maxPnlSeenPct, pnlPct);
		boolean active = tpTrailingActive || pnlPct >= tpStartPct;
		Double trailingStopPct = active ? maxSeen - tpTrailPct : null;
		boolean allowExit = !active || (trailingStopPct != null && pnlPct <= trailingStopPct);
		return new TpTrailingDecision(active, maxSeen, trailingStopPct, allowExit);
	}

	static TrendHoldFlipDecision evaluateTrendHoldFlipDecision(boolean trendHoldActive, int confirmedRecDir,
			int oppositeDir, int flipQualityScore, int flipQualityMin) {
		if (!trendHoldActive) {
			return new TrendHoldFlipDecision(true, flipQualityScore);
		}
		boolean allowFlip = confirmedRecDir == oppositeDir
				&& flipQualityScore >= flipQualityMin;
		return new TrendHoldFlipDecision(allowFlip, flipQualityScore);
	}

	private int resolveFlipQualityScore(PositionState current, double close, EntryFilterState entryFilterState,
			SymbolState state) {
		int oppositeDir = resolveOppositeDir(current);
		if (oppositeDir == 0) {
			return 0;
		}
		EntryQualityEvaluation evaluation = evaluateEntryQuality(
				oppositeDir,
				close,
				state.ema20_1mValue,
				state.isEma20Ready(),
				state.ema200_5mValue,
				state.isEma200Ready(),
				state.rsi9Value,
				state.isRsiReady(),
				entryFilterState.volume(),
				state.volumeSma10Value,
				state.isVolumeSmaReady(),
				state.atr14Value,
				state.isAtrReady(),
				state.atrSma20Value,
				state.isAtrSmaReady(),
				true,
				strategyProperties);
		return evaluation == null ? 0 : evaluation.qualityScore();
	}

	private void logEntryFilterState(String symbol, SymbolState state, long nowMs, double close, double prevClose,
			EntryFilterState entryFilterState, EntryDecision entryDecision) {
		if (warmupMode) {
			return;
		}
		LOGGER.info("EVENT=ENTRY_FILTER_STATE symbol={} fiveMinDir={} lastFiveMinDirPrev={} flipTimeMs={} prevClose1m={} scoreAligned={} entryTrigger={} triggerReason={} blockReason={} ema20_1m={} ema200_5m={} rsi9={} vol={} volSma10={} atr14={} atrSma20={} qualityScore={} ema200Ok={} ema20Ok={} rsiOk={} volOk={} atrOk={} qualityBlockReason={}",
				symbol,
				state.lastFiveMinDir,
				state.prevFiveMinDir,
				entryFilterState.flipTimeMs() == 0L ? "NA" : entryFilterState.flipTimeMs(),
				Double.isNaN(prevClose) ? "NA" : String.format("%.8f", prevClose),
				entryFilterState.scoreAligned(),
				entryFilterState.entryTrigger(),
				entryFilterState.triggerReason(),
				entryDecision.blockReason(),
				Double.isNaN(entryFilterState.ema20_1m()) ? "NA" : String.format("%.8f", entryFilterState.ema20_1m()),
				Double.isNaN(entryFilterState.ema200_5m()) ? "NA" : String.format("%.8f",
						entryFilterState.ema200_5m()),
				Double.isNaN(entryFilterState.rsi9()) ? "NA" : String.format("%.2f", entryFilterState.rsi9()),
				Double.isNaN(entryFilterState.volume()) ? "NA" : String.format("%.2f", entryFilterState.volume()),
				Double.isNaN(entryFilterState.volumeSma10()) ? "NA" : String.format("%.2f",
						entryFilterState.volumeSma10()),
				Double.isNaN(entryFilterState.atr14()) ? "NA" : String.format("%.6f", entryFilterState.atr14()),
				Double.isNaN(entryFilterState.atrSma20()) ? "NA" : String.format("%.6f",
						entryFilterState.atrSma20()),
				entryFilterState.qualityScore(),
				entryFilterState.ema200Ok(),
				entryFilterState.ema20Ok(),
				entryFilterState.rsiOk(),
				entryFilterState.volOk(),
				entryFilterState.atrOk(),
				entryFilterState.qualityBlockReason());
	}

	private EntryFilterState resolveEntryFilterState(int fiveMinDir, int score1m, double close, double volume1m,
			long nowMs, SymbolState state) {
		EntryFilterInputs inputs = new EntryFilterInputs(
				fiveMinDir,
				score1m,
				close,
				volume1m,
				nowMs,
				state.fiveMinFlipTimeMs,
				state.fiveMinFlipPrice,
				state.ema20_1mValue,
				state.isEma20Ready(),
				state.ema200_5mValue,
				state.isEma200Ready(),
				state.rsi9Value,
				state.isRsiReady(),
				state.volumeSma10Value,
				state.isVolumeSmaReady(),
				state.atr14Value,
				state.isAtrReady(),
				state.atrSma20Value,
				state.isAtrSmaReady());
		return buildEntryFilterState(inputs, strategyProperties);
	}

	static EntryFilterState buildEntryFilterState(EntryFilterInputs inputs, StrategyProperties properties) {
		boolean hasDir = inputs.fiveMinDir() != 0;
		boolean scoreAlignedLong = inputs.score1m() == 1;
		boolean scoreAlignedShort = inputs.score1m() == -1;
		boolean entryTrigger = false;
		String triggerReason = "NONE";
		boolean scoreAligned = false;
		if (inputs.fiveMinDir() == 1) {
			scoreAligned = scoreAlignedLong;
			entryTrigger = scoreAlignedLong;
		} else if (inputs.fiveMinDir() == -1) {
			scoreAligned = scoreAlignedShort;
			entryTrigger = scoreAlignedShort;
		}

		if (scoreAligned) {
			triggerReason = "SCORE";
		}
		EntryQualityEvaluation qualityEvaluation = evaluateEntryQuality(
				inputs.fiveMinDir(),
				inputs.close(),
				inputs.ema20_1m(),
				inputs.ema20Ready(),
				inputs.ema200_5m(),
				inputs.ema200Ready(),
				inputs.rsi9(),
				inputs.rsiReady(),
				inputs.volume1m(),
				inputs.volumeSma10(),
				inputs.volumeSmaReady(),
				inputs.atr14(),
				inputs.atrReady(),
				inputs.atrSma20(),
				inputs.atrSmaReady(),
				entryTrigger,
				properties);
		return new EntryFilterState(inputs.fiveMinDir(), entryTrigger, scoreAligned,
				triggerReason, inputs.fiveMinFlipTimeMs(), inputs.fiveMinFlipPrice(), inputs.ema20_1m(),
				inputs.ema200_5m(), inputs.rsi9(), inputs.volume1m(), inputs.volumeSma10(), inputs.atr14(),
				inputs.atrSma20(), qualityEvaluation.qualityScore(), qualityEvaluation.ema200Ok(),
				qualityEvaluation.ema20Ok(), qualityEvaluation.rsiOk(), qualityEvaluation.volOk(),
				qualityEvaluation.atrOk(), qualityEvaluation.blockReason());
	}

	static EntryQualityEvaluation evaluateEntryQuality(
			int fiveMinDir,
			double close,
			double ema20,
			boolean ema20Ready,
			double ema200,
			boolean ema200Ready,
			double rsi9,
			boolean rsiReady,
			double volume,
			double volSma10,
			boolean volReady,
			double atr14,
			boolean atrReady,
			double atrSma20,
			boolean atrSmaReady,
			boolean entryTrigger,
			StrategyProperties properties) {
		boolean ema200Ok = ema200Ready && ((fiveMinDir == 1 && close > ema200) || (fiveMinDir == -1 && close < ema200));
		boolean ema20Ok = ema20Ready && ((fiveMinDir == 1 && close > ema20) || (fiveMinDir == -1 && close < ema20));
		boolean rsiOk = rsiReady
				&& ((fiveMinDir == 1 && rsi9 >= 45.0 && rsi9 <= 65.0)
						|| (fiveMinDir == -1 && rsi9 >= 35.0 && rsi9 <= 55.0));
		boolean volOk = volReady && volSma10 > 0 && volume > volSma10 * properties.volSpikeMult();
		boolean atrOk = atrReady && atrSmaReady && atrSma20 > 0 && atr14 < atrSma20 * properties.atrCapMult();

		int score = 50;
		if (ema200Ready) {
			score += ema200Ok ? 15 : -8;
		}
		if (ema20Ready) {
			score += ema20Ok ? 10 : -5;
		}
		if (rsiReady) {
			score += rsiOk ? 10 : -5;
		}
		if (volReady && volOk) {
			score += 10;
		}
		if (atrReady && atrSmaReady) {
			score += atrOk ? 5 : -5;
		}
		int clamped = Math.max(0, Math.min(100, score));
		String blockReason = null;
		if (entryTrigger && rsiReady) {
			boolean extremeRsi = rsi9 > 75.0 || rsi9 < 25.0;
			if (extremeRsi) {
				blockReason = "ENTRY_BLOCK_EXTREME_RSI";
			}
		}
		int qualityMin = properties.entryQualityMin();
		if (entryTrigger && blockReason == null && atrReady && atrSmaReady && atrSma20 > 0) {
			if (atr14 < atrSma20 * 0.8) {
				qualityMin = Math.max(0, qualityMin - 5);
			}
		}
		if (entryTrigger && blockReason == null && qualityMin > 0 && clamped < qualityMin) {
			blockReason = "ENTRY_BLOCK_LOW_QUALITY";
		}
		if (entryTrigger && blockReason == null && atrReady && atrSmaReady && atrSma20 > 0) {
			if (atr14 > atrSma20 * properties.extremeAtrBlockMult()) {
				blockReason = "ENTRY_BLOCK_EXTREME_ATR";
			}
		}
		return new EntryQualityEvaluation(clamped, ema200Ok, ema20Ok, rsiOk, volOk, atrOk, blockReason);
	}

	static ContinuationDecision evaluateContinuationDecision(CtiDirection side, int fiveMinDir, int score1m,
			int qualityScore, boolean continuationActive, long continuationStartTimeMs, double bestFavorablePrice,
			double close, long nowMs, int continuationQualityMin, int continuationMinBars,
			double continuationMaxRetracePct) {
		if (side == null || close <= 0) {
			return new ContinuationDecision(false, 0L, Double.NaN, 0, Double.NaN, false);
		}
		double best = bestFavorablePrice;
		if (side == CtiDirection.LONG) {
			best = Double.isNaN(best) ? close : Math.max(best, close);
		} else if (side == CtiDirection.SHORT) {
			best = Double.isNaN(best) ? close : Math.min(best, close);
		}
		boolean trendContinuation = side == CtiDirection.LONG
				? fiveMinDir == 1 && score1m == 1
				: side == CtiDirection.SHORT && fiveMinDir == -1 && score1m == -1;
		trendContinuation = trendContinuation && qualityScore >= continuationQualityMin;
		boolean active = continuationActive;
		long startTime = continuationStartTimeMs;
		if (!active && trendContinuation) {
			active = true;
			startTime = nowMs;
		}
		int bars = 0;
		double retracePct = Double.NaN;
		boolean holdApplied = false;
		if (active && startTime > 0L) {
			bars = (int) ((nowMs - startTime) / 60_000L) + 1;
			if (best > 0) {
				if (side == CtiDirection.LONG) {
					retracePct = (best - close) / best * 100.0;
				} else if (side == CtiDirection.SHORT) {
					retracePct = (close - best) / best * 100.0;
				}
			}
			if (bars < continuationMinBars) {
				holdApplied = true;
			} else if (!Double.isNaN(retracePct) && retracePct <= continuationMaxRetracePct) {
				holdApplied = true;
			}
		}
		return new ContinuationDecision(active, startTime, best, bars, retracePct, holdApplied);
	}

	static FlipGateResult evaluateFlipGate(int qualityScore, int flipFastQuality) {
		if (qualityScore >= flipFastQuality) {
			return new FlipGateResult(true, "FAST_QUALITY");
		}
		return new FlipGateResult(false, "QUALITY_BELOW_FAST");
	}

	private EntryDecision resolveEntryDecision(PositionState current, SymbolState state, EntryFilterState entryFilterState) {
		return evaluateEntryDecision(current, entryFilterState, strategyProperties);
	}

	static EntryDecision evaluateEntryDecision(PositionState current, EntryFilterState entryFilterState,
			StrategyProperties properties) {
		if (current != PositionState.NONE) {
			return new EntryDecision(null, null, null);
		}
		if (entryFilterState.fiveMinDir() == 0) {
			return new EntryDecision(null, "NO_5M_SUPPORT", "NO_5M_SUPPORT");
		}
		if (entryFilterState.qualityBlockReason() != null) {
			return new EntryDecision(null, entryFilterState.qualityBlockReason(),
					entryFilterState.qualityBlockReason());
		}
		if (entryFilterState.entryTrigger()) {
			CtiDirection confirmed = entryFilterState.fiveMinDir() == 1 ? CtiDirection.LONG : CtiDirection.SHORT;
			String reason = "ENTRY_NORMAL_SCORE_CONFIRMED";
			return new EntryDecision(confirmed, null, reason);
		}
		return new EntryDecision(null, null, null);
	}

	private EntryDecision applyPendingFlipGate(String symbol, PositionState current, EntryDecision entryDecision,
			EntryFilterState entryFilterState, long nowMs) {
		int pendingFlipDir = pendingFlipDirs.getOrDefault(symbol, 0);
		if (pendingFlipDir == 0) {
			return entryDecision;
		}
		if (current != PositionState.NONE) {
			return entryDecision;
		}
		long remainingMs = resolveFlipCooldownRemaining(symbol, nowMs);
		if (remainingMs > 0) {
			return entryDecision.withBlockReason("ENTRY_BLOCK_COOLDOWN");
		}
		if (orderTracker.openOrdersCount(symbol) > 0) {
			return entryDecision.withBlockReason("ENTRY_BLOCK_OPEN_ORDERS");
		}
		if (entryFilterState.fiveMinDir() != pendingFlipDir) {
			return entryDecision.withBlockReason("ENTRY_BLOCK_PENDING_DIR_MISMATCH");
		}
		if (entryDecision.confirmedRec() == null) {
			return entryDecision;
		}
		pendingFlipDirs.put(symbol, 0);
		return entryDecision;
	}

	record EntryFilterState(
			int fiveMinDir,
			boolean entryTrigger,
			boolean scoreAligned,
			String triggerReason,
			long flipTimeMs,
			double flipPrice,
			double ema20_1m,
			double ema200_5m,
			double rsi9,
			double volume,
			double volumeSma10,
			double atr14,
			double atrSma20,
			int qualityScore,
			boolean ema200Ok,
			boolean ema20Ok,
			boolean rsiOk,
			boolean volOk,
			boolean atrOk,
			String qualityBlockReason) {
	}

	record EntryFilterInputs(
			int fiveMinDir,
			int score1m,
			double close,
			double volume1m,
			long nowMs,
			long fiveMinFlipTimeMs,
			double fiveMinFlipPrice,
			double ema20_1m,
			boolean ema20Ready,
			double ema200_5m,
			boolean ema200Ready,
			double rsi9,
			boolean rsiReady,
			double volumeSma10,
			boolean volumeSmaReady,
			double atr14,
			boolean atrReady,
			double atrSma20,
			boolean atrSmaReady) {
	}

	record EntryDecision(
			CtiDirection confirmedRec,
			String blockReason,
			String decisionActionReason) {
		static EntryDecision defaultDecision() {
			return new EntryDecision(null, null, null);
		}

		EntryDecision withBlockReason(String reason) {
			return new EntryDecision(confirmedRec, reason, decisionActionReason);
		}
	}

	record ExitHoldDecision(boolean hold, String reason, boolean exitBlockedByTrendAligned) {
	}

	private record TrailState(
			boolean trailActive,
			double peakPriceSinceEntry,
			double troughPriceSinceEntry,
			Double trailStop,
			double profitPct,
			boolean trendStillStrong,
			boolean exitHoldApplied,
			boolean trailStopHit) {
		static TrailState empty() {
			return new TrailState(false, Double.NaN, Double.NaN, null, Double.NaN, false, false, false);
		}

		TrailState withExitHoldApplied(boolean applied) {
			return new TrailState(trailActive, peakPriceSinceEntry, troughPriceSinceEntry, trailStop, profitPct,
					trendStillStrong, applied, trailStopHit);
		}
	}

	private record ContinuationState(
			boolean continuationActive,
			int continuationBars,
			double bestFavorablePrice,
			double retracePct,
			boolean holdApplied) {
		static ContinuationState empty() {
			return new ContinuationState(false, 0, Double.NaN, Double.NaN, false);
		}

		ContinuationState withHoldApplied(boolean applied) {
			return new ContinuationState(continuationActive, continuationBars, bestFavorablePrice, retracePct, applied);
		}
	}

	record ContinuationDecision(
			boolean continuationActive,
			long continuationStartTimeMs,
			double bestFavorablePrice,
			int continuationBars,
			double retracePct,
			boolean holdApplied) {
	}

	record FlipGateResult(boolean allowFlip, String reason) {
	}

	record EntryQualityEvaluation(
			int qualityScore,
			boolean ema200Ok,
			boolean ema20Ok,
			boolean rsiOk,
			boolean volOk,
			boolean atrOk,
			String blockReason) {
	}

	record IndicatorsSnapshot(
			double ema200,
			boolean ema200Ready,
			double ema20,
			boolean ema20Ready,
			double rsi9,
			boolean rsiReady,
			Double adx5m,
			boolean adxReady,
			CtiDirection ctiTrendDir) {
	}

	record TrendHoldFlipDecision(
			boolean allowFlip,
			int flipQualityScore) {
	}

	private record TpTrailingState(
			boolean tpTrailingActive,
			double maxPnlSeenPct,
			Double trailingStopPct,
			boolean allowExit) {
		static TpTrailingState empty() {
			return new TpTrailingState(false, Double.NaN, null, true);
		}
	}

	record TpTrailingDecision(
			boolean tpTrailingActive,
			double maxPnlSeenPct,
			Double trailingStopPct,
			boolean allowExit) {
	}

	private static class SymbolState {
		private int lastFiveMinDir;
		private int prevFiveMinDir;
		private long fiveMinFlipTimeMs;
		private double fiveMinFlipPrice;
		private double prevClose1m = Double.NaN;
		private long last1mCloseTime;
		private long last5mCloseTime;
		private double peakPriceSinceEntry = Double.NaN;
		private double troughPriceSinceEntry = Double.NaN;
		private boolean trailActive;
		private long entryTimeMs;
		private boolean tpTrailingActive;
		private double maxPnlSeenPct = Double.NaN;
		private long tpEntryTimeMs;
		private boolean continuationActive;
		private long continuationStartTimeMs;
		private long continuationEntryTimeMs;
		private double bestFavorablePriceSinceEntry = Double.NaN;
		private final BarSeries series1m = new BaseBarSeriesBuilder().withName("1m").build();
		private final BarSeries series5m = new BaseBarSeriesBuilder().withName("5m").build();
		private final ClosePriceIndicator closePrice1m = new ClosePriceIndicator(series1m);
		private final ClosePriceIndicator closePrice5m = new ClosePriceIndicator(series5m);
		private final EMAIndicator ema20_1m = new EMAIndicator(closePrice1m, EMA_20_PERIOD);
		private final EMAIndicator ema200_5m = new EMAIndicator(closePrice5m, EMA_200_PERIOD);
		private final RSIIndicator rsi9_1m = new RSIIndicator(closePrice1m, RSI_9_PERIOD);
		private final ATRIndicator atr14_1m = new ATRIndicator(series1m, ATR_14_PERIOD);
		private final SMAIndicator atrSma20_1m = new SMAIndicator(atr14_1m, ATR_SMA_20_PERIOD);
		private final VolumeIndicator volume1m = new VolumeIndicator(series1m);
		private final SMAIndicator volumeSma10_1m = new SMAIndicator(volume1m, VOLUME_SMA_10_PERIOD);
		private double ema20_1mValue = Double.NaN;
		private double ema200_5mValue = Double.NaN;
		private double rsi9Value = Double.NaN;
		private double atr14Value = Double.NaN;
		private double atrSma20Value = Double.NaN;
		private double volumeSma10Value = Double.NaN;

		private void updateOneMinuteIndicators(Candle candle) {
			if (candle.closeTime() <= last1mCloseTime) {
				return;
			}
			last1mCloseTime = candle.closeTime();
			series1m.addBar(new BaseBar(Duration.ofMinutes(1),
					java.time.Instant.ofEpochMilli(candle.closeTime()).atZone(java.time.ZoneOffset.UTC),
					BigDecimal.valueOf(candle.open()),
					BigDecimal.valueOf(candle.high()),
					BigDecimal.valueOf(candle.low()),
					BigDecimal.valueOf(candle.close()),
					BigDecimal.valueOf(candle.volume())));
			int index = series1m.getEndIndex();
			ema20_1mValue = ema20_1m.getValue(index).doubleValue();
			rsi9Value = rsi9_1m.getValue(index).doubleValue();
			atr14Value = atr14_1m.getValue(index).doubleValue();
			atrSma20Value = atrSma20_1m.getValue(index).doubleValue();
			volumeSma10Value = volumeSma10_1m.getValue(index).doubleValue();
		}

		private void updateFiveMinuteIndicators(Candle candle) {
			if (candle.closeTime() <= last5mCloseTime) {
				return;
			}
			last5mCloseTime = candle.closeTime();
			series5m.addBar(new BaseBar(Duration.ofMinutes(5),
					java.time.Instant.ofEpochMilli(candle.closeTime()).atZone(java.time.ZoneOffset.UTC),
					BigDecimal.valueOf(candle.open()),
					BigDecimal.valueOf(candle.high()),
					BigDecimal.valueOf(candle.low()),
					BigDecimal.valueOf(candle.close()),
					BigDecimal.valueOf(candle.volume())));
			int index = series5m.getEndIndex();
			ema200_5mValue = ema200_5m.getValue(index).doubleValue();
		}

		private boolean isTrendEmaReady() {
			return isEma20Ready() && isEma200Ready();
		}

		private boolean isEma20Ready() {
			return series1m.getBarCount() >= EMA_20_PERIOD;
		}

		private boolean isEma200Ready() {
			return series5m.getBarCount() >= EMA_200_PERIOD;
		}

		private boolean isRsiReady() {
			return series1m.getBarCount() >= RSI_9_PERIOD;
		}

		private boolean isVolumeSmaReady() {
			return series1m.getBarCount() >= VOLUME_SMA_10_PERIOD;
		}

		private boolean isAtrReady() {
			return series1m.getBarCount() >= ATR_14_PERIOD;
		}

		private boolean isAtrSmaReady() {
			return series1m.getBarCount() >= ATR_SMA_20_PERIOD;
		}

		private void resetTrailState() {
			peakPriceSinceEntry = Double.NaN;
			troughPriceSinceEntry = Double.NaN;
			trailActive = false;
			entryTimeMs = 0L;
		}

		private void resetTpTrailingState() {
			tpTrailingActive = false;
			maxPnlSeenPct = Double.NaN;
			tpEntryTimeMs = 0L;
		}

		private void resetContinuationState() {
			continuationActive = false;
			continuationStartTimeMs = 0L;
			continuationEntryTimeMs = 0L;
			bestFavorablePriceSinceEntry = Double.NaN;
		}
	}

	private long resolveFlipCooldownRemaining(String symbol, long nowMs) {
		Long lastExit = lastOppositeExitAtMs.get(symbol);
		if (lastExit == null) {
			return 0L;
		}
		long remaining = strategyProperties.oppositeExitFlipCooldownMs() - (nowMs - lastExit);
		return Math.max(0L, remaining);
	}

	private Mono<Void> executeFlip(String symbol, PositionState target, double close) {
		PositionState current = positionStates.getOrDefault(symbol, PositionState.NONE);
		BigDecimal quantity = resolveQuantity(symbol, close);
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> {
					if (current == PositionState.NONE) {
						String correlationId = orderTracker.nextCorrelationId(symbol, "FLIP_OPEN");
						return openPosition(symbol, target, quantity, hedgeMode, correlationId)
								.doOnNext(response -> orderTracker.registerSubmitted(symbol, correlationId, response, false));
					}
					String closeCorrelationId = orderTracker.nextCorrelationId(symbol, "FLIP_CLOSE");
					String openCorrelationId = orderTracker.nextCorrelationId(symbol, "FLIP_OPEN");
					return closePosition(symbol, current, quantity, hedgeMode, closeCorrelationId)
							.doOnNext(response -> orderTracker.registerSubmitted(symbol, closeCorrelationId, response, true))
							.flatMap(response -> openPosition(symbol, target, quantity, hedgeMode, openCorrelationId)
									.doOnNext(openResponse -> orderTracker.registerSubmitted(symbol, openCorrelationId, openResponse, false)));
				})
				.doOnNext(response -> positionStates.put(symbol, target))
				.then();
	}

	private Mono<OrderResponse> closePosition(String symbol, PositionState current, BigDecimal quantity, boolean hedgeMode,
			String correlationId) {
		if (current == PositionState.NONE) {
			return Mono.empty();
		}
		String side = current == PositionState.LONG ? "SELL" : "BUY";
		String positionSide = hedgeMode ? current.name() : "";
		return orderClient.fetchPosition(symbol)
				.doOnNext(position -> applyExchangePosition(symbol, position, System.currentTimeMillis()))
				.flatMap(position -> {
					BigDecimal positionAmt = position.positionAmt();
					if (positionAmt == null || positionAmt.signum() == 0) {
						LOGGER.info("EVENT=CLOSE_SKIP_NO_POSITION symbol={} positionAmt={}", symbol, positionAmt);
						return Mono.empty();
					}
					BigDecimal closeQty = resolveCloseQuantity(symbol, positionAmt.abs());
					if (closeQty == null || closeQty.signum() <= 0) {
						LOGGER.warn("EVENT=CLOSE_QTY_INVALID symbol={} positionAmt={} closeQty={}", symbol,
								positionAmt, closeQty);
						return Mono.empty();
					}
					return orderClient.placeReduceOnlyMarketOrder(symbol, side, closeQty, positionSide, correlationId)
							.doOnError(error -> logReduceOnlyFailure(symbol, correlationId, error))
							.retryWhen(Retry.backoff(2, Duration.ofMillis(200))
									.filter(this::isRetryableCloseError))
							.filter(CtiLbDecisionEngine::shouldProceedAfterClose)
							.switchIfEmpty(Mono.error(new IllegalStateException("Close order rejected")));
				});
	}

	private Mono<OrderResponse> openPosition(String symbol, PositionState target, BigDecimal quantity, boolean hedgeMode,
			String correlationId) {
		String side = target == PositionState.LONG ? "BUY" : "SELL";
		String positionSide = hedgeMode ? target.name() : "";
		return orderClient.placeMarketOrder(symbol, side, quantity, positionSide, correlationId)
				.doOnError(error -> logOrderPlacementFailure(symbol, correlationId, error));
	}

	private BigDecimal resolveCloseQuantity(String symbol, BigDecimal positionAmt) {
		if (positionAmt == null || positionAmt.signum() <= 0) {
			return null;
		}
		BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
		BigDecimal stepSize = filters != null && filters.stepSize() != null
				? filters.stepSize()
				: strategyProperties.quantityStep();
		if (filters == null) {
			triggerFilterRefresh();
		}
		return floorToStep(positionAmt, stepSize);
	}

	private BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
		if (value == null || step == null || step.signum() <= 0) {
			return value;
		}
		BigDecimal ratio = value.divide(step, 0, java.math.RoundingMode.DOWN);
		return ratio.multiply(step, java.math.MathContext.DECIMAL64);
	}

	private Mono<OrderResponse> openPosition(String symbol, PositionState target, BigDecimal quantity) {
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> {
					String correlationId = orderTracker.nextCorrelationId(symbol, "ENTRY");
					return openPosition(symbol, target, quantity, hedgeMode, correlationId)
							.doOnNext(response -> orderTracker.registerSubmitted(symbol, correlationId, response, false));
				});
	}

	BigDecimal resolveQuantity(String symbol, double close) {
		BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
		if (filters == null) {
			triggerFilterRefresh();
			return null;
		}
		BigDecimal targetNotional = resolveTargetNotionalUsdt();
		BigDecimal stepSize = filters.stepSize() != null ? filters.stepSize() : strategyProperties.quantityStep();
		return CtiLbDecisionEngine.resolveQuantity(targetNotional, close, stepSize);
	}

	private BigDecimal resolveTargetNotionalUsdt() {
		return CtiLbDecisionEngine.resolveTargetNotional(
				resolveNotionalUsdt(),
				strategyProperties.maxPositionUsdt(),
				DEFAULT_NOTIONAL_USDT);
	}

	private BigDecimal resolveNotionalUsdt() {
		BigDecimal notional = strategyProperties.positionNotionalUsdt();
		if (notional == null || notional.signum() <= 0) {
			return DEFAULT_NOTIONAL_USDT;
		}
		return notional;
	}

	private BigDecimal resolveMaxPositionUsdt() {
		BigDecimal maxPosition = strategyProperties.maxPositionUsdt();
		if (maxPosition == null || maxPosition.signum() <= 0) {
			return DEFAULT_NOTIONAL_USDT;
		}
		return maxPosition;
	}

	private long resolveMinHoldMs() {
		long configured = strategyProperties.minHoldMs();
		if (configured <= 0) {
			return DEFAULT_MIN_HOLD_MS;
		}
		return configured;
	}

	private BigDecimal resolveFeeBps() {
		return DEFAULT_FEE_BPS;
	}

	private void logDecision(String symbol, ScoreSignal signal, double close, SignalAction action,
			CtiDirection confirmedRec, CtiDirection recommendationUsed, CtiDirection recommendationRaw,
			BigDecimal resolvedQty,
			EntryState entryState, Double estimatedPnlPct, String decisionActionReason, String decisionBlockReason,
			TrailState trailState, ContinuationState continuationState, String flipGateReason,
			Integer qualityScore, String qualityConfirmReason, Boolean trendHoldActive,
			String trendHoldReason, Integer flipQualityScore,
			TpTrailingState tpTrailingState, Boolean trendAlignedWithPosition,
			Boolean exitBlockedByTrendAligned) {
		logSummaryIfNeeded(signal.closeTime());
		String decisionAction = resolveDecisionAction(action);
		String insufficientReason = resolveInsufficientReason(signal);
		BinanceFuturesOrderClient.ExchangePosition exchangePosition = exchangePositions.get(symbol);
		String exchangePositionSide = resolveExchangePositionSide(exchangePosition);
		Boolean desync = stateDesyncBySymbol.get(symbol);
		BigDecimal entryPrice = entryState == null ? null : entryState.entryPrice();
		int openOrders = orderTracker.openOrdersCount(symbol);
		Integer pendingFlipDir = pendingFlipDirs.getOrDefault(symbol, 0);
		Long cooldownRemainingMs = resolveFlipCooldownRemaining(symbol, signal.closeTime());
		TrailState effectiveTrail = trailState == null ? TrailState.empty() : trailState;
		ContinuationState effectiveContinuation = continuationState == null
				? ContinuationState.empty()
				: continuationState;
		String flipGateReasonValue = flipGateReason == null ? "NA" : flipGateReason;
		Integer qualityScoreValue = qualityScore == null ? 0 : qualityScore;
		String qualityConfirmReasonValue = qualityConfirmReason == null ? "NA" : qualityConfirmReason;
		Boolean trendHoldActiveValue = trendHoldActive == null ? false : trendHoldActive;
		String trendHoldReasonValue = trendHoldReason == null ? "NA" : trendHoldReason;
		Integer flipQualityScoreValue = flipQualityScore == null ? 0 : flipQualityScore;
		TpTrailingState effectiveTpTrailing = tpTrailingState == null ? TpTrailingState.empty() : tpTrailingState;
		Boolean trendAlignedValue = trendAlignedWithPosition == null ? false : trendAlignedWithPosition;
		Boolean exitBlockedByTrendAlignedValue = exitBlockedByTrendAligned == null ? false : exitBlockedByTrendAligned;
		DecisionLogDto dto = new DecisionLogDto(
				symbol,
				signal.closeTime(),
				close,
				signal.cti1mValue(),
				signal.cti1mPrev(),
				signal.cti5mValue(),
				signal.adx5m(),
				signal.adxGate(),
				signal.adxGateReason(),
				signal.adxReady(),
				signal.cti5mReady(),
				signal.cti5mBarsSeen(),
				signal.cti5mPeriod(),
				signal.adx5mBarsSeen(),
				signal.adx5mPeriod(),
				signal.insufficientData(),
				insufficientReason,
				signal.score1m(),
				signal.score5m(),
				signal.hamCtiScore(),
				signal.ctiDirScore(),
				signal.macdScore(),
				scoreLong(signal.score1m(), signal.score5m(), signal.macdScore()),
				scoreShort(signal.score1m(), signal.score5m(), signal.macdScore()),
				signal.adjustedScore(),
				signal.bias(),
				recommendationRaw,
				recommendationUsed,
				confirmedRec,
				signal.recReason().name(),
				decisionAction,
				decisionActionReason,
				effectiveEnableOrders(),
				resolvedQty,
				entryPrice,
				estimatedPnlPct,
				strategyProperties.quantityStep(),
				strategyProperties.positionNotionalUsdt(),
				strategyProperties.maxPositionUsdt(),
				hedgeModeBySymbol.get(symbol),
				exchangePositionSide,
				exchangePosition == null ? null : exchangePosition.positionAmt(),
				desync,
				decisionBlockReason,
				trendAlignedValue,
				exitBlockedByTrendAlignedValue,
				recommendationRaw,
				formatPositionSide(positionStates.getOrDefault(symbol, PositionState.NONE)),
				entryState == null ? null : entryState.quantity(),
				openOrders,
				pendingFlipDir,
				cooldownRemainingMs,
				flipCount.longValue(),
				effectiveTrail.trailActive(),
				effectiveTrail.peakPriceSinceEntry(),
				effectiveTrail.troughPriceSinceEntry(),
				effectiveTrail.trailStop(),
				effectiveTrail.profitPct(),
				effectiveTrail.trendStillStrong(),
				effectiveTrail.exitHoldApplied(),
				effectiveContinuation.continuationActive(),
				effectiveContinuation.continuationBars(),
				effectiveContinuation.bestFavorablePrice(),
				effectiveContinuation.retracePct(),
				flipGateReasonValue,
				qualityScoreValue,
				qualityConfirmReasonValue,
				trendHoldActiveValue,
				trendHoldReasonValue,
				flipQualityScoreValue,
				effectiveTpTrailing.tpTrailingActive(),
				effectiveTpTrailing.maxPnlSeenPct(),
				effectiveTpTrailing.trailingStopPct());
		LOGGER.info(StrategyLogLineBuilder.buildDecisionLine(dto));
	}

	private String resolveHoldReason(ScoreSignal signal, boolean hasSignal, boolean confirmationMet) {
		if (signal.recReason() == CtiScoreCalculator.RecReason.TIE_HOLD) {
			return "TIE_HOLD";
		}
		return CtiLbDecisionEngine.resolveHoldReason(hasSignal, confirmationMet);
	}

	private String validateMinTrade(String symbol, BigDecimal quantity, double price) {
		if (!symbolFilterService.filtersReady()) {
			triggerFilterRefresh();
			return "WAIT_FILTERS";
		}
		if (quantity == null || quantity.signum() <= 0) {
			BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
			if (filters == null) {
				triggerFilterRefresh();
				return "WAIT_FILTERS";
			}
			return "QTY_ZERO_AFTER_STEP";
		}
		BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
		if (filters == null) {
			triggerFilterRefresh();
			return "WAIT_FILTERS";
		}
		if (filters.minQty() != null && quantity.compareTo(filters.minQty()) < 0) {
			return "QTY_TOO_SMALL";
		}
		if (filters.minNotional() != null) {
			BigDecimal notional = quantity.multiply(BigDecimal.valueOf(price), MathContext.DECIMAL64);
			if (notional.compareTo(filters.minNotional()) < 0) {
				return "NOTIONAL_TOO_SMALL";
			}
		}
		return null;
	}

	private boolean isRetryableCloseError(Throwable error) {
		if (error instanceof TimeoutException
				|| error instanceof SocketTimeoutException
				|| error instanceof IOException
				|| error instanceof PrematureCloseException
				|| error instanceof WebClientRequestException) {
			return true;
		}
		Throwable rootCause = rootCause(error);
		if (rootCause instanceof WebClientResponseException responseException) {
			int status = responseException.getStatusCode().value();
			return status >= 500 || status == 429;
		}
		if (rootCause instanceof IllegalStateException) {
			BinanceStatusBody statusBody = extractStatusBody(rootCause.getMessage());
			if (statusBody != null && statusBody.status() != null) {
				int status = statusBody.status();
				if (status >= 500 || status == 429) {
					return true;
				}
			}
			Integer code = extractBinanceCode(statusBody == null ? null : statusBody.body());
			if (code != null) {
				return false;
			}
		}
		return false;
	}

	private void logReduceOnlyFailure(String symbol, String correlationId, Throwable error) {
		Throwable rootCause = rootCause(error);
		LOGGER.error("EVENT=CLOSE_ORDER_FAILED symbol={} correlationId={} error={}", symbol, correlationId,
				error.getMessage(), error);
		logBinanceFailureDetails("EVENT=CLOSE_ORDER_FAILED", symbol, correlationId, rootCause);
	}

	private void logOrderPlacementFailure(String symbol, String correlationId, Throwable error) {
		Throwable rootCause = rootCause(error);
		LOGGER.error("EVENT=ORDER_FAILED symbol={} correlationId={} error={}", symbol, correlationId,
				error.getMessage(), error);
		logBinanceFailureDetails("EVENT=ORDER_FAILED", symbol, correlationId, rootCause);
	}

	private void logBinanceFailureDetails(String event, String symbol, String correlationId, Throwable rootCause) {
		if (rootCause == null) {
			return;
		}
		String message = rootCause.getMessage();
		BinanceStatusBody statusBody = extractStatusBody(message);
		if (statusBody != null) {
			LOGGER.error("{}_DETAILS symbol={} correlationId={} status={} body={}", event, symbol, correlationId,
					statusBody.status(), statusBody.body());
		} else if (message != null) {
			LOGGER.error("{}_DETAILS symbol={} correlationId={} message={}", event, symbol, correlationId, message);
		}
		if (rootCause instanceof WebClientResponseException responseException) {
			LOGGER.error("{}_HTTP symbol={} correlationId={} status={} body={}", event, symbol, correlationId,
					responseException.getStatusCode().value(), responseException.getResponseBodyAsString());
		}
	}

	private Throwable rootCause(Throwable error) {
		Throwable current = error;
		while (current != null && current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

	private BinanceStatusBody extractStatusBody(String message) {
		if (message == null) {
			return null;
		}
		Matcher matcher = BINANCE_STATUS_PATTERN.matcher(message);
		if (!matcher.find()) {
			return null;
		}
		Integer status = null;
		try {
			status = Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException ignored) {
			status = null;
		}
		int bodyIndex = message.indexOf("body=");
		String body = bodyIndex >= 0 ? message.substring(bodyIndex + 5).trim() : null;
		return new BinanceStatusBody(status, body);
	}

	private Integer extractBinanceCode(String body) {
		if (body == null) {
			return null;
		}
		Matcher matcher = BINANCE_CODE_PATTERN.matcher(body);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private record BinanceStatusBody(Integer status, String body) {
	}

	private void logOrderEvent(String event, String symbol, String reason, String side, BigDecimal qty,
			boolean reduceOnly, String positionSide, String correlationId, OrderResponse response, String error) {
		String orderId = response == null || response.orderId() == null ? "NA" : response.orderId().toString();
		String status = response == null ? "NA" : response.status();
		String errorValue = error == null ? "NA" : error;
		LOGGER.info("EVENT={} symbol={} reason={} side={} qty={} reduceOnly={} positionSide={} orderId={} correlationId={} status={} error={}",
				event,
				symbol,
				reason,
				side,
				qty == null ? "NA" : qty.stripTrailingZeros().toPlainString(),
				reduceOnly,
				positionSide == null || positionSide.isBlank() ? "NA" : positionSide,
				orderId,
				correlationId == null || correlationId.isBlank() ? "NA" : correlationId,
				status == null ? "NA" : status,
				errorValue);
	}

	private void recordSignalSnapshot(String symbol, String signalType, SignalAction action, ScoreSignal signal,
			long closeTime, double closePrice, BigDecimal quantity, PositionState side, PositionState target,
			EntryState entryState, String decisionActionReason, String decisionBlockReason,
			CtiDirection recommendationUsed, CtiDirection recommendationRaw, CtiDirection confirmedRec) {
		try {
			Files.createDirectories(SIGNAL_OUTPUT_DIR);
			Path outputFile = SIGNAL_OUTPUT_DIR.resolve(symbol + ".json");
			Object lock = signalFileLocks.computeIfAbsent(symbol, ignored -> new Object());
			synchronized (lock) {
				ArrayNode arrayNode = objectMapper.createArrayNode();
				if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
					JsonNode existing = objectMapper.readTree(outputFile.toFile());
					if (existing != null) {
						if (existing.isArray()) {
							arrayNode = (ArrayNode) existing;
						} else {
							arrayNode.add(existing);
						}
					}
				}
				ObjectNode payload = objectMapper.createObjectNode();
				payload.put("symbol", symbol);
				payload.put("signalType", signalType);
				payload.put("positionType", signalType);
				payload.put("action", action == null ? "NA" : action.name());
				payload.put("timestamp", closeTime);
				payload.put("price", closePrice);
				if (quantity != null) {
					payload.put("quantity", quantity.stripTrailingZeros().toPlainString());
				}
				if (side != null) {
					payload.put("side", side.name());
				}
				if (target != null) {
					payload.put("targetSide", target.name());
				}
				if (entryState != null) {
					payload.put("entrySide", entryState.side() == null ? "NA" : entryState.side().name());
					if (entryState.entryPrice() != null) {
						payload.put("entryPrice", entryState.entryPrice().stripTrailingZeros().toPlainString());
					}
					payload.put("entryTimeMs", entryState.entryTimeMs());
					if (entryState.quantity() != null) {
						payload.put("entryQuantity", entryState.quantity().stripTrailingZeros().toPlainString());
					}
				}
				if ("EXIT".equals(signalType)) {
					BigDecimal realizedPnl = calculateRealizedPnl(entryState, quantity, closePrice);
					if (realizedPnl != null) {
						payload.put("realizedPnl", realizedPnl.stripTrailingZeros().toPlainString());
					}
				}
				payload.put("decisionActionReason", decisionActionReason == null ? "NA" : decisionActionReason);
				payload.put("decisionBlockReason", decisionBlockReason == null ? "NA" : decisionBlockReason);
				payload.put("recommendationUsed", recommendationUsed == null ? "NA" : recommendationUsed.name());
				payload.put("recommendationRaw", recommendationRaw == null ? "NA" : recommendationRaw.name());
				payload.put("confirmedRecommendation", confirmedRec == null ? "NA" : confirmedRec.name());
				payload.set("signal", objectMapper.valueToTree(signal));
				arrayNode.add(payload);
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), arrayNode);
			}
		} catch (IOException error) {
			LOGGER.warn("EVENT=SIGNAL_SNAPSHOT_FAILED symbol={} type={} error={}", symbol, signalType,
					error.getMessage());
		}
	}

	private BigDecimal calculateRealizedPnl(EntryState entryState, BigDecimal quantity, double closePrice) {
		if (entryState == null || entryState.side() == null || entryState.entryPrice() == null
				|| entryState.entryPrice().signum() <= 0 || quantity == null || quantity.signum() <= 0
				|| closePrice <= 0) {
			return null;
		}
		BigDecimal close = BigDecimal.valueOf(closePrice);
		BigDecimal priceDiff = close.subtract(entryState.entryPrice());
		if (entryState.side() == CtiDirection.SHORT) {
			priceDiff = entryState.entryPrice().subtract(close);
		}
		return priceDiff.multiply(quantity);
	}

	private void logConfigSnapshot() {
		LOGGER.info("EVENT=CTI_CONFIG notionalUsdt={} maxPositionUsdt={} minHoldMs={} enableTieBreakBias={}"
				+ " flipCooldownMs={} maxFlipsPer5Min={}",
				resolveNotionalUsdt(),
				resolveMaxPositionUsdt(),
				resolveMinHoldMs(),
				strategyProperties.enableTieBreakBias(),
				strategyProperties.flipCooldownMs(),
				strategyProperties.maxFlipsPer5Min());
	}

	private void triggerFilterRefresh() {
		symbolFilterService.requestRefresh();
	}

	private EntryState resolveEntryState(String symbol, PositionState current, double close, long closeTime) {
		if (current == PositionState.NONE) {
			entryStates.remove(symbol);
			return null;
		}
		EntryState existing = entryStates.get(symbol);
		if (existing != null) {
			return existing;
		}
		BinanceFuturesOrderClient.ExchangePosition exchangePosition = exchangePositions.get(symbol);
		BigDecimal entryPrice = exchangePosition == null ? null : exchangePosition.entryPrice();
		if (entryPrice == null || entryPrice.signum() <= 0) {
			entryPrice = BigDecimal.valueOf(close);
		}
		BigDecimal qty = exchangePosition == null ? null : exchangePosition.positionAmt();
		if (qty != null) {
			qty = qty.abs();
		}
		CtiDirection side = current == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT;
		EntryState created = new EntryState(side, entryPrice, closeTime, qty);
		entryStates.put(symbol, created);
		return created;
	}

	private BigDecimal resolveExitQuantity(String symbol, EntryState entryState, double close) {
		if (entryState != null && entryState.quantity() != null && entryState.quantity().signum() > 0) {
			return entryState.quantity();
		}
		return resolveQuantity(symbol, close);
	}

	private void recordEntry(String symbol, PositionState target, OrderResponse response, BigDecimal fallbackQty,
			long closeTime, double closePrice) {
		BigDecimal entryPrice = response == null ? null : response.avgPrice();
		if (entryPrice == null || entryPrice.signum() <= 0) {
			entryPrice = BigDecimal.valueOf(closePrice);
		}
		BigDecimal qty = response == null ? null : response.executedQty();
		if (qty == null || qty.signum() <= 0) {
			qty = fallbackQty;
		}
		CtiDirection side = target == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT;
		entryStates.put(symbol, new EntryState(side, entryPrice, closeTime, qty));
	}

	private void recordFlip(String symbol, long closeTime, BigDecimal closePrice) {
		lastFlipTimes.put(symbol, closeTime);
		lastFlipPrices.put(symbol, closePrice);
		java.util.Deque<Long> flips = flipTimesBySymbol.computeIfAbsent(symbol, ignored -> new java.util.ArrayDeque<>());
		flips.addLast(closeTime);
		pruneFlipHistory(flips, closeTime);
	}

	private void pruneFlipHistory(java.util.Deque<Long> flips, long nowMs) {
		long cutoff = nowMs - 300_000L;
		while (!flips.isEmpty() && flips.peekFirst() < cutoff) {
			flips.pollFirst();
		}
	}

	private boolean isOrderResponseValid(OrderResponse response) {
		return response != null && response.orderId() != null;
	}

	private String formatPositionSide(PositionState state) {
		if (state == null || state == PositionState.NONE) {
			return "FLAT";
		}
		return state.name();
	}

	private SignalAction resolveAction(PositionState current, CtiDirection confirmedRec) {
		if (confirmedRec == CtiDirection.NEUTRAL) {
			return SignalAction.HOLD;
		}
		if (confirmedRec == CtiDirection.LONG) {
			if (current == PositionState.NONE) {
				return SignalAction.ENTER_LONG;
			}
			if (current == PositionState.SHORT) {
				return SignalAction.FLIP_TO_LONG;
			}
			return SignalAction.HOLD;
		}
		if (current == PositionState.NONE) {
			return SignalAction.ENTER_SHORT;
		}
		if (current == PositionState.LONG) {
			return SignalAction.FLIP_TO_SHORT;
		}
		return SignalAction.HOLD;
	}

	private void logFlip(String symbol, PositionState from, PositionState to, ScoreSignal signal, double price,
			CtiDirection rec, CtiDirection confirmedRec, SignalAction action, Long orderId) {
		if (warmupMode) {
			return;
		}
		FlipLogDto dto = new FlipLogDto(
				symbol,
				formatPositionSide(from),
				formatPositionSide(to),
				signal.closeTime(),
				BigDecimal.valueOf(price),
				signal.adjustedScore(),
				scoreLong(signal.score1m(), signal.score5m(), signal.macdScore()),
				scoreShort(signal.score1m(), signal.score5m(), signal.macdScore()),
				rec,
				confirmedRec,
				signal.cti1mValue(),
				signal.cti5mValue(),
				signal.adx5m(),
				resolveFlipAction(to),
				formatPositionSide(from),
				BigDecimal.ZERO,
				strategyProperties.enableOrders(),
				orderId == null ? "NA" : orderId.toString(),
				flipCount.longValue());
		LOGGER.info(StrategyLogLineBuilder.buildFlipLine(dto));
	}

	private void logSummaryIfNeeded(long closeTimeMs) {
		if (warmupMode) {
			return;
		}
		long last = lastSummaryAtMs.get();
		if (last == 0L) {
			lastSummaryAtMs.compareAndSet(0L, closeTimeMs);
			return;
		}
		if (closeTimeMs - last < 900_000L) {
			return;
		}
		if (!lastSummaryAtMs.compareAndSet(last, closeTimeMs)) {
			return;
		}
		long flips = flipCount.longValue();
		SummaryLogDto dto = new SummaryLogDto(
				symbolStates.size(),
				flips);
		LOGGER.info(StrategyLogLineBuilder.buildSummaryLine(dto));
	}

	private String resolveDecisionAction(SignalAction action) {
		if (action == SignalAction.FLIP_TO_LONG || action == SignalAction.ENTER_LONG) {
			return "FLIP_TO_LONG";
		}
		if (action == SignalAction.FLIP_TO_SHORT || action == SignalAction.ENTER_SHORT) {
			return "FLIP_TO_SHORT";
		}
		return "HOLD";
	}

	private String resolveDecisionBlockReason(ScoreSignal signal, SignalAction action, CtiDirection confirmedRec,
			PositionState current) {
		if (signal.insufficientData()) {
			return "INSUFFICIENT_DATA";
		}
		if (action != SignalAction.HOLD && current == PositionState.NONE) {
			if (signal.cti1mDir() == null || signal.cti5mDir() == null
					|| signal.cti1mDir() == CtiDirection.NEUTRAL
					|| signal.cti5mDir() == CtiDirection.NEUTRAL
					|| signal.cti1mDir() != signal.cti5mDir()) {
				return "CTI_DIR_MISMATCH";
			}
		}
		if (action != SignalAction.HOLD) {
			if (!signal.adxReady() || signal.adx5m() == null || signal.adx5m() <= 25.0) {
				return "ADX5M<=25";
			}
		}
		if (action != SignalAction.HOLD && !symbolFilterService.filtersReady()) {
			triggerFilterRefresh();
			return "WAIT_FILTERS";
		}
		if (action != SignalAction.HOLD && !effectiveEnableOrders()) {
			return "ORDERS_DISABLED";
		}
		if (action == SignalAction.HOLD && confirmedRec != CtiDirection.NEUTRAL) {
			if ((confirmedRec == CtiDirection.LONG && current == PositionState.LONG)
					|| (confirmedRec == CtiDirection.SHORT && current == PositionState.SHORT)) {
				return "EXCHANGE_SYNC_SAYS_ALREADY_IN_POSITION";
			}
		}
		return "OK_EXECUTED";
	}

	private void syncPositionIfNeeded(String symbol, long closeTime) {
		Long lastSync = lastPositionSyncMs.get(symbol);
		if (lastSync != null && closeTime - lastSync < POSITION_SYNC_INTERVAL_MS) {
			return;
		}
		lastPositionSyncMs.put(symbol, closeTime);
		orderClient.fetchPosition(symbol)
				.doOnNext(position -> {
					applyExchangePosition(symbol, position, closeTime);
				})
				.doOnError(error -> LOGGER.warn("EVENT=POSITION_SYNC symbol={} error={}", symbol, error.getMessage()))
				.subscribe();
	}

	private void applyExchangePosition(String symbol, BinanceFuturesOrderClient.ExchangePosition position, long closeTime) {
		PositionState local = positionStates.getOrDefault(symbol, PositionState.NONE);
		PositionState updated = resolvePositionState(position.positionAmt());
		positionStates.put(symbol, updated);
		boolean desync = local != updated;
		exchangePositions.put(symbol, position);
		stateDesyncBySymbol.put(symbol, desync);
		if (updated == PositionState.NONE) {
			entryStates.remove(symbol);
		} else {
			BigDecimal entryPrice = position.entryPrice();
			EntryState existing = entryStates.get(symbol);
			if (entryPrice == null || entryPrice.signum() <= 0) {
				entryPrice = existing == null ? null : existing.entryPrice();
			}
			if (entryPrice != null && entryPrice.signum() > 0) {
				CtiDirection side = updated == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT;
				BigDecimal qty = position.positionAmt() == null ? null : position.positionAmt().abs();
				if (existing == null) {
					entryStates.put(symbol, new EntryState(side, entryPrice, closeTime, qty));
				}
			}
		}
		StrategyLogV1.PositionSyncLogDto dto = new StrategyLogV1.PositionSyncLogDto(
				symbol,
				updated == PositionState.NONE ? "FLAT" : updated.name(),
				position.positionAmt(),
				formatPositionSide(local),
				desync);
		LOGGER.info(StrategyLogLineBuilder.buildPositionSyncLine(dto));
	}

	private String resolveExchangePositionSide(BinanceFuturesOrderClient.ExchangePosition position) {
		if (position == null) {
			return "NA";
		}
		BigDecimal amount = position.positionAmt();
		if (amount == null || amount.signum() == 0) {
			return "FLAT";
		}
		String side = position.positionSide();
		if (side == null || side.isBlank() || "BOTH".equalsIgnoreCase(side)) {
			return amount.signum() > 0 ? "LONG" : "SHORT";
		}
		return side;
	}

	private PositionState resolvePositionState(BigDecimal positionAmt) {
		if (positionAmt == null || positionAmt.signum() == 0) {
			return PositionState.NONE;
		}
		return positionAmt.signum() > 0 ? PositionState.LONG : PositionState.SHORT;
	}

	private String resolveInsufficientReason(ScoreSignal signal) {
		if (!signal.cti5mReady()) {
			return "CTI5M_NOT_READY";
		}
		return "OK";
	}

	private boolean effectiveEnableOrders() {
		return strategyProperties.enableOrders() && ordersEnabledOverride && !warmupMode;
	}

	private boolean shouldLogWarmupDecision(String symbol) {
		if (!warmupProperties.logDecisions()) {
			return false;
		}
		int every = warmupProperties.decisionLogEvery();
		if (every <= 0) {
			return true;
		}
		LongAdder counter = warmupDecisionCounters.computeIfAbsent(symbol, ignored -> new LongAdder());
		counter.increment();
		return counter.longValue() % every == 0;
	}

	private int scoreLong(int score1m, int score5m, int macdScore) {
		int longScore = (score1m > 0 ? 1 : 0) + (score5m > 0 ? 1 : 0);
		if (macdScore > 0) {
			longScore += 1;
		}
		return longScore;
	}

	private int scoreShort(int score1m, int score5m, int macdScore) {
		int shortScore = (score1m < 0 ? 1 : 0) + (score5m < 0 ? 1 : 0);
		if (macdScore < 0) {
			shortScore += 1;
		}
		return shortScore;
	}

	private String resolveFlipAction(PositionState target) {
		return target == PositionState.LONG ? "FLIP_TO_LONG" : "FLIP_TO_SHORT";
	}

	private String resolveFlipReason(PositionState current, PositionState target) {
		if (target == PositionState.LONG) {
			return "FLIP_SHORT_TO_LONG";
		}
		return "FLIP_LONG_TO_SHORT";
	}

	enum PositionState {
		LONG,
		SHORT,
		NONE
	}

	private record EntryState(
			CtiDirection side,
			BigDecimal entryPrice,
			long entryTimeMs,
			BigDecimal quantity) {
	}
}
