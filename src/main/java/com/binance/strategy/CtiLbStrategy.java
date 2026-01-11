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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
	private static final double MACD_HIST_EPS = 1e-6;
	private static final double EMA20_MAX_DIST_PCT = 0.008;
	private static final double GIVEBACK_THRESHOLD_BPS = 25.0;
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
	private final Map<String, Boolean> trailingArmedBySymbol = new ConcurrentHashMap<>();
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
	@Autowired
	@Lazy
	private TrailingPnlService trailingPnlService;
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

	public void setTrailingArmed(String symbol, boolean armed) {
		if (symbol == null) {
			return;
		}
		if (armed) {
			trailingArmedBySymbol.put(symbol, true);
		} else {
			trailingArmedBySymbol.remove(symbol);
		}
	}

	private void clearTrailingArmed(String symbol) {
		setTrailingArmed(symbol, false);
	}

	private boolean isTrailingExitOnly(String symbol, PositionState current) {
		return current != PositionState.NONE
				&& strategyProperties.pnlTrailEnabled()
				&& Boolean.TRUE.equals(trailingArmedBySymbol.get(symbol));
	}

	private double resolvePnlTrailProfitArm() {
		return strategyProperties.pnlTrailProfitArm() > 0 ? strategyProperties.pnlTrailProfitArm() : 20.0;
	}

	private double resolvePnlTrailLossArm() {
		return strategyProperties.pnlTrailLossArm() < 0 ? strategyProperties.pnlTrailLossArm() : -20.0;
	}

	private int resolveLeverageForTrail() {
		return strategyProperties.leverage() > 0 ? strategyProperties.leverage() : 50;
	}

	private double calculateLeveragedPnlPct(EntryState entryState, double close) {
		if (entryState == null || entryState.entryPrice() == null || entryState.entryPrice().signum() <= 0
				|| close <= 0) {
			return Double.NaN;
		}
		double entryPrice = entryState.entryPrice().doubleValue();
		double delta = (close - entryPrice) / entryPrice;
		if (entryState.side() == CtiDirection.SHORT) {
			delta = -delta;
		}
		return delta * 100.0 * resolveLeverageForTrail();
	}

	public void requestTrailingExit(TrailingExitRequest request) {
		if (request == null || request.symbol() == null) {
			return;
		}
		String symbol = request.symbol();
		PositionState current = positionStates.getOrDefault(symbol, PositionState.NONE);

		if (current == PositionState.NONE) {
			LOGGER.info("EVENT=TRAIL_EXIT_SKIP symbol={} reason=NO_POSITION", symbol);
			cleanupPositionState(symbol);
			return;
		}

		EntryState entryState = entryStates.get(symbol);
		if (entryState == null || entryState.entryPrice() == null || entryState.entryPrice().signum() <= 0) {
			LOGGER.info("EVENT=TRAIL_EXIT_SKIP symbol={} reason=NO_ENTRY_STATE", symbol);
			cleanupPositionState(symbol);
			cleanupPositionState(symbol);
			return;
		}

		BigDecimal exitQty = resolveExitQuantity(symbol, entryState, request.markPrice());
		if (exitQty == null || exitQty.signum() <= 0) {
			LOGGER.info("EVENT=TRAIL_EXIT_SKIP symbol={} reason=QTY_ZERO", symbol);
			cleanupPositionState(symbol);
			return;
		}

		if (!effectiveEnableOrders()) {
			LOGGER.info(
					"EVENT=TRAIL_EXIT_SIGNAL symbol={} reason={} side={} entryPrice={} markPrice={} leverageUsed={} pnlPct={} profitHits={} lossHardHits={} lossRecoveryHits={}",
					symbol, request.reason(), current, request.entryPrice(), request.markPrice(),
					request.leverageUsed(), String.format("%.4f", request.pnlPct()),
					request.profitExitCount(), request.lossHardExitCount(), request.lossRecoveryExitCount());
			return;
		}

		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> {
					String correlationId = orderTracker.nextCorrelationId(symbol, "TRAIL_EXIT");
					return closePosition(symbol, current, exitQty, hedgeMode, correlationId)
							.doOnNext(response -> {
								orderTracker.registerSubmitted(symbol, correlationId, response, true);
								logOrderEvent("TRAIL_EXIT_EXECUTED", symbol, request.reason(),
										current == PositionState.LONG ? "SELL" : "BUY", exitQty, true,
										hedgeMode ? current.name() : "", correlationId, response, null);
							})
							.doOnError(error -> {
								logOrderEvent("TRAIL_EXIT_FAILED", symbol, request.reason(),
										current == PositionState.LONG ? "SELL" : "BUY", exitQty, true,
										hedgeMode ? current.name() : "", correlationId, null, error.getMessage());
								// ✅ CRITICAL: Reset trailing state on error to allow retry
								trailingPnlService.resetClosingFlag(symbol);
							});
				})
				.doOnNext(response -> {
					positionStates.put(symbol, PositionState.NONE);
					entryStates.remove(symbol);
					clearTrailingArmed(symbol);
					trailingPnlService.resetState(symbol);
				})
				.doOnError(error -> {
					LOGGER.warn("EVENT=TRAIL_EXIT_FAILED symbol={} reason={}", symbol, error.getMessage());
					trailingPnlService.resetClosingFlag(symbol);
				})
				.onErrorResume(error -> Mono.empty())
				.subscribe();
	}
	private void cleanupPositionState(String symbol) {
		positionStates.put(symbol, PositionState.NONE);
		entryStates.remove(symbol);
		clearTrailingArmed(symbol);
		trailingPnlService.resetState(symbol);
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
		EntryFilterState entryFilterState = resolveEntryFilterState(
				fiveMinDir,
				signal.score1m(),
				close,
				candle.volume(),
				closeTime,
				symbolState);
		EntryDecision entryDecision = resolveEntryDecision(current, symbolState, entryFilterState);
		entryDecision = applyPendingFlipGate(symbol, current, entryDecision, entryFilterState, closeTime);

		CtiDirection recommendationRaw = signal.recommendation();
		CtiDirection recommendationUsed = signal.insufficientData() ? CtiDirection.NEUTRAL : recommendationRaw;
		CtiDirection confirmedRec = recommendationUsed;
		if (current == PositionState.NONE && entryDecision.confirmedRec() != null) {
			confirmedRec = entryDecision.confirmedRec();
		}
		if (signal.recReason() == CtiScoreCalculator.RecReason.TIE_HOLD) {
			confirmedRec = CtiDirection.NEUTRAL;
		}
		if (strategyProperties.pnlTrailEnabled()
				&& current != PositionState.NONE
				&& Boolean.TRUE.equals(trailingArmedBySymbol.get(symbol))) {
			return;
		}

		boolean trendAlignedWithPosition = resolveTrendAlignedWithPosition(
				current,
				fiveMinDir);
		double coreScore = signal.macdScore() + signal.ctiScore();
		int dirSign = ScoreMath.sign(coreScore);
		double rsi9_5m = symbolState.rsi9_5mValue;
		double volume5m = candle.volume();
		double volumeSma10_5m = symbolState.volumeSma10_5mValue;
		VolRsiConfidence confidence = resolveVolRsiConfidence(dirSign, rsi9_5m, volume5m, volumeSma10_5m);
		double rsiVolScore = resolveRsiVolScore(dirSign, confidence.conf());
		double scoreAfterSafety = coreScore + rsiVolScore;
		double adxScore = resolveAdxScore(scoreAfterSafety, signal.adx5m());
		double totalScore = scoreAfterSafety + adxScore;
		EntryGateMetrics entryGateMetrics = EntryGateMetrics.empty();
		if (current == PositionState.NONE) {
			CtiDirection candidateSide = entryDecision.confirmedRec();
			double rsi9Used = !Double.isNaN(symbolState.rsi9_5mValue)
					? symbolState.rsi9_5mValue
					: entryFilterState.rsi9();
			entryDecision = applyMacdEntryGates(entryDecision, candidateSide, signal);
			EntryGateEvaluation gateEvaluation = applyEntrySafetyGates(
					entryDecision,
					candidateSide,
					signal,
					entryFilterState,
					rsi9Used,
					close);
			entryDecision = gateEvaluation.entryDecision();
			entryGateMetrics = gateEvaluation.metrics();
		}
		logEntryFilterState(symbol, symbolState, closeTime, close, prevClose, entryFilterState, entryDecision,
				entryGateMetrics);
		updateMacdStreak(symbolState, signal);
		EmergencyExitEvaluation emergencyEval = evaluateEmergencyExit(symbol, current, close, signal, symbolState);
		boolean emergencyExitTriggered = emergencyEval.emergencyConfirmed();
		String emergencyExitReason = emergencyExitTriggered ? resolveEmergencyExitReason(current) : null;
		double adxExitPressure = signal.adxExitPressure() == null ? 0.0 : signal.adxExitPressure();
		double rsiVolExitPressure = resolveRsiVolExitPressure(confidence.conf());
		int posSign = current == PositionState.LONG ? 1 : current == PositionState.SHORT ? -1 : 0;
		double totalScoreForExit = current == PositionState.NONE
				? totalScore
				: totalScore - (posSign * (adxExitPressure + rsiVolExitPressure));
		boolean normalExitConfirmed = false;
		if (current == PositionState.LONG) {
			// Close LONG only when score meaningfully flips bearish.
			normalExitConfirmed = totalScoreForExit <= -1.0;
		} else if (current == PositionState.SHORT) {
			// Close SHORT only when score meaningfully flips bullish.
			normalExitConfirmed = totalScoreForExit >= 1.0;
		}
		if (emergencyExitTriggered) {
			normalExitConfirmed = false;
		}
		boolean scoreExitConfirmed = current != PositionState.NONE && (emergencyExitTriggered || normalExitConfirmed);
		String decisionValue = resolveDecisionValue(current, totalScore, emergencyExitTriggered,
				normalExitConfirmed, entryDecision);
		if (!warmupMode) {
			LOGGER.info(
					"EVENT=POST_WARMUP_CALC symbol={} t5mCloseUsed={} close5m={} outHist={} outHistPrev={} histColor={}"
							+ " macdScore={} cti1mDir={} cti5mDir={} ctiScore={} rsi9_5m={} volume5m={} volumeSma10_5m={}"
							+ " volRatio={} volConf={} rsiConf={} conf={} rsiVolScore={} rsiVolExitPressure={} adx5m={}"
							+ " adxSma10={} adxScore={} coreScore={} scoreAfterSafety={} totalScore={} totalScoreForExit={}",
					symbol,
					signal.t5mCloseUsed(),
					close,
					signal.outHist(),
					signal.outHistPrev(),
					signal.macdHistColor(),
					signal.macdScore(),
					signal.cti1mDir(),
					signal.cti5mDir(),
					signal.ctiScore(),
					confidence.rsi9(),
					volume5m,
					confidence.volumeSma10(),
					confidence.volRatio(),
					confidence.volConf(),
					confidence.rsiConf(),
					confidence.conf(),
					rsiVolScore,
					rsiVolExitPressure,
					signal.adx5m(),
					signal.adxSma10(),
					adxScore,
					coreScore,
					scoreAfterSafety,
					totalScore,
					totalScoreForExit);
		}

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
				symbolState.ema20_5mValue,
				symbolState.isEma20Ready(),
				symbolState.rsi9_5mValue,
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
		recordDecisionSnapshot(
				symbol,
				signal,
				candle,
				coreScore,
				confidence,
				rsiVolScore,
				rsiVolExitPressure,
				adxScore,
				scoreAfterSafety,
				totalScore,
				totalScoreForExit,
				current,
				decisionValue,
				entryDecision,
				entryFilterState,
				fiveMinDir,
				confirmedRec,
				recommendationUsed,
				trendHoldActive,
				scoreExitConfirmed,
				emergencyExitTriggered,
				normalExitConfirmed);
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
		double leveragedPnlPct = Double.NaN;
		if (strategyProperties.pnlTrailEnabled()
				&& current != PositionState.NONE
				&& entryState != null) {
			leveragedPnlPct = calculateLeveragedPnlPct(entryState, close);
			if (!Double.isNaN(leveragedPnlPct) && leveragedPnlPct >= resolvePnlTrailProfitArm()) {
				setTrailingArmed(symbol, true);
			}
		}
		boolean trailingExitOnly = isTrailingExitOnly(symbol, current);
		boolean trailingLossOnly = !Double.isNaN(leveragedPnlPct) && leveragedPnlPct <= resolvePnlTrailLossArm();
		if (trailingLossOnly) {
			trailingExitOnly = true;
		}
		if (trailingExitOnly) {
			exitDecision = new CtiLbDecisionEngine.ExitDecision(false, "TRAILING_EXIT_ONLY", exitDecision.pnlBps());
		}
		Double estimatedPnlPct = exitDecision.pnlBps() / 100.0;
		double pnlBps = exitDecision.pnlBps();
		updateMaxPnlState(symbolState, entryState, pnlBps);
		double maxPnlBps = symbolState.maxPnlBpsSinceEntry;
		double givebackBps = Double.isNaN(maxPnlBps) ? Double.NaN : maxPnlBps - pnlBps;
		boolean givebackExit = !Double.isNaN(givebackBps)
				&& maxPnlBps > 0.0
				&& givebackBps >= GIVEBACK_THRESHOLD_BPS;
		if (givebackExit && !exitDecision.exit()) {
			exitDecision = new CtiLbDecisionEngine.ExitDecision(true, "EXIT_GIVEBACK", pnlBps);
		}

		if (current == PositionState.NONE && !effectiveEnableOrders() && entryState != null && exitDecision.exit()) {
			BigDecimal exitQty = resolveExitQuantity(symbol, entryState, close);
			if (exitQty != null && exitQty.signum() > 0) {
					recordSignalSnapshot(symbol, "EXIT", SignalAction.HOLD, signal, closeTime, close, exitQty,
							PositionState.NONE, null, entryState, entryFilterState, null, exitDecision.reason(),
							CtiLbDecisionEngine.resolveExitDecisionBlockReason(), recommendationUsed,
							recommendationRaw, confirmedRec);
				positionStates.put(symbol, PositionState.NONE);
				entryStates.remove(symbol);
				clearTrailingArmed(symbol);
			}
			return;
		}

		if (current != PositionState.NONE && exitDecision.exit()) {
			SignalAction exitAction = SignalAction.HOLD;
			String decisionActionReason = exitDecision.reason();
			String decisionBlockReason = CtiLbDecisionEngine.resolveExitDecisionBlockReason();
			BigDecimal exitQty = resolveExitQuantity(symbol, entryState, close);
			boolean stopLossExit = isStopLossExit(decisionActionReason);
			boolean hardExitReason = isHardExitReason(decisionActionReason);
			boolean trailStopHit = trailState.trailStopHit();
			boolean reversalConfirm = evaluateReversalConfirm(current, close, signal, symbolState);
			boolean skipHoldChecks = emergencyExitTriggered || stopLossExit || trailStopHit || givebackExit || hardExitReason;
			boolean exitBlockedByTrendAligned = false;
			boolean continuationHold = false;
			boolean holdExit = false;
			boolean topZone = isTopZone(confidence);
			boolean bottomZone = isBottomZone(confidence);
			boolean softExit = !stopLossExit && !hardExitReason && !givebackExit && !trailStopHit;
			boolean macdStreakConfirmed = resolveMacdStreakConfirmed(current, symbolState);
			boolean exitAllowed = true;
			String blockedReason = null;
			if (softExit && !reversalConfirm) {
				if (current == PositionState.LONG && bottomZone) {
					exitAllowed = false;
					blockedReason = "BOTTOM_ZONE_SOFT_EXIT_BLOCK";
				} else if (current == PositionState.SHORT && topZone) {
					exitAllowed = false;
					blockedReason = "TOP_ZONE_SOFT_EXIT_BLOCK";
				} else if (!macdStreakConfirmed) {
					exitAllowed = false;
					blockedReason = "SOFT_EXIT_MACD_STREAK";
				}
			}
			LOGGER.info(
					"EVENT=EXIT_GUARD symbol={} side={} topZone={} bottomZone={} emergencyBase={} c1={} c2={} c3={} emergencyConfirmed={} normalExitConfirmed={} stopLossExit={} skipHoldChecks={} finalExitAllowed={} blockedReason={}",
					symbol,
					current,
					topZone,
					bottomZone,
					emergencyEval.base(),
					emergencyEval.c1(),
					emergencyEval.c2(),
					emergencyEval.c3(),
					emergencyExitTriggered,
					normalExitConfirmed,
					stopLossExit,
					skipHoldChecks,
					exitAllowed,
					blockedReason == null ? "NA" : blockedReason);
			LOGGER.info(
					"EVENT=EXIT_ACCEL symbol={} side={} reversalConfirm={} givebackBps={} maxPnlBps={} skipHoldChecks={} finalExitAllowed={}",
					symbol,
					current,
					reversalConfirm,
					Double.isNaN(givebackBps) ? "NA" : String.format("%.2f", givebackBps),
					Double.isNaN(maxPnlBps) ? "NA" : String.format("%.2f", maxPnlBps),
					skipHoldChecks,
					exitAllowed);
			if (!exitAllowed) {
				decisionActionReason = blockedReason;
				decisionBlockReason = blockedReason;
				logDecision(symbol, signal, close, exitAction, confirmedRec, recommendationUsed,
						recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason,
						decisionBlockReason, decisionTrailState, decisionContinuationState, flipGateReason,
						qualityScoreForLog, qualityConfirmReason, trendHoldActive,
						trendHoldReason, flipQualityScore, decisionTpTrailingState,
						trendAlignedWithPosition, exitBlockedByTrendAligned);
				return;
			}
			if (skipHoldChecks) {
				if (emergencyExitTriggered) {
					decisionActionReason = emergencyExitReason;
				} else if (normalExitConfirmed && !givebackExit) {
					decisionActionReason = current == PositionState.LONG ? "CLOSE_LONG" : "CLOSE_SHORT";
				}
			}
			if (!skipHoldChecks && !reversalConfirm) {
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
				continuationHold = decisionContinuationState.holdApplied() && !stopLossExit;
				holdExit = shouldHoldTrendStrong(
						entryState == null ? null : entryState.side(),
						entryState == null ? null : entryState.entryPrice(),
						close,
						fiveMinDir,
						symbolState.ema20_5mValue,
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
			}
			logDecision(symbol, signal, close, exitAction, confirmedRec, recommendationUsed,
					recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason, decisionBlockReason,
					decisionTrailState, decisionContinuationState, flipGateReason,
					qualityScoreForLog, qualityConfirmReason, trendHoldActive, trendHoldReason,
					flipQualityScore, decisionTpTrailingState, trendAlignedWithPosition,
					exitBlockedByTrendAligned);
			if (!continuationHold && !holdExit && exitQty != null && exitQty.signum() > 0) {
				recordSignalSnapshot(symbol, "EXIT", exitAction, signal, closeTime, close, exitQty,
						current, null, entryState, entryFilterState, null, decisionActionReason, decisionBlockReason, recommendationUsed,
						recommendationRaw, confirmedRec);
			}
			if (continuationHold || holdExit || !effectiveEnableOrders()) {
				if (!effectiveEnableOrders()) {
					positionStates.put(symbol, PositionState.NONE);
					entryStates.remove(symbol);
					clearTrailingArmed(symbol);
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
						clearTrailingArmed(symbol);
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
		if (current != PositionState.NONE && action != SignalAction.HOLD && trailingExitOnly) {
			action = SignalAction.HOLD;
			decisionActionReason = "TRAILING_EXIT_ONLY";
			decisionBlockReason = "TRAILING_EXIT_ONLY";
		}
		if (current == PositionState.NONE && totalScore == 0.0 && action != SignalAction.HOLD) {
			action = SignalAction.HOLD;
			decisionActionReason = "ZERO_SCORE_GATE";
			decisionBlockReason = "ZERO_SCORE_GATE";
		}
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
					symbolState.ema20_5mValue,
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
							signal.cti5mValue() == null ? Double.NaN : signal.cti5mValue(),
							signal.cti5mPrev() == null ? Double.NaN : signal.cti5mPrev(),
							strategyProperties.minBfrDelta(),
							strategyProperties.minPriceMoveBps(),
							lastFlipPrices.get(symbol)));
			if (blockDecision.blocked()) {
				decisionActionReason = blockDecision.reason();
				action = SignalAction.HOLD;
			}
		}
		if (action != SignalAction.HOLD) {
			Double adx5m = signal.adx5m();
			if (adx5m != null && adx5m < 15.0) {
				action = SignalAction.HOLD;
				decisionActionReason = "NO_ENTRY_ADX_BELOW_15";
				decisionBlockReason = "NO_ENTRY_ADX_BELOW_15";
			}
		}
		if (action != SignalAction.HOLD) {
			CtiDirection targetDir = target == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT;
			ExtremeGuardDecision extremeGuardDecision = evaluateExtremeGuardrail(
					targetDir,
					close,
					signal,
					confidence,
					symbolState);
			if (extremeGuardDecision != null) {
				LOGGER.info(
						"EVENT=EXTREME_GUARD symbol={} zone={} vetoDir={} c1={} c2={} c3={} final={}",
						symbol,
						extremeGuardDecision.zone(),
						extremeGuardDecision.vetoDir(),
						extremeGuardDecision.c1(),
						extremeGuardDecision.c2(),
						extremeGuardDecision.c3(),
						extremeGuardDecision.finalDecision());
				if (extremeGuardDecision.blocked()) {
					action = SignalAction.HOLD;
					decisionActionReason = extremeGuardDecision.reason();
					decisionBlockReason = extremeGuardDecision.reason();
				}
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
							target, target, entrySnapshot, entryFilterState, entryGateMetrics, decisionActionReason,
							decisionBlockReason,
							recommendationUsed, recommendationRaw, confirmedRec);
					positionStates.put(symbol, target);
					clearTrailingArmed(symbol);
					entryStates.put(symbol, entrySnapshot);
				} else if (target != current) {
					recordSignalSnapshot(symbol, "EXIT", action, signal, closeTime, close, closeQty,
							current, target, entryState, entryFilterState, null, decisionActionReason, decisionBlockReason,
							recommendationUsed, recommendationRaw, confirmedRec);
					EntryState entrySnapshot = new EntryState(
							target == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
							BigDecimal.valueOf(close),
							closeTime,
							resolvedQty);
					recordSignalSnapshot(symbol, "ENTRY", action, signal, closeTime, close, resolvedQty,
							target, target, entrySnapshot, entryFilterState, entryGateMetrics, decisionActionReason,
							decisionBlockReason,
							recommendationUsed, recommendationRaw, confirmedRec);
					positionStates.put(symbol, target);
					clearTrailingArmed(symbol);
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
						targetForLog, targetForLog, entrySnapshot, entryFilterState, entryGateMetrics,
						decisionActionReasonForLog, decisionBlock,
						recommendationUsedForLog, recommendationRawForLog, confirmedRecForLog);
				if (!effectiveEnableOrders()) {
					positionStates.put(symbol, targetForLog);
					clearTrailingArmed(symbol);
					entryStates.put(symbol, entrySnapshot);
				}
			} else if (targetForLog != currentForLog) {
				recordSignalSnapshot(symbol, "EXIT", actionForLog, signal, closeTime, close, closeQty, currentForLog,
						targetForLog, entryState, entryFilterState, null, decisionActionReasonForLog, decisionBlock, recommendationUsedForLog,
						recommendationRawForLog, confirmedRecForLog);
				EntryState entrySnapshot = new EntryState(
						targetForLog == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
						BigDecimal.valueOf(close),
						closeTime,
						resolvedQtyForLog);
				recordSignalSnapshot(symbol, "ENTRY", actionForLog, signal, closeTime, close, resolvedQtyForLog,
						targetForLog, targetForLog, entrySnapshot, entryFilterState, entryGateMetrics,
						decisionActionReasonForLog, decisionBlock,
						recommendationUsedForLog, recommendationRawForLog, confirmedRecForLog);
				if (!effectiveEnableOrders()) {
					positionStates.put(symbol, targetForLog);
					clearTrailingArmed(symbol);
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

	public void onClosedOneMinuteCandle(String symbol, Candle candle) {
		SymbolState symbolState = symbolStates.computeIfAbsent(symbol, ignored -> new SymbolState());
		symbolState.updateOneMinuteIndicators(candle);
		symbolState.prevClose1m = candle.close();
		symbolState.prevHigh1m = candle.high();
		symbolState.prevLow1m = candle.low();
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
			return fiveMinDir == 1 && close > state.ema20_5mValue && close > state.ema200_5mValue;
		}
		if (side == CtiDirection.SHORT) {
			return fiveMinDir == -1 && close < state.ema20_5mValue && close < state.ema200_5mValue;
		}
		return false;
	}

	private boolean isEma20Break(PositionState current, SymbolState state, double close) {
		if (current == null || current == PositionState.NONE || !state.isEma20Ready()) {
			return false;
		}
		if (current == PositionState.LONG) {
			return close < state.ema20_5mValue;
		}
		if (current == PositionState.SHORT) {
			return close > state.ema20_5mValue;
		}
		return false;
	}

	private boolean resolveTrendAlignedWithPosition(PositionState current, int fiveMinDir) {
		if (current == PositionState.LONG) {
			return fiveMinDir == 1;
		}
		if (current == PositionState.SHORT) {
			return fiveMinDir == -1;
		}
		return false;
	}

	private ExtremeGuardDecision evaluateExtremeGuardrail(CtiDirection targetDir, double close, ScoreSignal signal,
			VolRsiConfidence confidence, SymbolState state) {
		if (targetDir == null || targetDir == CtiDirection.NEUTRAL || signal == null || confidence == null
				|| state == null) {
			return null;
		}
		double rsi9 = confidence.rsi9();
		double volRatio = confidence.volRatio();
		if (Double.isNaN(rsi9) || Double.isNaN(volRatio)) {
			return null;
		}
		boolean topZone = rsi9 > 73.0 && volRatio < 0.8;
		boolean bottomZone = rsi9 < 32.0 && volRatio > 2.4;
		if (!topZone && !bottomZone) {
			return null;
		}
		double macdHist = signal.outHist();
		double macdHistPrev = signal.outHistPrev();
		MacdHistColor histColor = signal.macdHistColor();
		boolean prevCloseLower = !Double.isNaN(state.prevClose1m) && close < state.prevClose1m;
		boolean prevCloseHigher = !Double.isNaN(state.prevClose1m) && close > state.prevClose1m;
		boolean prevLowLower = !Double.isNaN(state.prevLow1m) && close < state.prevLow1m;
		boolean prevHighHigher = !Double.isNaN(state.prevHigh1m) && close > state.prevHigh1m;
		boolean rsiPrevReady = !Double.isNaN(state.rsi9_5mPrev);
		if (topZone) {
			boolean c1 = !Double.isNaN(macdHist) && !Double.isNaN(macdHistPrev) && macdHist < macdHistPrev;
			boolean c2 = rsiPrevReady && rsi9 < state.rsi9_5mPrev;
			boolean c3 = prevCloseLower || prevLowLower || histColor == MacdHistColor.BLUE;
			if (targetDir == CtiDirection.LONG) {
				return new ExtremeGuardDecision(true, "EXTREME_GUARD_TOP_LONG_VETO", "TOP", "LONG", c1, c2, c3,
						"NO_ENTRY");
			}
			boolean allow = countTrue(c1, c2, c3) >= 2;
			return new ExtremeGuardDecision(!allow, "EXTREME_GUARD_TOP_SHORT_CONFIRM", "TOP", "SHORT", c1, c2, c3,
					allow ? "ALLOW_SHORT" : "NO_ENTRY");
		}
		boolean c1 = !Double.isNaN(macdHist) && !Double.isNaN(macdHistPrev) && macdHist > macdHistPrev;
		boolean c2 = rsiPrevReady && rsi9 > state.rsi9_5mPrev;
		boolean c3 = prevCloseHigher || prevHighHigher || histColor == MacdHistColor.MAROON;
		if (targetDir == CtiDirection.SHORT) {
			return new ExtremeGuardDecision(true, "EXTREME_GUARD_BOTTOM_SHORT_VETO", "BOTTOM", "SHORT", c1, c2, c3,
					"NO_ENTRY");
		}
		boolean allow = countTrue(c1, c2, c3) >= 2;
		return new ExtremeGuardDecision(!allow, "EXTREME_GUARD_BOTTOM_LONG_CONFIRM", "BOTTOM", "LONG", c1, c2, c3,
				allow ? "ALLOW_LONG" : "NO_ENTRY");
	}

	private static int countTrue(boolean... checks) {
		int count = 0;
		if (checks == null) {
			return 0;
		}
		for (boolean check : checks) {
			if (check) {
				count++;
			}
		}
		return count;
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
				state.ema20_5mValue,
				state.isEma20Ready(),
				state.ema200_5mValue,
				state.isEma200Ready(),
				state.rsi9_5mValue,
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
			EntryFilterState entryFilterState, EntryDecision entryDecision, EntryGateMetrics entryGateMetrics) {
		if (warmupMode) {
			return;
		}
		LOGGER.info("EVENT=ENTRY_FILTER_STATE symbol={} fiveMinDir={} lastFiveMinDirPrev={} flipTimeMs={} prevClose1m={} scoreAligned={} entryTrigger={} triggerReason={} blockReason={} rsi9Used={} outHist={} outHistPrev={} macdDelta={} ema20={} ema20DistPct={} ema20_5m={} ema200_5m={} rsi9={} vol={} volSma10={} atr14={} atrSma20={} qualityScore={} ema200Ok={} ema20Ok={} rsiOk={} volOk={} atrOk={} qualityBlockReason={}",
				symbol,
				state.lastFiveMinDir,
				state.prevFiveMinDir,
				entryFilterState.flipTimeMs() == 0L ? "NA" : entryFilterState.flipTimeMs(),
				Double.isNaN(prevClose) ? "NA" : String.format("%.8f", prevClose),
				entryFilterState.scoreAligned(),
				entryFilterState.entryTrigger(),
				entryFilterState.triggerReason(),
				entryDecision.blockReason(),
				entryGateMetrics.rsi9UsedLabel(),
				entryGateMetrics.outHistLabel(),
				entryGateMetrics.outHistPrevLabel(),
				entryGateMetrics.macdDeltaLabel(),
				entryGateMetrics.ema20Label(),
				entryGateMetrics.ema20DistPctLabel(),
				Double.isNaN(entryFilterState.ema20_5m()) ? "NA" : String.format("%.8f", entryFilterState.ema20_5m()),
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
				state.ema20_5mValue,
				state.isEma20Ready(),
				state.ema200_5mValue,
				state.isEma200Ready(),
				state.rsi9_5mValue,
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

		// 1m can be noisy; do NOT use it as the primary trigger.
		boolean scoreAligned = false;
		if (inputs.fiveMinDir() == 1) {
			scoreAligned = scoreAlignedLong;
		} else if (inputs.fiveMinDir() == -1) {
			scoreAligned = scoreAlignedShort;
		}

		boolean entryTrigger = hasDir; // primary trigger = 5m direction present
		String triggerReason = hasDir ? "CTI5M_DIR" : "NONE";
		if (scoreAligned) {
			triggerReason = "CTI5M_DIR+CTI1M_ALIGN";
		}
		EntryQualityEvaluation qualityEvaluation = evaluateEntryQuality(
				inputs.fiveMinDir(),
				inputs.close(),
				inputs.ema20_5m(),
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
				triggerReason, inputs.fiveMinFlipTimeMs(), inputs.fiveMinFlipPrice(), inputs.ema20_5m(),
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
				&& ((fiveMinDir == 1 && rsi9 >= 45.0 && rsi9 <= 75.0)
						|| (fiveMinDir == -1 && rsi9 >= 25.0 && rsi9 <= 55.0));
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
		if (!entryFilterState.entryTrigger()) {
			return new EntryDecision(null, null, null);
		}

		// Mandatory: EMA20 must confirm direction.
		if (!entryFilterState.ema20Ok()) {
			return new EntryDecision(null, "ENTRY_BLOCK_EMA20_REQUIRED", "ENTRY_BLOCK_EMA20_REQUIRED");
		}
		// Mandatory: RSI must confirm direction.
		if (!entryFilterState.rsiOk()) {
			return new EntryDecision(null, "ENTRY_BLOCK_RSI_REQUIRED", "ENTRY_BLOCK_RSI_REQUIRED");
		}

		// 5 checks (ema200, ema20, rsi, vol, atr) -> at least 3 must be TRUE.
		int okCount = 0;
		if (entryFilterState.ema200Ok()) {
			okCount++;
		}
		if (entryFilterState.ema20Ok()) {
			okCount++;
		}
		if (entryFilterState.rsiOk()) {
			okCount++;
		}
		if (entryFilterState.volOk()) {
			okCount++;
		}
		if (entryFilterState.atrOk()) {
			okCount++;
		}
		if (okCount < 3) {
			return new EntryDecision(null, "ENTRY_BLOCK_QUALITY_3_OF_5_" + okCount, "ENTRY_BLOCK_QUALITY_3_OF_5_" + okCount);
		}

		CtiDirection confirmed = entryFilterState.fiveMinDir() == 1 ? CtiDirection.LONG : CtiDirection.SHORT;
		String reason = "ENTRY_QUALITY_3_OF_5_CONFIRMED_" + okCount;
		return new EntryDecision(confirmed, null, reason);
	}

	private EntryDecision applyMacdEntryGates(EntryDecision entryDecision, CtiDirection candidateSide, ScoreSignal signal) {
		if (candidateSide == null || candidateSide == CtiDirection.NEUTRAL) {
			return entryDecision;
		}
		if (entryDecision.blockReason() != null) {
			return entryDecision;
		}
		if (signal == null || Double.isNaN(signal.outHist()) || Math.abs(signal.outHist()) <= MACD_HIST_EPS) {
			return entryDecision.withBlockReason("ENTRY_BLOCK_MACD_HIST_EPS");
		}
		MacdHistColor histColor = signal.macdHistColor();

		// Require MACD histogram color to SUPPORT the 5m direction (same side).
		if (candidateSide == CtiDirection.LONG) {
			if (histColor != MacdHistColor.AQUA && histColor != MacdHistColor.BLUE) {
				return entryDecision.withBlockReason("ENTRY_BLOCK_MACD_NOT_LONG");
			}
		} else if (candidateSide == CtiDirection.SHORT) {
			if (histColor != MacdHistColor.RED && histColor != MacdHistColor.MAROON) {
				return entryDecision.withBlockReason("ENTRY_BLOCK_MACD_NOT_SHORT");
			}
		}

		return entryDecision;
	}

	private EntryGateEvaluation applyEntrySafetyGates(EntryDecision entryDecision, CtiDirection candidateSide,
			ScoreSignal signal, EntryFilterState entryFilterState, double rsi9Used, double close) {
		double outHist = signal == null ? Double.NaN : signal.outHist();
		double outHistPrev = signal == null ? Double.NaN : signal.outHistPrev();
		double macdDelta = Double.isNaN(outHist) || Double.isNaN(outHistPrev) ? Double.NaN : outHist - outHistPrev;
		double ema20 = entryFilterState == null ? Double.NaN : entryFilterState.ema20_5m();
		double ema20DistPct = Double.NaN;
		if (candidateSide != null && !Double.isNaN(ema20) && ema20 > 0) {
			if (candidateSide == CtiDirection.LONG) {
				ema20DistPct = (close - ema20) / ema20;
			} else if (candidateSide == CtiDirection.SHORT) {
				ema20DistPct = (ema20 - close) / ema20;
			}
		}
		EntryDecision updatedDecision = entryDecision;
		if (candidateSide != null && candidateSide != CtiDirection.NEUTRAL && entryDecision.blockReason() == null) {
			if (candidateSide == CtiDirection.SHORT && !Double.isNaN(rsi9Used) && rsi9Used < 40.0) {
				updatedDecision = entryDecision.withBlockReason("ENTRY_BLOCK_RSI_TOO_LOW_FOR_SHORT");
			} else if (candidateSide == CtiDirection.LONG && !Double.isNaN(rsi9Used) && rsi9Used > 65.0) {
				updatedDecision = entryDecision.withBlockReason("ENTRY_BLOCK_RSI_TOO_HIGH_FOR_LONG");
			} else if (candidateSide == CtiDirection.LONG && !Double.isNaN(macdDelta) && macdDelta <= 0.0) {
				updatedDecision = entryDecision.withBlockReason("ENTRY_BLOCK_MACD_SLOPE_DOWN");
			} else if (candidateSide == CtiDirection.SHORT && !Double.isNaN(macdDelta) && macdDelta >= 0.0) {
				updatedDecision = entryDecision.withBlockReason("ENTRY_BLOCK_MACD_SLOPE_UP");
			} else if (!Double.isNaN(ema20DistPct) && ema20DistPct > EMA20_MAX_DIST_PCT) {
				updatedDecision = entryDecision.withBlockReason("ENTRY_BLOCK_EMA20_TOO_FAR");
			}
		}
		EntryGateMetrics metrics = new EntryGateMetrics(rsi9Used, outHist, outHistPrev, macdDelta, ema20, ema20DistPct,
				updatedDecision.blockReason());
		return new EntryGateEvaluation(updatedDecision, metrics);
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
			double ema20_5m,
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
			double ema20_5m,
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

	private record EntryGateEvaluation(EntryDecision entryDecision, EntryGateMetrics metrics) {
	}

	private record EmergencyExitEvaluation(
			boolean base,
			boolean c1,
			boolean c2,
			boolean c3,
			boolean emergencyConfirmed) {
		static EmergencyExitEvaluation empty() {
			return new EmergencyExitEvaluation(false, false, false, false, false);
		}
	}

	private record ExtremeGuardDecision(
			boolean blocked,
			String reason,
			String zone,
			String vetoDir,
			boolean c1,
			boolean c2,
			boolean c3,
			String finalDecision) {
	}

	private record EntryGateMetrics(
			double rsi9Used,
			double outHist,
			double outHistPrev,
			double macdDelta,
			double ema20,
			double ema20DistPct,
			String blockReason) {
		static EntryGateMetrics empty() {
			return new EntryGateMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null);
		}

		private String rsi9UsedLabel() {
			return Double.isNaN(rsi9Used) ? "NA" : String.format("%.2f", rsi9Used);
		}

		private String outHistLabel() {
			return Double.isNaN(outHist) ? "NA" : String.format("%.8f", outHist);
		}

		private String outHistPrevLabel() {
			return Double.isNaN(outHistPrev) ? "NA" : String.format("%.8f", outHistPrev);
		}

		private String macdDeltaLabel() {
			return Double.isNaN(macdDelta) ? "NA" : String.format("%.8f", macdDelta);
		}

		private String ema20Label() {
			return Double.isNaN(ema20) ? "NA" : String.format("%.8f", ema20);
		}

		private String ema20DistPctLabel() {
			return Double.isNaN(ema20DistPct) ? "NA" : String.format("%.4f", ema20DistPct);
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
		private double prevHigh1m = Double.NaN;
		private double prevLow1m = Double.NaN;
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
		private final EMAIndicator ema20_5m = new EMAIndicator(closePrice5m, EMA_20_PERIOD);
		private final EMAIndicator ema200_5m = new EMAIndicator(closePrice5m, EMA_200_PERIOD);
		private final RSIIndicator rsi9_5m = new RSIIndicator(closePrice5m, RSI_9_PERIOD);
		private final ATRIndicator atr14_1m = new ATRIndicator(series1m, ATR_14_PERIOD);
		private final SMAIndicator atrSma20_1m = new SMAIndicator(atr14_1m, ATR_SMA_20_PERIOD);
		private final VolumeIndicator volume1m = new VolumeIndicator(series1m);
		private final SMAIndicator volumeSma10_1m = new SMAIndicator(volume1m, VOLUME_SMA_10_PERIOD);
		private final VolumeIndicator volume5m = new VolumeIndicator(series5m);
		private final SMAIndicator volumeSma10_5m = new SMAIndicator(volume5m, VOLUME_SMA_10_PERIOD);
		private double ema20_5mValue = Double.NaN;
		private double ema200_5mValue = Double.NaN;
		private double rsi9_5mValue = Double.NaN;
		private double rsi9_5mPrev = Double.NaN;
		private int macdDownStreak;
		private int macdUpStreak;
		private long maxPnlEntryTimeMs;
		private double maxPnlBpsSinceEntry = Double.NaN;
		private double atr14Value = Double.NaN;
		private double atrSma20Value = Double.NaN;
		private double volumeSma10Value = Double.NaN;
		private double volumeSma10_5mValue = Double.NaN;

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
			ema20_5mValue = ema20_5m.getValue(index).doubleValue();
			ema200_5mValue = ema200_5m.getValue(index).doubleValue();
			rsi9_5mPrev = rsi9_5mValue;
			rsi9_5mValue = rsi9_5m.getValue(index).doubleValue();
			volumeSma10_5mValue = volumeSma10_5m.getValue(index).doubleValue();
		}

		private boolean isTrendEmaReady() {
			return isEma20Ready() && isEma200Ready();
		}

		private boolean isEma20Ready() {
			return series5m.getBarCount() >= EMA_20_PERIOD;
		}

		private boolean isEma200Ready() {
			return series5m.getBarCount() >= EMA_200_PERIOD;
		}

		private boolean isRsiReady() {
			return series5m.getBarCount() >= RSI_9_PERIOD;
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
			EntryState entryState, EntryFilterState entryFilterState, EntryGateMetrics entryGateMetrics,
			String decisionActionReason, String decisionBlockReason,
			CtiDirection recommendationUsed, CtiDirection recommendationRaw, CtiDirection confirmedRec) {
		try {
			Files.createDirectories(SIGNAL_OUTPUT_DIR);
			Path outputFile = SIGNAL_OUTPUT_DIR.resolve(symbol + ".json");
			Object lock = signalFileLocks.computeIfAbsent(symbol, ignored -> new Object());
			synchronized (lock) {
				ArrayNode arrayNode = objectMapper.createArrayNode();
				String lastAction = null;
				if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
					JsonNode existing = objectMapper.readTree(outputFile.toFile());
					if (existing != null) {
						if (existing.isArray()) {
							arrayNode = (ArrayNode) existing;
							if (!arrayNode.isEmpty()) {
								JsonNode lastNode = arrayNode.get(arrayNode.size() - 1);
								if (lastNode != null) {
									lastAction = lastNode.path("action").asText(null);
								}
							}
						} else {
							arrayNode.add(existing);
						}
					}
				}
				String actionValue = action == null ? "NA" : action.name();
				if (lastAction != null && lastAction.equals(actionValue)) {
					return;
				}
				ObjectNode payload = objectMapper.createObjectNode();
				payload.put("symbol", symbol);
				payload.put("signalType", signalType);
				payload.put("positionType", signalType);
				payload.put("action", actionValue);
				payload.put("timestamp", formatTimestamp(closeTime));
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
					ObjectNode indicatorsNode = objectMapper.createObjectNode();
					if (entryFilterState != null) {
						indicatorsNode.put("rsi9", entryFilterState.rsi9());
						indicatorsNode.put("volume", entryFilterState.volume());
						indicatorsNode.put("volumeSma10", entryFilterState.volumeSma10());
						indicatorsNode.put("atr14", entryFilterState.atr14());
						indicatorsNode.put("atrSma20", entryFilterState.atrSma20());
						indicatorsNode.put("ema20_5m", entryFilterState.ema20_5m());
						indicatorsNode.put("ema200_5m", entryFilterState.ema200_5m());
						indicatorsNode.put("qualityScore", entryFilterState.qualityScore());
						indicatorsNode.put("ema200Ok", entryFilterState.ema200Ok());
						indicatorsNode.put("ema20Ok", entryFilterState.ema20Ok());
						indicatorsNode.put("rsiOk", entryFilterState.rsiOk());
						indicatorsNode.put("volOk", entryFilterState.volOk());
						indicatorsNode.put("atrOk", entryFilterState.atrOk());
						if (entryFilterState.qualityBlockReason() != null) {
							indicatorsNode.put("qualityBlockReason", entryFilterState.qualityBlockReason());
						}
					}
					if (entryGateMetrics != null) {
						indicatorsNode.put("rsi9Used", entryGateMetrics.rsi9Used());
						indicatorsNode.put("outHist", entryGateMetrics.outHist());
						indicatorsNode.put("outHistPrev", entryGateMetrics.outHistPrev());
						indicatorsNode.put("macdDelta", entryGateMetrics.macdDelta());
						indicatorsNode.put("ema20", entryGateMetrics.ema20());
						indicatorsNode.put("ema20DistPct", entryGateMetrics.ema20DistPct());
						if (entryGateMetrics.blockReason() != null) {
							indicatorsNode.put("entryBlockReason", entryGateMetrics.blockReason());
						}
					}
					payload.set("indicators", indicatorsNode);
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

	private void recordDecisionSnapshot(String symbol, ScoreSignal signal, Candle candle, double coreScore,
			VolRsiConfidence confidence, double rsiVolScore, double rsiVolExitPressure, double adxScore,
			double scoreAfterSafety, double totalScore, double totalScoreForExit, PositionState positionBefore,
			String decisionValue, EntryDecision entryDecision, EntryFilterState entryFilterState, int fiveMinDir,
			CtiDirection confirmedRec, CtiDirection recommendationUsed, boolean trendHoldActive,
			boolean scoreExitConfirmed, boolean emergencyExitTriggered, boolean normalExitConfirmed) {
		try {
			Files.createDirectories(SIGNAL_OUTPUT_DIR);
			Path outputFile = SIGNAL_OUTPUT_DIR.resolve(symbol + "-decision.json");
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("decisionTime", formatTimestamp(signal.closeTime()));
			payload.put("t5mCloseUsed", signal.t5mCloseUsed());
			putFinite(payload, "close5m", candle.close());
			putFinite(payload, "outHist", signal.outHist());
			putFinite(payload, "outHistPrev", signal.outHistPrev());
			payload.put("histColor", signal.macdHistColor() == null ? "NA" : signal.macdHistColor().name());
			putFinite(payload, "macdScore", signal.macdScore());
			payload.put("cti1mDir", signal.cti1mDir() == null ? "NA" : signal.cti1mDir().name());
			payload.put("cti5mDir", signal.cti5mDir() == null ? "NA" : signal.cti5mDir().name());
			putFinite(payload, "ctiScore", signal.ctiScore());
			putFinite(payload, "rsi9_5m", confidence.rsi9());
			putFinite(payload, "volume5m", candle.volume());
			putFinite(payload, "volumeSma10_5m", confidence.volumeSma10());
			putFinite(payload, "ema20", entryFilterState == null ? Double.NaN : entryFilterState.ema20_5m());
			putFinite(payload, "volRatio", confidence.volRatio());
			putFinite(payload, "volConf", confidence.volConf());
			putFinite(payload, "rsiConf", confidence.rsiConf());
			putFinite(payload, "conf", confidence.conf());
			putFinite(payload, "rsiVolScore", rsiVolScore);
			putFinite(payload, "rsiVolExitPressure", rsiVolExitPressure);
			putFinite(payload, "adx", signal.adx5m());
			putFinite(payload, "sma10", signal.adxSma10());
			putFinite(payload, "adxScore", adxScore);
			putFinite(payload, "coreScore", coreScore);
			putFinite(payload, "scoreAfterSafety", scoreAfterSafety);
			putFinite(payload, "totalScore", totalScore);
			if (positionBefore == PositionState.NONE) {
				putFinite(payload, "totalScoreUsedForEntry", totalScore);
			} else {
				payload.putNull("totalScoreUsedForEntry");
			}
			payload.put("drop3", signal.drop3());
			payload.put("crossDown", signal.crossDown());
			payload.put("belowSma", signal.belowSma());
			payload.put("deadZone", signal.deadZone());
			putFinite(payload, "adxExitPressure", signal.adxExitPressure());
			putFinite(payload, "totalScoreForExit", totalScoreForExit);
			payload.put("positionBefore", positionBefore == null ? "NA" : positionBefore.name());
			payload.put("decision", decisionValue);
			payload.put("entryBlockReason", entryDecision == null || entryDecision.blockReason() == null
					? "NA"
					: entryDecision.blockReason());
			payload.put("entryActionReason", entryDecision == null || entryDecision.decisionActionReason() == null
					? "NA"
					: entryDecision.decisionActionReason());
			payload.put("entryConfirmedRec", entryDecision == null || entryDecision.confirmedRec() == null
					? "NA"
					: entryDecision.confirmedRec().name());
			payload.put("entryQualityScore", entryFilterState == null ? 0 : entryFilterState.qualityScore());
			payload.put("fiveMinDir", fiveMinDir);
			payload.put("confirmedRecUsed", confirmedRec == null ? "NA" : confirmedRec.name());
			payload.put("recommendationUsed", recommendationUsed == null ? "NA" : recommendationUsed.name());
			payload.put("trendHoldActive", trendHoldActive);
			payload.put("scoreExitConfirmed", scoreExitConfirmed);
			payload.put("emergencyExitTriggered", emergencyExitTriggered);
			payload.put("normalExitConfirmed", normalExitConfirmed);
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), payload);
		} catch (IOException error) {
			LOGGER.warn("EVENT=DECISION_SNAPSHOT_FAILED symbol={} error={}", symbol, error.getMessage());
		}
	}

	private static Double finiteOrNull(double value) {
		return Double.isFinite(value) ? value : null;
	}

	private static void putFinite(ObjectNode payload, String field, double value) {
		Double finite = finiteOrNull(value);
		if (finite == null) {
			payload.putNull(field);
		} else {
			payload.put(field, finite);
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

	private String formatTimestamp(long timestampMs) {
		return java.time.Instant.ofEpochMilli(timestampMs)
				.atZone(java.time.ZoneId.systemDefault())
				.format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy HH:mm:ss"));
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
			clearTrailingArmed(symbol);
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
		clearTrailingArmed(symbol);
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
		if (!effectiveEnableOrders()) {
			return;
		}
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
		if (!effectiveEnableOrders() && updated == PositionState.NONE && entryStates.get(symbol) != null) {
			exchangePositions.put(symbol, position);
			stateDesyncBySymbol.put(symbol, local != updated);
			StrategyLogV1.PositionSyncLogDto dto = new StrategyLogV1.PositionSyncLogDto(
					symbol,
					formatPositionSide(local),
					position.positionAmt(),
					formatPositionSide(local),
					true);
			LOGGER.info(StrategyLogLineBuilder.buildPositionSyncLine(dto));
			return;
		}
		positionStates.put(symbol, updated);
		boolean desync = local != updated;
		exchangePositions.put(symbol, position);
		stateDesyncBySymbol.put(symbol, desync);
		if (updated == PositionState.NONE) {
			entryStates.remove(symbol);
			clearTrailingArmed(symbol);
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
					clearTrailingArmed(symbol);
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
		//LOGGER.info(StrategyLogLineBuilder.buildPositionSyncLine(dto));
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

	private int scoreLong(int score1m, int score5m, double macdScore) {
		int longScore = (score1m > 0 ? 1 : 0) + (score5m > 0 ? 1 : 0);
		if (macdScore > 0) {
			longScore += 1;
		}
		return longScore;
	}

	private int scoreShort(int score1m, int score5m, double macdScore) {
		int shortScore = (score1m < 0 ? 1 : 0) + (score5m < 0 ? 1 : 0);
		if (macdScore < 0) {
			shortScore += 1;
		}
		return shortScore;
	}

	private VolRsiConfidence resolveVolRsiConfidence(int dirSign, double rsi9_5m, double volume5m,
			double volumeSma10_5m) {
		double volRatio = 1.0;
		if (!Double.isNaN(volumeSma10_5m) && volumeSma10_5m > 0.0 && Double.isFinite(volume5m)) {
			volRatio = volume5m / volumeSma10_5m;
		}
		if (!Double.isFinite(volRatio)) {
			volRatio = 1.0;
		}
		if (dirSign == 0 || Double.isNaN(rsi9_5m)) {
			return new VolRsiConfidence(volRatio, 0.0, 0.0, 0.0, rsi9_5m, volumeSma10_5m);
		}
		double volConf = ScoreMath.clamp((volRatio - 0.8) / 0.6, 0.0, 1.0);
		double rsiConfLong = ScoreMath.clamp((rsi9_5m - 50.0) / 10.0, 0.0, 1.0);
		double rsiConfShort = ScoreMath.clamp((50.0 - rsi9_5m) / 10.0, 0.0, 1.0);
		double rsiConf = dirSign > 0 ? rsiConfLong : dirSign < 0 ? rsiConfShort : 0.0;
		double conf = volConf * rsiConf;
		return new VolRsiConfidence(volRatio, volConf, rsiConf, conf, rsi9_5m, volumeSma10_5m);
	}

	private double resolveRsiVolScore(int dirSign, double conf) {
		return dirSign * conf;
	}

	private double resolveRsiVolExitPressure(double conf) {
		return (1.0 - conf);
	}

	private double resolveAdxScore(double scoreAfterSafety, Double adxValue) {
		if (adxValue == null || adxValue.isNaN()) {
			return 0.0;
		}
		int scoreSign = ScoreMath.sign(scoreAfterSafety);
		if (adxValue > 25.0) {
			return scoreSign * 1.5;
		}
		if (adxValue < 20.0) {
			return -scoreSign * ScoreMath.min(1.5, ScoreMath.abs(scoreAfterSafety));
		}
		return 0.0;
	}

	private CtiDirection resolveEntryDirection(double totalScore) {
		if (totalScore >= 3.0) {
			return CtiDirection.LONG;
		}
		if (totalScore <= -3.0) {
			return CtiDirection.SHORT;
		}
		return CtiDirection.NEUTRAL;
	}

	private EmergencyExitEvaluation evaluateEmergencyExit(
			String symbol,
			PositionState current,
			double close,
			ScoreSignal signal,
			SymbolState state) {

		if (current == null || current == PositionState.NONE || signal == null || state == null) {
			return EmergencyExitEvaluation.empty();
		}

		// ✅ CRITICAL: Don't trigger emergency exit if trailing is active
		if (Boolean.TRUE.equals(trailingArmedBySymbol.get(symbol))) {
			return EmergencyExitEvaluation.empty();
		}

		EntryState entryState = state.entryTimeMs > 0
				? new EntryState(
				current == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
				BigDecimal.ZERO,
				state.entryTimeMs,
				null)
				: null;

		int barsInPosition = resolveBarsInPosition(entryState, signal.closeTime());
		if (barsInPosition < 2) {
			return EmergencyExitEvaluation.empty();
		}

		double pnlPct = resolveProfitPct(
				current == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT,
				BigDecimal.valueOf(state.fiveMinFlipPrice > 0 ? state.fiveMinFlipPrice : close),
				close);

		if (Double.isNaN(pnlPct) || pnlPct >= 0.0) {
			return EmergencyExitEvaluation.empty();
		}

		MacdHistColor histColor = signal.macdHistColor();
		double outHist = signal.outHist();
		double outHistPrev = signal.outHistPrev();
		double rsi9 = state.rsi9_5mValue;
		double rsi9Prev = state.rsi9_5mPrev;

		boolean prevCloseLower = !Double.isNaN(state.prevClose1m) && close < state.prevClose1m;
		boolean prevCloseHigher = !Double.isNaN(state.prevClose1m) && close > state.prevClose1m;
		boolean prevLowLower = !Double.isNaN(state.prevLow1m) && close < state.prevLow1m;
		boolean prevHighHigher = !Double.isNaN(state.prevHigh1m) && close > state.prevHigh1m;
		boolean rsiPrevReady = !Double.isNaN(state.rsi9_5mPrev);

		boolean base = false;
		boolean c1 = false;
		boolean c2 = false;
		boolean c3 = false;

		if (current == PositionState.LONG) {
			base = histColor == MacdHistColor.RED;
			c1 = !Double.isNaN(outHist) && !Double.isNaN(outHistPrev) && outHist < outHistPrev;
			c2 = rsiPrevReady && rsi9 < rsi9Prev;
			c3 = prevCloseLower || prevLowLower || histColor == MacdHistColor.BLUE;
		} else if (current == PositionState.SHORT) {
			base = histColor == MacdHistColor.AQUA;
			c1 = !Double.isNaN(outHist) && !Double.isNaN(outHistPrev) && outHist > outHistPrev;
			c2 = rsiPrevReady && rsi9 > rsi9Prev;
			c3 = prevCloseHigher || prevHighHigher || histColor == MacdHistColor.MAROON;
		}

		boolean confirmed = base && countTrue(c1, c2, c3) >= 2;
		return new EmergencyExitEvaluation(base, c1, c2, c3, confirmed);
	}


	private boolean evaluateReversalConfirm(PositionState current, double close, ScoreSignal signal,
			SymbolState state) {
		if (current == null || current == PositionState.NONE || signal == null || state == null) {
			return false;
		}
		MacdHistColor histColor = signal.macdHistColor();
		double outHist = signal.outHist();
		double outHistPrev = signal.outHistPrev();
		double rsi9 = state.rsi9_5mValue;
		double rsi9Prev = state.rsi9_5mPrev;
		if (current == PositionState.LONG) {
			boolean c1 = !Double.isNaN(outHist) && !Double.isNaN(outHistPrev) && outHist < outHistPrev;
			boolean c2 = !Double.isNaN(rsi9) && !Double.isNaN(rsi9Prev) && rsi9 < rsi9Prev;
			boolean c3 = (!Double.isNaN(state.prevClose1m) && close < state.prevClose1m)
					|| histColor == MacdHistColor.BLUE
					|| histColor == MacdHistColor.RED;
			return countTrue(c1, c2, c3) >= 2;
		}
		if (current == PositionState.SHORT) {
			boolean c1 = !Double.isNaN(outHist) && !Double.isNaN(outHistPrev) && outHist > outHistPrev;
			boolean c2 = !Double.isNaN(rsi9) && !Double.isNaN(rsi9Prev) && rsi9 > rsi9Prev;
			boolean c3 = (!Double.isNaN(state.prevClose1m) && close > state.prevClose1m)
					|| histColor == MacdHistColor.AQUA
					|| histColor == MacdHistColor.MAROON;
			return countTrue(c1, c2, c3) >= 2;
		}
		return false;
	}

	private static boolean isHardExitReason(String reason) {
		if (reason == null) {
			return false;
		}
		return reason.startsWith("EXIT_STOP_LOSS")
				|| reason.startsWith("EXIT_GIVEBACK")
				|| reason.contains("TRAIL")
				|| reason.contains("LOSS_HARD")
				|| reason.contains("LOSS_RECOVERY")
				|| reason.contains("PROFIT_TRAIL")
				|| reason.contains("LIQUID")
				|| reason.contains("MAX_LOSS");
	}

	private static boolean isTopZone(VolRsiConfidence confidence) {
		if (confidence == null || Double.isNaN(confidence.rsi9()) || Double.isNaN(confidence.volRatio())) {
			return false;
		}
		return confidence.rsi9() > 73.0 && confidence.volRatio() < 0.8;
	}

	private static boolean isBottomZone(VolRsiConfidence confidence) {
		if (confidence == null || Double.isNaN(confidence.rsi9()) || Double.isNaN(confidence.volRatio())) {
			return false;
		}
		return confidence.rsi9() < 32.0 && confidence.volRatio() > 2.4;
	}

	private void updateMaxPnlState(SymbolState state, EntryState entryState, double pnlBps) {
		if (state == null) {
			return;
		}
		if (entryState == null || entryState.entryTimeMs() <= 0 || Double.isNaN(pnlBps)) {
			state.maxPnlEntryTimeMs = 0L;
			state.maxPnlBpsSinceEntry = Double.NaN;
			return;
		}
		if (state.maxPnlEntryTimeMs != entryState.entryTimeMs()) {
			state.maxPnlEntryTimeMs = entryState.entryTimeMs();
			state.maxPnlBpsSinceEntry = pnlBps;
			return;
		}
		if (Double.isNaN(state.maxPnlBpsSinceEntry)) {
			state.maxPnlBpsSinceEntry = pnlBps;
			return;
		}
		state.maxPnlBpsSinceEntry = Math.max(state.maxPnlBpsSinceEntry, pnlBps);
	}

	private void updateMacdStreak(SymbolState state, ScoreSignal signal) {
		if (state == null || signal == null) {
			return;
		}
		double outHist = signal.outHist();
		double outHistPrev = signal.outHistPrev();
		if (Double.isNaN(outHist) || Double.isNaN(outHistPrev)) {
			state.macdDownStreak = 0;
			state.macdUpStreak = 0;
			return;
		}
		if (outHist < outHistPrev) {
			state.macdDownStreak += 1;
			state.macdUpStreak = 0;
		} else if (outHist > outHistPrev) {
			state.macdUpStreak += 1;
			state.macdDownStreak = 0;
		} else {
			state.macdDownStreak = 0;
			state.macdUpStreak = 0;
		}
	}

	private boolean resolveMacdStreakConfirmed(PositionState current, SymbolState state) {
		if (state == null || current == null) {
			return false;
		}
		if (current == PositionState.LONG) {
			return state.macdDownStreak >= 2;
		}
		if (current == PositionState.SHORT) {
			return state.macdUpStreak >= 2;
		}
		return false;
	}

	private String resolveEmergencyExitReason(PositionState current) {
		if (current == PositionState.LONG) {
			return "CLOSE_LONG_EMERGENCY";
		}
		if (current == PositionState.SHORT) {
			return "CLOSE_SHORT_EMERGENCY";
		}
		return "CLOSE_EMERGENCY";
	}

	private record VolRsiConfidence(
			double volRatio,
			double volConf,
			double rsiConf,
			double conf,
			double rsi9,
			double volumeSma10) {
	}

	private String resolveDecisionValue(PositionState current, double totalScore, boolean emergencyExit,
			boolean normalExit, EntryDecision entryDecision) {
		if (current == PositionState.NONE) {
			if (entryDecision != null) {
				if (entryDecision.blockReason() != null) {
					return "NO_ENTRY";
				}
				if (entryDecision.confirmedRec() == CtiDirection.LONG) {
					return "ENTER_LONG";
				}
				if (entryDecision.confirmedRec() == CtiDirection.SHORT) {
					return "ENTER_SHORT";
				}
			}
			if (totalScore >= 3.0) {
				return "ENTER_LONG";
			}
			if (totalScore <= -3.0) {
				return "ENTER_SHORT";
			}
			return "NO_ENTRY";
		}
		if (emergencyExit) {
			return current == PositionState.LONG ? "CLOSE_LONG_EMERGENCY" : "CLOSE_SHORT_EMERGENCY";
		}
		if (normalExit) {
			return current == PositionState.LONG ? "CLOSE_LONG" : "CLOSE_SHORT";
		}
		return "HOLD";
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

	public
	// Exposed for TrailingPnlService ROI / trailing logic (intrabar exits).
	CtiLbStrategy.EntryState peekEntryStateForTrailing(String symbol) {
		return entryStates.get(symbol);
	}
	PositionState peekPositionStateForTrailing(String symbol) {
		return positionStates.getOrDefault(symbol, PositionState.NONE);
	}

record EntryState(
			CtiDirection side,
			BigDecimal entryPrice,
			long entryTimeMs,
			BigDecimal quantity) {
	}
}
