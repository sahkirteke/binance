package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.exchange.dto.OrderResponse;
import com.binance.strategy.indicators.AtrWilder;
import com.binance.strategy.indicators.EmaIndicator;
import com.binance.strategy.indicators.RsiWilder;
import com.binance.strategy.indicators.SmaRolling;
import com.binance.strategy.StrategyLogV1.ConfirmHitLogDto;
import com.binance.strategy.StrategyLogV1.DecisionLogDto;
import com.binance.strategy.StrategyLogV1.FlipLogDto;
import com.binance.strategy.StrategyLogV1.MissedMoveLogDto;
import com.binance.strategy.StrategyLogV1.SummaryLogDto;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class CtiLbStrategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(CtiLbStrategy.class);
	private static final int EMA_20_PERIOD = 20;
	private static final int EMA_200_PERIOD = 200;
	private static final int RSI_9_PERIOD = 9;
	private static final int ATR_14_PERIOD = 14;
	private static final int ATR_SMA_20_PERIOD = 20;
	private static final int VOLUME_SMA_10_PERIOD = 10;

	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final WarmupProperties warmupProperties;
	private final SymbolFilterService symbolFilterService;
	private final OrderTracker orderTracker;
	private final Map<String, Long> lastCloseTimes = new ConcurrentHashMap<>();
	private final Map<String, PositionState> positionStates = new ConcurrentHashMap<>();
	private final Map<String, EntryState> entryStates = new ConcurrentHashMap<>();
	private final Map<String, RecStreakTracker> recTrackers = new ConcurrentHashMap<>();
	private final LongAdder missedMoveCount = new LongAdder();
	private final LongAdder flipCount = new LongAdder();
	private final LongAdder confirmHitCount = new LongAdder();
	private final Map<String, LongAdder> missedBySymbol = new ConcurrentHashMap<>();
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
	private volatile boolean warmupMode;
	private volatile boolean ordersEnabledOverride = true;

	public CtiLbStrategy(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties,
			WarmupProperties warmupProperties, SymbolFilterService symbolFilterService, OrderTracker orderTracker) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.symbolFilterService = symbolFilterService;
		this.orderTracker = orderTracker;
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
		symbolState.updateIndicators(candle);
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
				signal.cti1mValue(),
				signal.cti1mPrev(),
				prevClose,
				close,
				candle.open(),
				candle.volume(),
				closeTime,
				symbolState);
		EntryDecision entryDecision = resolveEntryDecision(current, symbolState, entryFilterState);
		entryDecision = applyPendingFlipGate(symbol, current, entryDecision, entryFilterState, closeTime);
		logEntryFilterState(symbol, symbolState, closeTime, close, prevClose, entryFilterState, entryDecision);

		CtiDirection recommendationRaw = signal.recommendation();
		CtiDirection recommendationUsed = signal.insufficientData() ? CtiDirection.NEUTRAL : recommendationRaw;
		RecStreakTracker tracker = recTrackers.computeIfAbsent(symbol, ignored -> new RecStreakTracker());
		int confirmBars = CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars());
		RecStreakTracker.RecUpdate recUpdate = tracker.update(
				recommendationUsed,
				signal.closeTime(),
				BigDecimal.valueOf(close),
				confirmBars);
		int confirm1m = recUpdate.streakCount();
		CtiDirection confirmedRec = confirm1m >= confirmBars
				? recUpdate.lastRec()
				: CtiDirection.NEUTRAL;
		if (current == PositionState.NONE && entryDecision.confirmedRec() != null) {
			confirmedRec = entryDecision.confirmedRec();
		}

		boolean trendAlignedWithPosition = resolveTrendAlignedWithPosition(
				current,
				fiveMinDir,
				signal.cti1mValue(),
				signal.cti1mPrev());
		int exitReversalConfirmCounter = resolveExitReversalConfirmCounter(
				symbolState,
				current,
				fiveMinDir,
				confirmedRec,
				signal.cti1mValue(),
				signal.cti1mPrev());
		boolean exitReversalConfirmed = exitReversalConfirmCounter >= strategyProperties.exitReversalConfirmBars();

		int qualityScoreForLog = entryFilterState.qualityScore();
		String qualityConfirmReason = entryFilterState.lowQualityExtraConfirmApplied()
				? "LOW_QUALITY_EXTRA_CONFIRM"
				: null;
		if (warmupMode) {
			if (shouldLogWarmupDecision(symbol)) {
				logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRec, recUpdate,
						recommendationUsed, recommendationRaw, null, null, null, "WARMUP_MODE", "WARMUP_MODE",
						TrailState.empty(), ContinuationState.empty(), null, 0, qualityScoreForLog,
						qualityConfirmReason, false, null, 0, false, TpTrailingState.empty(),
						trendAlignedWithPosition, exitReversalConfirmCounter, false);
			}
			return;
		}

		if (recUpdate.missedMove()) {
			missedMoveCount.increment();
			missedBySymbol.computeIfAbsent(symbol, ignored -> new LongAdder()).increment();
			logMissedMove(symbol, recUpdate, signal, close);
		}
		if (recUpdate.confirmHit()) {
			confirmHitCount.increment();
			logConfirmHit(symbol, confirmedRec, recUpdate, signal, close);
		}

		syncPositionIfNeeded(symbol, closeTime);
		EntryState entryState = resolveEntryState(symbol, current, close, closeTime);
		TrailState trailState = resolveTrailState(symbolState, current, entryState, fiveMinDir, close);
		TrailState decisionTrailState = trailState;
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
		boolean flipExtraConfirmApplied = false;
		int flipQualityScore = 0;
		boolean exitBlockedByTrendAlignedAction = false;
		int barsInPosition = resolveBarsInPosition(entryState, closeTime);
		IndicatorsSnapshot indicatorsSnapshot = new IndicatorsSnapshot(
				symbolState.ema200_5mValue,
				symbolState.ema200_5m.isReady(),
				symbolState.ema20_1mValue,
				symbolState.ema20_1m.isReady(),
				symbolState.rsi9Value,
				symbolState.rsi9_1m.isReady(),
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
		if (current == PositionState.NONE) {
			symbolState.resetFlipConfirm();
		} else if (strategyProperties.flipConfirmResetOnNeutral()) {
			boolean resetFlipConfirm = confirmedRec == CtiDirection.NEUTRAL
					|| (current == PositionState.LONG && confirmedRec == CtiDirection.LONG)
					|| (current == PositionState.SHORT && confirmedRec == CtiDirection.SHORT);
			if (resetFlipConfirm) {
				symbolState.resetFlipConfirm();
			}
		}
		OppositeExitDecision oppositeExit = resolveOppositeExit(symbolState, current);
		if (current != PositionState.NONE && oppositeExit.exit()) {
			if (!exitReversalConfirmed) {
				String decisionActionReason = trendAlignedWithPosition ? "HOLD_TREND_ALIGNED" : "EXIT_WAIT_REVERSAL";
				String decisionBlockReason = decisionActionReason;
				boolean exitBlockedByTrendAligned = trendAlignedWithPosition;
				logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRec, recUpdate,
						recommendationUsed, recommendationRaw, null, entryState, null, decisionActionReason,
						decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
						symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
						trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
						trendAlignedWithPosition, exitReversalConfirmCounter, exitBlockedByTrendAligned);
				return;
			}
			TrendHoldFlipDecision trendHoldFlipDecision = evaluateTrendHoldFlipDecision(
					trendHoldActive,
					resolveDir(confirmedRec),
					resolveOppositeDir(current),
					resolveFlipQualityScore(current, close, entryFilterState, symbolState),
					confirm1m,
					confirmBars,
					strategyProperties.flipExtraConfirmBars(),
					strategyProperties.flipQualityMin());
			flipQualityScore = trendHoldFlipDecision.flipQualityScore();
			flipExtraConfirmApplied = trendHoldFlipDecision.flipExtraConfirmApplied();
			if (!trendHoldFlipDecision.allowFlip()) {
				String decisionActionReason = "TREND_HOLD_ACTIVE";
				String decisionBlockReason = "TREND_HOLD_ACTIVE";
				trendHoldReason = "TREND_HOLD_ACTIVE";
				logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRec, recUpdate,
						recommendationUsed, recommendationRaw, null, entryState, null, decisionActionReason,
						decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
						symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason,
						trendHoldActive, trendHoldReason, flipQualityScore, flipExtraConfirmApplied,
						decisionTpTrailingState, trendAlignedWithPosition, exitReversalConfirmCounter, false);
				return;
			}
			if (decisionContinuationState.holdApplied()) {
				String decisionActionReason = "EXIT_HOLD_CONTINUATION";
				String decisionBlockReason = "EXIT_HOLD_CONTINUATION";
				logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRec, recUpdate,
						recommendationUsed, recommendationRaw, null, entryState, null, decisionActionReason,
						decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
						symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
						trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
						trendAlignedWithPosition, exitReversalConfirmCounter, false);
				return;
			}
			if (strategyProperties.enableFlipOnOppositeExit()) {
				lastOppositeExitAtMs.put(symbol, closeTime);
				pendingFlipDirs.put(symbol, symbolState.lastFiveMinDir);
				LOGGER.info("EVENT=PENDING_FLIP symbol={} setDir={} at={}", symbol, symbolState.lastFiveMinDir, closeTime);
			}
			SignalAction exitAction = SignalAction.HOLD;
			String decisionActionReason = oppositeExit.reason();
			String decisionBlockReason = CtiLbDecisionEngine.resolveExitDecisionBlockReason();
			BigDecimal exitQty = resolveExitQuantity(symbol, entryState, close);
			logDecision(symbol, signal, close, exitAction, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, exitQty, entryState, null, decisionActionReason, decisionBlockReason,
					decisionTrailState, decisionContinuationState, flipGateReason, symbolState.flipConfirmCounter,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive, trendHoldReason, flipQualityScore,
					flipExtraConfirmApplied, decisionTpTrailingState, trendAlignedWithPosition,
					exitReversalConfirmCounter, false);
			if (!effectiveEnableOrders()) {
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
						String correlationId = orderTracker.nextCorrelationId(symbol, "EXIT_OPPOSITE");
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
					.doOnError(error -> LOGGER.warn("Failed to execute CTI LB opposite exit {}: {}",
							decisionActionReason, error.getMessage()))
					.onErrorResume(error -> Mono.empty())
					.subscribe();
			return;
		}
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
				resolveMinHoldMs());
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
					logDecision(symbol, signal, close, exitAction, confirm1m, confirmedRec, recUpdate, recommendationUsed,
							recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason,
							decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
							symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
							trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
							trendAlignedWithPosition, exitReversalConfirmCounter, false);
					return;
				}
			}
			ExitHoldDecision exitHoldDecision = evaluateExitHoldDecision(stopLossExit, exitReversalConfirmed,
					trendAlignedWithPosition);
			if (exitHoldDecision.hold()) {
				decisionActionReason = exitHoldDecision.reason();
				decisionBlockReason = exitHoldDecision.reason();
				exitBlockedByTrendAligned = exitHoldDecision.exitBlockedByTrendAligned();
				logDecision(symbol, signal, close, exitAction, confirm1m, confirmedRec, recUpdate, recommendationUsed,
						recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason,
						decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
						symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
						trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
						trendAlignedWithPosition, exitReversalConfirmCounter, exitBlockedByTrendAligned);
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
					confirm1m,
					CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars()),
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
			logDecision(symbol, signal, close, exitAction, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason, decisionBlockReason,
					decisionTrailState, decisionContinuationState, flipGateReason, symbolState.flipConfirmCounter,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive, trendHoldReason,
					flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState, trendAlignedWithPosition,
					exitReversalConfirmCounter, exitBlockedByTrendAligned);
			if (continuationHold || holdExit || !effectiveEnableOrders()) {
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
					.doOnError(error -> LOGGER.warn("Failed to execute CTI LB exit {}: {}", decisionActionReason,
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
					confirm1m,
					confirmBars,
					strategyProperties.flipExtraConfirmBars(),
					strategyProperties.flipQualityMin());
			flipQualityScore = trendHoldFlipDecision.flipQualityScore();
			flipExtraConfirmApplied = trendHoldFlipDecision.flipExtraConfirmApplied();
			if (!trendHoldFlipDecision.allowFlip()) {
				action = SignalAction.HOLD;
				decisionActionReason = "TREND_HOLD_ACTIVE";
				decisionBlockReason = "TREND_HOLD_ACTIVE";
				trendHoldReason = "TREND_HOLD_ACTIVE";
			}
		}
		if (current != PositionState.NONE && action != SignalAction.HOLD && !exitReversalConfirmed) {
			action = SignalAction.HOLD;
			if (trendAlignedWithPosition) {
				decisionActionReason = "HOLD_TREND_ALIGNED";
				decisionBlockReason = "HOLD_TREND_ALIGNED";
				exitBlockedByTrendAlignedAction = true;
			} else {
				decisionActionReason = "EXIT_WAIT_REVERSAL";
				decisionBlockReason = "EXIT_WAIT_REVERSAL";
			}
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
					confirm1m,
					CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars()),
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
						strategyProperties.flipFastQuality(),
						strategyProperties.flipConfirmBars(),
						target == PositionState.LONG ? 1 : -1,
						symbolState.flipConfirmCounter,
						symbolState.flipConfirmDir);
				symbolState.flipConfirmCounter = flipGate.flipConfirmCounter();
				symbolState.flipConfirmDir = flipGate.flipConfirmDir();
				if (!flipGate.allowFlip()) {
					action = SignalAction.HOLD;
					decisionActionReason = "FLIP_BLOCK_QUALITY_GATE";
					decisionBlockReason = "FLIP_BLOCK_QUALITY_GATE";
					flipGateReason = flipGate.reason();
				} else {
					symbolState.resetFlipConfirm();
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
			action = SignalAction.HOLD;
			logDecision(symbol, signal, close, action, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, decisionActionReason,
					decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
					symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
					trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
					trendAlignedWithPosition, exitReversalConfirmCounter, exitBlockedByTrendAlignedAction);
			return;
		}

		String minTradeBlockReason = validateMinTrade(symbol, resolvedQty, close);
		if (minTradeBlockReason != null) {
			action = SignalAction.HOLD;
			logDecision(symbol, signal, close, action, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, minTradeBlockReason,
					"OK_EXECUTED", decisionTrailState, decisionContinuationState, flipGateReason,
					symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
					trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
					trendAlignedWithPosition, exitReversalConfirmCounter, exitBlockedByTrendAlignedAction);
			return;
		}

		if (action != SignalAction.HOLD
				&& (resolvedQty == null || resolvedQty.signum() <= 0
						|| (current != PositionState.NONE && (closeQty == null || closeQty.signum() <= 0)))) {
			action = SignalAction.HOLD;
			decisionActionReason = "QTY_ZERO_AFTER_STEP";
			decisionBlockReason = "QTY_ZERO_AFTER_STEP";
			logDecision(symbol, signal, close, action, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, decisionActionReason,
					decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
					symbolState.flipConfirmCounter, qualityScoreForLog, qualityConfirmReason, trendHoldActive,
					trendHoldReason, flipQualityScore, flipExtraConfirmApplied, decisionTpTrailingState,
					trendAlignedWithPosition, exitReversalConfirmCounter, exitBlockedByTrendAlignedAction);
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
		int flipConfirmCounterForLog = symbolState.flipConfirmCounter;
		int qualityScoreForLogFinal = qualityScoreForLog;
		String qualityConfirmReasonForLog = qualityConfirmReason;
		boolean trendHoldActiveForLog = trendHoldActive;
		String trendHoldReasonForLog = trendHoldReason;
		int flipQualityScoreForLog = flipQualityScore;
		boolean flipExtraConfirmAppliedForLog = flipExtraConfirmApplied;
		TpTrailingState tpTrailingStateForLog = decisionTpTrailingState;
		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> {
					hedgeModeBySymbol.put(symbol, hedgeMode);
					logDecision(symbol, signal, close, actionForLog, confirm1m, confirmedRecForLog, recUpdate,
							recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
							estimatedPnlPct, decisionActionReasonForLog, decisionBlock, trailStateForLog,
							continuationStateForLog, flipGateReasonForLog, flipConfirmCounterForLog,
							qualityScoreForLogFinal, qualityConfirmReasonForLog, trendHoldActiveForLog,
							trendHoldReasonForLog, flipQualityScoreForLog, flipExtraConfirmAppliedForLog,
							tpTrailingStateForLog, trendAlignedWithPosition, exitReversalConfirmCounter,
							exitBlockedByTrendAlignedAction);
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
					logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRecForLog, recUpdate,
							recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
							estimatedPnlPct, decisionActionReasonForLog, "ORDER_ERROR", trailStateForLog,
							continuationStateForLog, flipGateReasonForLog, flipConfirmCounterForLog,
							qualityScoreForLogFinal, qualityConfirmReasonForLog, trendHoldActiveForLog,
							trendHoldReasonForLog, flipQualityScoreForLog, flipExtraConfirmAppliedForLog,
							tpTrailingStateForLog, trendAlignedWithPosition, exitReversalConfirmCounter,
							exitBlockedByTrendAlignedAction);
				})
				.onErrorResume(error -> Mono.empty())
				.subscribe();
	}

	public void onWarmupFiveMinuteCandle(String symbol, Candle candle) {
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
			boolean stopLossExit, CtiDirection confirmedRec, int confirmCounter, int confirmBars,
			double trendHoldMinProfitPct) {
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
		boolean oppositeStrong = false;
		if (side == CtiDirection.LONG) {
			oppositeStrong = confirmedRec == CtiDirection.SHORT && confirmCounter >= confirmBars;
		} else if (side == CtiDirection.SHORT) {
			oppositeStrong = confirmedRec == CtiDirection.LONG && confirmCounter >= confirmBars;
		}
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

	private int resolveExitReversalConfirmCounter(SymbolState state, PositionState current, int fiveMinDir,
			CtiDirection confirmedRec, double bfr1m, double bfrPrev) {
		ExitReversalConfirm update = updateExitReversalConfirm(
				state.exitReversalConfirmCounter,
				state.exitReversalConfirmDir,
				current,
				fiveMinDir,
				confirmedRec,
				bfr1m,
				bfrPrev);
		state.exitReversalConfirmCounter = update.counter();
		state.exitReversalConfirmDir = update.dir();
		return update.counter();
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

	static ExitHoldDecision evaluateExitHoldDecision(boolean stopLossExit, boolean exitReversalConfirmed,
			boolean trendAlignedWithPosition) {
		if (stopLossExit) {
			return new ExitHoldDecision(false, null, false);
		}
		if (!exitReversalConfirmed) {
			if (trendAlignedWithPosition) {
				return new ExitHoldDecision(true, "HOLD_TREND_ALIGNED", true);
			}
			return new ExitHoldDecision(true, "EXIT_WAIT_REVERSAL", false);
		}
		return new ExitHoldDecision(false, null, false);
	}

	static ExitReversalConfirm updateExitReversalConfirm(int currentCounter, int currentDir, PositionState current,
			int fiveMinDir, CtiDirection confirmedRec, double bfr1m, double bfrPrev) {
		if (current == PositionState.NONE) {
			return new ExitReversalConfirm(0, 0);
		}
		int targetDir = current == PositionState.LONG ? -1 : 1;
		boolean trendOpposite = fiveMinDir == targetDir;
		boolean bfrReversal = false;
		if (Double.isFinite(bfr1m) && Double.isFinite(bfrPrev)) {
			bfrReversal = targetDir == -1 ? bfr1m < bfrPrev : bfr1m > bfrPrev;
		}
		boolean confirmedOpposite = targetDir == -1
				? confirmedRec == CtiDirection.SHORT
				: confirmedRec == CtiDirection.LONG;
		boolean reversalCandidate = trendOpposite && (confirmedOpposite || bfrReversal);
		if (!reversalCandidate) {
			return new ExitReversalConfirm(0, 0);
		}
		if (currentDir != targetDir) {
			return new ExitReversalConfirm(1, targetDir);
		}
		return new ExitReversalConfirm(currentCounter + 1, targetDir);
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
			int oppositeDir, int flipQualityScore, int confirm1m, int confirmBars, int flipExtraConfirmBars,
			int flipQualityMin) {
		if (!trendHoldActive) {
			return new TrendHoldFlipDecision(true, flipQualityScore, false);
		}
		int requiredConfirm = confirmBars + Math.max(0, flipExtraConfirmBars);
		boolean extraConfirmApplied = confirm1m < requiredConfirm;
		boolean allowFlip = confirmedRecDir == oppositeDir
				&& flipQualityScore >= flipQualityMin
				&& confirm1m >= requiredConfirm;
		return new TrendHoldFlipDecision(allowFlip, flipQualityScore, extraConfirmApplied);
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
				state.ema20_1m.isReady(),
				state.ema200_5mValue,
				state.ema200_5m.isReady(),
				state.rsi9Value,
				state.rsi9_1m.isReady(),
				entryFilterState.volume(),
				state.volumeSma10Value,
				state.volumeSma10_1m.isReady(),
				state.atr14Value,
				state.atr14_1m.isReady(),
				state.atrSma20Value,
				state.atrSma20_1m.isReady(),
				true,
				strategyProperties);
		return evaluation == null ? 0 : evaluation.qualityScore();
	}

	private void logEntryFilterState(String symbol, SymbolState state, long nowMs, double close, double prevClose,
			EntryFilterState entryFilterState, EntryDecision entryDecision) {
		if (warmupMode) {
			return;
		}
		LOGGER.info("EVENT=ENTRY_FILTER_STATE symbol={} fiveMinDir={} lastFiveMinDirPrev={} flipTimeMs={} minutesSinceFlip={} inArming={} lateBlocked={} lateIgnoredPullback={} lateIgnoredMoveGate={} flipPrice={} movedDirPct={} chaseRisk={} prevClose1m={} pullbackTriggered={} pullbackExtraOk={} scoreAligned={} entryTrigger={} triggerReason={} entryMode={} confirmBarsUsed={} confirmCounter={} scoreConfirmBoosted={} blockReason={} ema20_1m={} ema200_5m={} rsi9={} vol={} volSma10={} atr14={} atrSma20={} qualityScore={} ema200Ok={} ema20Ok={} rsiOk={} volOk={} atrOk={} confirmBarsUsedDynamic={} qualityBlockReason={} lowQualityExtraConfirm={}",
				symbol,
				state.lastFiveMinDir,
				state.prevFiveMinDir,
				entryFilterState.flipTimeMs() == 0L ? "NA" : entryFilterState.flipTimeMs(),
				Double.isNaN(entryFilterState.minutesSinceFlip()) ? "NA" : String.format("%.2f",
						entryFilterState.minutesSinceFlip()),
				entryFilterState.inArming(),
				entryFilterState.lateBlocked(),
				entryFilterState.lateBlockedIgnoredPullback(),
				entryFilterState.lateBlockedIgnoredMoveGate(),
				entryFilterState.flipPrice() == 0.0 ? "NA" : String.format("%.8f", entryFilterState.flipPrice()),
				Double.isNaN(entryFilterState.movedDirPct()) ? "NA" : String.format("%.4f",
						entryFilterState.movedDirPct()),
				entryFilterState.chaseRisk(),
				Double.isNaN(prevClose) ? "NA" : String.format("%.8f", prevClose),
				entryFilterState.pullbackTriggered(),
				entryFilterState.pullbackExtraOk(),
				entryFilterState.scoreAligned(),
				entryFilterState.entryTrigger(),
				entryFilterState.triggerReason(),
				entryDecision.entryMode(),
				entryDecision.confirmBarsUsed(),
				entryDecision.confirmCounter(),
				entryDecision.scoreConfirmBoosted(),
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
				entryFilterState.confirmBarsUsedDynamic(),
				entryFilterState.qualityBlockReason(),
				entryFilterState.lowQualityExtraConfirmApplied());
	}

	private EntryFilterState resolveEntryFilterState(int fiveMinDir, int score1m, double bfr1m, double bfrPrev,
			double prevClose, double close, double open, double volume1m, long nowMs, SymbolState state) {
		EntryFilterInputs inputs = new EntryFilterInputs(
				fiveMinDir,
				score1m,
				bfr1m,
				bfrPrev,
				prevClose,
				close,
				open,
				volume1m,
				nowMs,
				state.fiveMinFlipTimeMs,
				state.fiveMinFlipPrice,
				state.ema20_1mValue,
				state.ema20_1m.isReady(),
				state.ema200_5mValue,
				state.ema200_5m.isReady(),
				state.rsi9Value,
				state.rsi9_1m.isReady(),
				state.volumeSma10Value,
				state.volumeSma10_1m.isReady(),
				state.atr14Value,
				state.atr14_1m.isReady(),
				state.atrSma20Value,
				state.atrSma20_1m.isReady());
		return buildEntryFilterState(inputs, strategyProperties);
	}

	static EntryFilterState buildEntryFilterState(EntryFilterInputs inputs, StrategyProperties properties) {
		long armingWindowMs = properties.armingWindowMinutes() * 60_000L;
		long lateLimitMs = properties.lateLimitMinutes() * 60_000L;
		boolean hasDir = inputs.fiveMinDir() != 0;
		long sinceFlipMs = inputs.fiveMinFlipTimeMs() == 0L ? Long.MAX_VALUE : inputs.nowMs() - inputs.fiveMinFlipTimeMs();
		boolean inArming = hasDir && sinceFlipMs <= armingWindowMs;
		double minutesSinceFlip = inputs.fiveMinFlipTimeMs() == 0L ? Double.NaN : sinceFlipMs / 60000.0;
		double movedDirPct = Double.NaN;
		if (inputs.fiveMinFlipPrice() > 0) {
			double movedSinceFlipPct = (inputs.close() - inputs.fiveMinFlipPrice()) / inputs.fiveMinFlipPrice() * 100.0;
			movedDirPct = inputs.fiveMinDir() == 1
					? movedSinceFlipPct
					: (inputs.fiveMinFlipPrice() - inputs.close()) / inputs.fiveMinFlipPrice() * 100.0;
		}
		boolean prevCloseValid = !Double.isNaN(inputs.prevClose());
		boolean pullbackBaseLong = inputs.fiveMinDir() == 1 && prevCloseValid
				&& inputs.prevClose() < inputs.bfrPrev() && inputs.close() > inputs.bfr1m();
		boolean pullbackBaseShort = inputs.fiveMinDir() == -1 && prevCloseValid
				&& inputs.prevClose() > inputs.bfrPrev() && inputs.close() < inputs.bfr1m();
		double openValue = inputs.open();
		boolean openValid = openValue > 0 && Double.isFinite(openValue);
		boolean candleRed = openValid
				? inputs.close() < openValue
				: prevCloseValid && inputs.close() < inputs.prevClose();
		boolean candleGreen = openValid
				? inputs.close() > openValue
				: prevCloseValid && inputs.close() > inputs.prevClose();
		boolean nearEma20 = inputs.ema20Ready()
				&& Double.isFinite(inputs.ema20_1m())
				&& inputs.close() > 0
				&& Math.abs(inputs.close() - inputs.ema20_1m()) / inputs.close() <= 0.0015;
		boolean pullbackExtraOk = false;
		if (inputs.fiveMinDir() == 1) {
			pullbackExtraOk = nearEma20 || candleRed;
		} else if (inputs.fiveMinDir() == -1) {
			pullbackExtraOk = nearEma20 || candleGreen;
		}
		boolean pullbackTriggered = false;
		if (inputs.fiveMinDir() == 1) {
			pullbackTriggered = pullbackBaseLong && pullbackExtraOk;
		} else if (inputs.fiveMinDir() == -1) {
			pullbackTriggered = pullbackBaseShort && pullbackExtraOk;
		}
		boolean scoreAlignedLong = inputs.score1m() == 1;
		boolean scoreAlignedShort = inputs.score1m() == -1;
		boolean entryTrigger = false;
		String triggerReason = "NONE";
		boolean scoreAligned = false;

		boolean pullbackQualityOk = true;
		if (inputs.rsiReady()) {
			if (inputs.fiveMinDir() == 1 && inputs.rsi9() >= 80.0) {
				pullbackQualityOk = false;
			} else if (inputs.fiveMinDir() == -1 && inputs.rsi9() <= 20.0) {
				pullbackQualityOk = false;
			}
		}
		if (pullbackQualityOk && inputs.atrReady() && inputs.atrSmaReady() && inputs.atrSma20() > 0) {
			if (inputs.atr14() > inputs.atrSma20() * 2.5) {
				pullbackQualityOk = false;
			}
		}

		boolean pullbackAllowed = pullbackTriggered && pullbackQualityOk;
		if (inputs.fiveMinDir() == 1) {
			scoreAligned = scoreAlignedLong;
			entryTrigger = scoreAlignedLong || pullbackAllowed;
		} else if (inputs.fiveMinDir() == -1) {
			scoreAligned = scoreAlignedShort;
			entryTrigger = scoreAlignedShort || pullbackAllowed;
		}

		LateBlockDecision lateBlockDecision = resolveLateBlockDecision(
				hasDir,
				sinceFlipMs,
				lateLimitMs,
				pullbackTriggered,
				movedDirPct,
				properties.movedDirPctLateGate());

		boolean movedDirGateHit = properties.movedDirPctLateGate() != null
				&& !Double.isNaN(movedDirPct)
				&& movedDirPct >= properties.movedDirPctLateGate().doubleValue();
		boolean chaseRisk = lateBlockDecision.lateBlocked() || movedDirGateHit;

		if (pullbackAllowed) {
			triggerReason = "PULLBACK";
		} else if (scoreAligned) {
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
		int baseConfirmBarsUsed = inArming
				? properties.confirmBarsEarly()
				: properties.confirmBarsNormal();
		int confirmBarsUsedDynamic = resolveConfirmBarsUsedDynamic(
				baseConfirmBarsUsed,
				entryTrigger,
				qualityEvaluation,
				properties);
		return new EntryFilterState(inputs.fiveMinDir(), inArming, lateBlockDecision.lateBlocked(),
				lateBlockDecision.ignoredPullback(), lateBlockDecision.ignoredMoveGate(), movedDirPct,
				chaseRisk, entryTrigger, pullbackTriggered, pullbackExtraOk, pullbackQualityOk, scoreAligned,
				triggerReason, inputs.fiveMinFlipTimeMs(), minutesSinceFlip, inputs.fiveMinFlipPrice(),
				inputs.ema20_1m(), inputs.ema200_5m(), inputs.rsi9(), inputs.volume1m(), inputs.volumeSma10(),
				inputs.atr14(), inputs.atrSma20(), qualityEvaluation.qualityScore(), qualityEvaluation.ema200Ok(),
				qualityEvaluation.ema20Ok(), qualityEvaluation.rsiOk(), qualityEvaluation.volOk(),
				qualityEvaluation.atrOk(), confirmBarsUsedDynamic, qualityEvaluation.blockReason(),
				qualityEvaluation.lowQualityExtraConfirmApplied());
	}

	static LateBlockDecision resolveLateBlockDecision(boolean hasDir, long sinceFlipMs, long lateLimitMs,
			boolean pullbackTriggered, double movedDirPct, BigDecimal movedDirPctLateGate) {
		boolean lateLimitExceeded = hasDir && sinceFlipMs > lateLimitMs;
		boolean ignoredMoveGate = false;
		boolean ignoredPullback = false;
		boolean gatePassed = true;
		if (lateLimitExceeded && movedDirPctLateGate != null) {
			if (Double.isNaN(movedDirPct) || Math.abs(movedDirPct) < movedDirPctLateGate.doubleValue()) {
				gatePassed = false;
				ignoredMoveGate = true;
			}
		}
		if (lateLimitExceeded && pullbackTriggered) {
			ignoredPullback = true;
		}
		boolean lateBlocked = lateLimitExceeded && gatePassed && !pullbackTriggered;
		return new LateBlockDecision(lateBlocked, ignoredPullback, ignoredMoveGate);
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
				&& ((fiveMinDir == 1 && rsi9 >= properties.rsiLongMin() && rsi9 <= properties.rsiLongMax())
						|| (fiveMinDir == -1 && rsi9 >= properties.rsiShortMin() && rsi9 <= properties.rsiShortMax()));
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
		if (entryTrigger && properties.extremeRsiBlock() && rsiReady) {
			boolean extremeRsi = (fiveMinDir == 1 && rsi9 >= properties.extremeRsiHigh())
					|| (fiveMinDir == -1 && rsi9 <= properties.extremeRsiLow());
			if (extremeRsi) {
				blockReason = "ENTRY_BLOCK_EXTREME_RSI";
			}
		}
		if (entryTrigger && blockReason == null && atrReady && atrSmaReady && atrSma20 > 0) {
			if (atr14 > atrSma20 * properties.extremeAtrBlockMult()) {
				blockReason = "ENTRY_BLOCK_EXTREME_ATR";
			}
		}
		boolean lowQualityExtraConfirmApplied = entryTrigger && clamped < properties.entryQualityMin();
		return new EntryQualityEvaluation(clamped, ema200Ok, ema20Ok, rsiOk, volOk, atrOk, blockReason,
				lowQualityExtraConfirmApplied);
	}

	static int resolveConfirmBarsUsedDynamic(int baseConfirmBars, boolean entryTrigger,
			EntryQualityEvaluation evaluation, StrategyProperties properties) {
		int confirmBars = Math.max(1, baseConfirmBars);
		if (evaluation == null || !entryTrigger) {
			return confirmBars;
		}
		if (evaluation.lowQualityExtraConfirmApplied()) {
			confirmBars += Math.max(0, properties.lowQualityExtraConfirm());
		}
		return Math.max(1, confirmBars);
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

	static FlipGateResult evaluateFlipGate(int qualityScore, int flipFastQuality, int flipConfirmBars, int targetDir,
			int currentCounter, int currentDir) {
		if (qualityScore >= flipFastQuality) {
			return new FlipGateResult(true, 0, 0, "FAST_QUALITY");
		}
		int counter = currentCounter;
		int dir = currentDir;
		if (dir != targetDir) {
			counter = 0;
			dir = targetDir;
		}
		counter += 1;
		if (counter < Math.max(1, flipConfirmBars)) {
			String reason = String.format("CONFIRM_%d/%d", counter, Math.max(1, flipConfirmBars));
			return new FlipGateResult(false, counter, dir, reason);
		}
		return new FlipGateResult(true, 0, 0, "CONFIRMED");
	}

	private EntryDecision resolveEntryDecision(PositionState current, SymbolState state, EntryFilterState entryFilterState) {
		EntryDecision decision = evaluateEntryDecision(current, entryFilterState, state.confirmCounter,
				strategyProperties);
		state.confirmCounter = decision.confirmCounter();
		return decision;
	}

	static EntryDecision evaluateEntryDecision(PositionState current, EntryFilterState entryFilterState,
			int confirmCounter, StrategyProperties properties) {
		if (current != PositionState.NONE) {
			return new EntryDecision(null, "NORMAL", 0, 0, false, null, null);
		}
		if (entryFilterState.fiveMinDir() == 0) {
			return new EntryDecision(null, "NORMAL", 0, 0, false, "NO_5M_SUPPORT", "NO_5M_SUPPORT");
		}
		if (entryFilterState.lateBlocked()) {
			return new EntryDecision(null, "NORMAL", 0, 0, false, "ENTRY_BLOCK_LATE", "ENTRY_BLOCK_LATE");
		}
		if (entryFilterState.qualityBlockReason() != null) {
			return new EntryDecision(null, "NORMAL", 0, 0, false, entryFilterState.qualityBlockReason(),
					entryFilterState.qualityBlockReason());
		}
		BigDecimal chaseMaxMovePct = properties.chaseMaxMovePct();
		boolean chaseBlocked = !entryFilterState.inArming()
				&& chaseMaxMovePct != null
				&& !Double.isNaN(entryFilterState.movedDirPct())
				&& entryFilterState.movedDirPct() > chaseMaxMovePct.doubleValue();
		if (chaseBlocked) {
			return new EntryDecision(null, "NORMAL", 0, 0, false, "ENTRY_BLOCK_CHASE",
					"ENTRY_BLOCK_CHASE");
		}
		boolean pullbackAllowed = entryFilterState.pullbackTriggered() && entryFilterState.pullbackQualityOk();
		int confirmBarsUsed = entryFilterState.confirmBarsUsedDynamic();
		boolean scoreConfirmBoosted = false;
		if (pullbackAllowed) {
			confirmBarsUsed = 1;
		} else if (entryFilterState.scoreAligned() && entryFilterState.chaseRisk()) {
			confirmBarsUsed = Math.max(confirmBarsUsed, 2);
			scoreConfirmBoosted = true;
		}
		if (entryFilterState.entryTrigger()) {
			confirmCounter += 1;
		} else {
			confirmCounter = 0;
		}
		if (confirmCounter >= Math.max(1, confirmBarsUsed)) {
			CtiDirection confirmed = entryFilterState.fiveMinDir() == 1 ? CtiDirection.LONG : CtiDirection.SHORT;
			String entryMode = pullbackAllowed ? "PULLBACK" : "NORMAL";
			String reason;
			if (entryFilterState.inArming()) {
				reason = pullbackAllowed ? "ENTRY_ARMED_PULLBACK" : "ENTRY_ARMED_SCORE";
			} else {
				reason = pullbackAllowed ? "ENTRY_PULLBACK" : "ENTRY_NORMAL_SCORE_CONFIRMED";
			}
			return new EntryDecision(confirmed, entryMode, confirmBarsUsed, confirmCounter, scoreConfirmBoosted,
					null, reason);
		}
		String entryMode = pullbackAllowed ? "PULLBACK" : "NORMAL";
		return new EntryDecision(null, entryMode, confirmBarsUsed, confirmCounter, scoreConfirmBoosted, null, null);
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
			boolean inArming,
			boolean lateBlocked,
			boolean lateBlockedIgnoredPullback,
			boolean lateBlockedIgnoredMoveGate,
			double movedDirPct,
			boolean chaseRisk,
			boolean entryTrigger,
			boolean pullbackTriggered,
			boolean pullbackExtraOk,
			boolean pullbackQualityOk,
			boolean scoreAligned,
			String triggerReason,
			long flipTimeMs,
			double minutesSinceFlip,
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
			int confirmBarsUsedDynamic,
			String qualityBlockReason,
			boolean lowQualityExtraConfirmApplied) {
	}

	record EntryFilterInputs(
			int fiveMinDir,
			int score1m,
			double bfr1m,
			double bfrPrev,
			double prevClose,
			double close,
			double open,
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

	record LateBlockDecision(boolean lateBlocked, boolean ignoredPullback, boolean ignoredMoveGate) {
	}

	record EntryDecision(
			CtiDirection confirmedRec,
			String entryMode,
			int confirmBarsUsed,
			int confirmCounter,
			boolean scoreConfirmBoosted,
			String blockReason,
			String decisionActionReason) {
		static EntryDecision defaultDecision() {
			return new EntryDecision(null, "NORMAL", 0, 0, false, null, null);
		}

		EntryDecision withBlockReason(String reason) {
			return new EntryDecision(confirmedRec, entryMode, confirmBarsUsed, confirmCounter, scoreConfirmBoosted,
					reason, decisionActionReason);
		}
	}

	record ExitHoldDecision(boolean hold, String reason, boolean exitBlockedByTrendAligned) {
	}

	record ExitReversalConfirm(int counter, int dir) {
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

	record FlipGateResult(boolean allowFlip, int flipConfirmCounter, int flipConfirmDir, String reason) {
	}

	record EntryQualityEvaluation(
			int qualityScore,
			boolean ema200Ok,
			boolean ema20Ok,
			boolean rsiOk,
			boolean volOk,
			boolean atrOk,
			String blockReason,
			boolean lowQualityExtraConfirmApplied) {
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
			int flipQualityScore,
			boolean flipExtraConfirmApplied) {
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
		private int confirmCounter;
		private int exitConfirmCounter;
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
		private int flipConfirmCounter;
		private int flipConfirmDir;
		private int exitReversalConfirmCounter;
		private int exitReversalConfirmDir;
		private final FiveMinuteCandleAggregator fiveMinuteAggregator = new FiveMinuteCandleAggregator();
		private final EmaIndicator ema20_1m = new EmaIndicator(EMA_20_PERIOD);
		private final EmaIndicator ema200_5m = new EmaIndicator(EMA_200_PERIOD);
		private final RsiWilder rsi9_1m = new RsiWilder(RSI_9_PERIOD);
		private final AtrWilder atr14_1m = new AtrWilder(ATR_14_PERIOD);
		private final SmaRolling atrSma20_1m = new SmaRolling(ATR_SMA_20_PERIOD);
		private final SmaRolling volumeSma10_1m = new SmaRolling(VOLUME_SMA_10_PERIOD);
		private double ema20_1mValue = Double.NaN;
		private double ema200_5mValue = Double.NaN;
		private double rsi9Value = Double.NaN;
		private double atr14Value = Double.NaN;
		private double atrSma20Value = Double.NaN;
		private double volumeSma10Value = Double.NaN;

		private void updateIndicators(Candle candle) {
			ema20_1mValue = ema20_1m.update(candle.close());
			rsi9Value = rsi9_1m.update(candle.close());
			atr14Value = atr14_1m.update(candle.high(), candle.low(), candle.close());
			atrSma20Value = atrSma20_1m.update(atr14Value);
			volumeSma10Value = volumeSma10_1m.update(candle.volume());
			fiveMinuteAggregator.update(candle)
					.ifPresent(this::updateFiveMinuteIndicators);
		}

		private void updateFiveMinuteIndicators(Candle candle) {
			ema200_5mValue = ema200_5m.update(candle.close());
		}

		private boolean isTrendEmaReady() {
			return ema20_1m.isReady() && ema200_5m.isReady();
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

		private void resetFlipConfirm() {
			flipConfirmCounter = 0;
			flipConfirmDir = 0;
		}
	}

	private OppositeExitDecision resolveOppositeExit(SymbolState state, PositionState current) {
		if (current == PositionState.NONE) {
			state.exitConfirmCounter = 0;
			return OppositeExitDecision.noExit();
		}
		boolean oppositeCandidate = (current == PositionState.LONG && state.lastFiveMinDir == -1)
				|| (current == PositionState.SHORT && state.lastFiveMinDir == 1);
		if (oppositeCandidate) {
			state.exitConfirmCounter += 1;
		} else {
			state.exitConfirmCounter = 0;
		}
		int confirmBarsExit = Math.max(1, strategyProperties.confirmBarsExit());
		if (oppositeCandidate && state.exitConfirmCounter >= confirmBarsExit) {
			String reason = String.format(
					"EXIT_OPPOSITE_SIGNAL fiveMinDir=%d positionSide=%s confirm=%d/%d",
					state.lastFiveMinDir,
					current.name(),
					state.exitConfirmCounter,
					confirmBarsExit);
			return new OppositeExitDecision(true, reason);
		}
		return OppositeExitDecision.noExit();
	}

	private record OppositeExitDecision(boolean exit, String reason) {
		static OppositeExitDecision noExit() {
			return new OppositeExitDecision(false, null);
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
		return orderClient.placeReduceOnlyMarketOrder(symbol, side, quantity, positionSide, correlationId)
				.retryWhen(Retry.backoff(2, Duration.ofMillis(200))
						.filter(error -> !(error instanceof IllegalArgumentException)))
				.filter(CtiLbDecisionEngine::shouldProceedAfterClose)
				.switchIfEmpty(Mono.error(new IllegalStateException("Close order rejected")));
	}

	private Mono<OrderResponse> openPosition(String symbol, PositionState target, BigDecimal quantity, boolean hedgeMode,
			String correlationId) {
		String side = target == PositionState.LONG ? "BUY" : "SELL";
		String positionSide = hedgeMode ? target.name() : "";
		return orderClient.placeMarketOrder(symbol, side, quantity, positionSide, correlationId);
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
			int confirm1m, CtiDirection confirmedRec, RecStreakTracker.RecUpdate recUpdate,
			CtiDirection recommendationUsed, CtiDirection recommendationRaw, BigDecimal resolvedQty,
			EntryState entryState, Double estimatedPnlPct, String decisionActionReason, String decisionBlockReason,
			TrailState trailState, ContinuationState continuationState, String flipGateReason,
			Integer flipConfirmCounter, Integer qualityScore, String qualityConfirmReason, Boolean trendHoldActive,
			String trendHoldReason, Integer flipQualityScore, Boolean flipExtraConfirmApplied,
			TpTrailingState tpTrailingState, Boolean trendAlignedWithPosition,
			Integer exitReversalConfirmCounter, Boolean exitBlockedByTrendAligned) {
		logSummaryIfNeeded(signal.closeTime());
		String decisionAction = recUpdate.missedMove() ? "RESET_PENDING" : resolveDecisionAction(action);
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
		Integer flipConfirmCounterValue = flipConfirmCounter == null ? 0 : flipConfirmCounter;
		Integer qualityScoreValue = qualityScore == null ? 0 : qualityScore;
		String qualityConfirmReasonValue = qualityConfirmReason == null ? "NA" : qualityConfirmReason;
		Boolean trendHoldActiveValue = trendHoldActive == null ? false : trendHoldActive;
		String trendHoldReasonValue = trendHoldReason == null ? "NA" : trendHoldReason;
		Integer flipQualityScoreValue = flipQualityScore == null ? 0 : flipQualityScore;
		Boolean flipExtraConfirmAppliedValue = flipExtraConfirmApplied == null ? false : flipExtraConfirmApplied;
		TpTrailingState effectiveTpTrailing = tpTrailingState == null ? TpTrailingState.empty() : tpTrailingState;
		Boolean trendAlignedValue = trendAlignedWithPosition == null ? false : trendAlignedWithPosition;
		Integer exitReversalConfirmValue = exitReversalConfirmCounter == null ? 0 : exitReversalConfirmCounter;
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
				signal.adxBonus(),
				scoreLong(signal.score1m(), signal.score5m(), signal.adxBonus(), signal.hamCtiScore()),
				scoreShort(signal.score1m(), signal.score5m(), signal.adxBonus(), signal.hamCtiScore()),
				signal.adjustedScore(),
				signal.bias(),
				recommendationRaw,
				recommendationUsed,
				confirm1m,
				CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars()),
				confirmedRec,
				signal.recReason().name(),
				recUpdate.recPending(),
				recUpdate.recFirstSeenAtMs(),
				recUpdate.recFirstSeenPrice(),
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
				exitReversalConfirmValue,
				exitBlockedByTrendAlignedValue,
				recommendationRaw,
				confirm1m,
				formatPositionSide(positionStates.getOrDefault(symbol, PositionState.NONE)),
				entryState == null ? null : entryState.quantity(),
				openOrders,
				pendingFlipDir,
				cooldownRemainingMs,
				missedMoveCount.longValue(),
				confirmHitCount.longValue(),
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
				flipConfirmCounterValue,
				flipGateReasonValue,
				qualityScoreValue,
				qualityConfirmReasonValue,
				trendHoldActiveValue,
				trendHoldReasonValue,
				flipQualityScoreValue,
				flipExtraConfirmAppliedValue,
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

	private void logConfigSnapshot() {
		LOGGER.info("EVENT=CTI_CONFIG notionalUsdt={} maxPositionUsdt={} confirmBars={} minHoldMs={} enableTieBreakBias={}"
				+ " flipCooldownMs={} maxFlipsPer5Min={}",
				resolveNotionalUsdt(),
				resolveMaxPositionUsdt(),
				CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars()),
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

	private void logMissedMove(String symbol, RecStreakTracker.RecUpdate recUpdate, ScoreSignal signal,
			double nowPrice) {
		if (warmupMode) {
			return;
		}
		MissedMoveLogDto dto = new MissedMoveLogDto(
				symbol,
				recUpdate.missedPending(),
				recUpdate.missedFirstSeenAtMs(),
				recUpdate.missedFirstSeenPrice(),
				signal.closeTime(),
				BigDecimal.valueOf(nowPrice),
				recUpdate.streakBeforeReset(),
				CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars()),
				signal.cti1mValue(),
				signal.cti5mValue(),
				signal.adx5m(),
				missedMoveCount.longValue(),
				confirmHitCount.longValue(),
				flipCount.longValue());
		LOGGER.info(StrategyLogLineBuilder.buildMissedMoveLine(dto));
	}

	private void logConfirmHit(String symbol, CtiDirection confirmedRec, RecStreakTracker.RecUpdate recUpdate,
			ScoreSignal signal, double nowPrice) {
		if (warmupMode) {
			return;
		}
		ConfirmHitLogDto dto = new ConfirmHitLogDto(
				symbol,
				confirmedRec,
				recUpdate.confirmFirstSeenAtMs(),
				recUpdate.confirmFirstSeenPrice(),
				signal.closeTime(),
				BigDecimal.valueOf(nowPrice),
				recUpdate.streakCount(),
				CtiLbDecisionEngine.effectiveConfirmBars(strategyProperties.confirmBars()),
				signal.cti1mValue(),
				signal.cti5mValue(),
				signal.adx5m(),
				missedMoveCount.longValue(),
				confirmHitCount.longValue(),
				flipCount.longValue());
		LOGGER.info(StrategyLogLineBuilder.buildConfirmHitLine(dto));
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
				scoreLong(signal.score1m(), signal.score5m(), signal.adxBonus(), signal.hamCtiScore()),
				scoreShort(signal.score1m(), signal.score5m(), signal.adxBonus(), signal.hamCtiScore()),
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
				missedMoveCount.longValue(),
				confirmHitCount.longValue(),
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
		long missed = missedMoveCount.longValue();
		long confirmed = confirmHitCount.longValue();
		long flips = flipCount.longValue();
		double missRate = (missed + confirmed) > 0 ? (double) missed / (missed + confirmed) : 0.0;
		double confirmRate = (missed + confirmed) > 0 ? (double) confirmed / (missed + confirmed) : 0.0;
		String topMissed = buildTopMissed();
		SummaryLogDto dto = new SummaryLogDto(
				recTrackers.size(),
				flips,
				confirmed,
				missed,
				missRate,
				confirmRate,
				topMissed);
		LOGGER.info(StrategyLogLineBuilder.buildSummaryLine(dto));
	}

	private String buildTopMissed() {
		java.util.List<String> entries = missedBySymbol.entrySet().stream()
				.filter(entry -> entry.getValue().longValue() > 0)
				.sorted((left, right) -> Long.compare(right.getValue().longValue(), left.getValue().longValue()))
				.map(entry -> entry.getKey() + ":" + entry.getValue().longValue())
				.toList();
		return entries.isEmpty() ? "0" : String.join(",", entries);
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

	private int scoreLong(int score1m, int score5m, int adxBonus, int hamScore) {
		int longScore = (score1m > 0 ? 1 : 0) + (score5m > 0 ? 1 : 0);
		if (hamScore > 0) {
			longScore += adxBonus;
		}
		return longScore;
	}

	private int scoreShort(int score1m, int score5m, int adxBonus, int hamScore) {
		int shortScore = (score1m < 0 ? 1 : 0) + (score5m < 0 ? 1 : 0);
		if (hamScore < 0) {
			shortScore += adxBonus;
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
