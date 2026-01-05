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

	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final WarmupProperties warmupProperties;
	private final SymbolFilterService symbolFilterService;
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
	private static final long POSITION_SYNC_INTERVAL_MS = 60_000L;
	private static final BigDecimal DEFAULT_NOTIONAL_USDT = BigDecimal.valueOf(50);
	private static final long DEFAULT_MIN_HOLD_MS = 30_000L;
	private volatile boolean warmupMode;
	private volatile boolean ordersEnabledOverride = true;

	public CtiLbStrategy(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties,
			WarmupProperties warmupProperties, SymbolFilterService symbolFilterService) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.symbolFilterService = symbolFilterService;
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

	public Mono<Void> refreshAfterWarmup(String symbol) {
		triggerFilterRefresh(symbol);
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

	public void onScoreSignal(String symbol, ScoreSignal signal, double close) {
		if (signal == null) {
			return;
		}
		SignalAction action = SignalAction.HOLD;
		long closeTime = signal.closeTime();
		Long previousCloseTime = lastCloseTimes.put(symbol, closeTime);
		if (previousCloseTime != null && previousCloseTime == closeTime) {
			return;
		}

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

		if (warmupMode) {
			if (shouldLogWarmupDecision(symbol)) {
				logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRec, recUpdate,
						recommendationUsed, recommendationRaw, null, null, null, "WARMUP_MODE", "WARMUP_MODE");
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
		PositionState current = positionStates.getOrDefault(symbol, PositionState.NONE);
		EntryState entryState = resolveEntryState(symbol, current, close, closeTime);
		CtiLbDecisionEngine.ExitDecision exitDecision = CtiLbDecisionEngine.evaluateExit(
				entryState == null ? null : entryState.side(),
				entryState == null ? null : entryState.entryPrice(),
				close,
				strategyProperties.stopLossBps(),
				strategyProperties.takeProfitBps());
		Double estimatedPnlPct = exitDecision.pnlBps() / 100.0;

		if (current != PositionState.NONE && exitDecision.exit()) {
			SignalAction exitAction = SignalAction.HOLD;
			String decisionActionReason = exitDecision.reason();
			String decisionBlockReason = CtiLbDecisionEngine.resolveExitDecisionBlockReason();
			BigDecimal exitQty = resolveExitQuantity(symbol, entryState, close);
			logDecision(symbol, signal, close, exitAction, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, exitQty, entryState, estimatedPnlPct, decisionActionReason, decisionBlockReason);
			if (!effectiveEnableOrders()) {
				return;
			}
			if (exitQty == null || exitQty.signum() <= 0) {
				return;
			}
			orderClient.fetchHedgeModeEnabled()
					.flatMap(hedgeMode -> closePosition(symbol, current, exitQty, hedgeMode)
							.doOnNext(response -> logOrderEvent("EXIT_ORDER", symbol, decisionActionReason,
									current == PositionState.LONG ? "SELL" : "BUY", exitQty, true,
									hedgeMode ? current.name() : "", response, null))
							.doOnError(error -> logOrderEvent("EXIT_ORDER", symbol, decisionActionReason,
									current == PositionState.LONG ? "SELL" : "BUY", exitQty, true,
									hedgeMode ? current.name() : "", null, error.getMessage())))
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
					decisionBlockReason);
			return;
		}

		String minTradeBlockReason = validateMinTrade(symbol, resolvedQty, close);
		if (minTradeBlockReason != null) {
			action = SignalAction.HOLD;
			logDecision(symbol, signal, close, action, confirm1m, confirmedRec, recUpdate, recommendationUsed,
					recommendationRaw, resolvedQty, entryState, estimatedPnlPct, minTradeBlockReason,
					"OK_EXECUTED");
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
					decisionBlockReason);
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
		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> {
					hedgeModeBySymbol.put(symbol, hedgeMode);
					logDecision(symbol, signal, close, actionForLog, confirm1m, confirmedRec, recUpdate,
							recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
							estimatedPnlPct, decisionActionReasonForLog, decisionBlock);
					if (actionForLog == SignalAction.ENTER_LONG || actionForLog == SignalAction.ENTER_SHORT) {
						return openPosition(symbol, targetForLog, resolvedQtyForLog, hedgeMode)
								.doOnNext(response -> logOrderEvent("ENTRY_ORDER", symbol, decisionActionReasonForLog,
										targetForLog == PositionState.LONG ? "BUY" : "SELL", resolvedQtyForLog, false,
										hedgeMode ? targetForLog.name() : "", response, null))
								.doOnError(error -> logOrderEvent("ENTRY_ORDER", symbol, decisionActionReasonForLog,
										targetForLog == PositionState.LONG ? "BUY" : "SELL", resolvedQtyForLog, false,
										hedgeMode ? targetForLog.name() : "", null, error.getMessage()))
								.doOnNext(response -> positionStates.put(symbol, targetForLog))
								.doOnNext(response -> {
									recordEntry(symbol, targetForLog, response, resolvedQtyForLog, closeTime, close);
									flipCount.increment();
									logFlip(symbol, currentForLog, targetForLog, signal, close,
											recommendationUsedForLog, confirmedRec, actionForLog);
								})
								.then();
					}
					return closePosition(symbol, currentForLog, closeQty, hedgeMode)
							.doOnNext(response -> logOrderEvent("FLIP_ORDER", symbol, decisionActionReasonForLog,
									currentForLog == PositionState.LONG ? "SELL" : "BUY", closeQty, true,
									hedgeMode ? currentForLog.name() : "", response, null))
							.doOnError(error -> logOrderEvent("FLIP_ORDER", symbol, decisionActionReasonForLog,
									currentForLog == PositionState.LONG ? "SELL" : "BUY", closeQty, true,
									hedgeMode ? currentForLog.name() : "", null, error.getMessage()))
							.flatMap(response -> openPosition(symbol, targetForLog, resolvedQtyForLog, hedgeMode)
									.doOnNext(openResponse -> logOrderEvent("FLIP_ORDER", symbol,
											decisionActionReasonForLog, targetForLog == PositionState.LONG ? "BUY" : "SELL",
											resolvedQtyForLog, false, hedgeMode ? targetForLog.name() : "", openResponse,
											null))
									.doOnError(error -> logOrderEvent("FLIP_ORDER", symbol, decisionActionReasonForLog,
											targetForLog == PositionState.LONG ? "BUY" : "SELL", resolvedQtyForLog, false,
											hedgeMode ? targetForLog.name() : "", null, error.getMessage())))
							.doOnNext(response -> positionStates.put(symbol, targetForLog))
							.doOnNext(response -> {
								recordEntry(symbol, targetForLog, response, resolvedQtyForLog, closeTime, close);
								recordFlip(symbol, closeTime, BigDecimal.valueOf(close));
								flipCount.increment();
								logFlip(symbol, currentForLog, targetForLog, signal, close,
										recommendationUsedForLog, confirmedRec, actionForLog);
							})
							.then();
				})
				.doOnError(error -> {
					LOGGER.warn("Failed to execute CTI LB action {}: {}", actionForLog, error.getMessage());
					logDecision(symbol, signal, close, SignalAction.HOLD, confirm1m, confirmedRec, recUpdate,
							recommendationUsedForLog, recommendationRawForLog, resolvedQtyForLog, entryState,
							estimatedPnlPct, decisionActionReasonForLog, "ORDER_ERROR");
				})
				.onErrorResume(error -> Mono.empty())
				.subscribe();
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
						return openPosition(symbol, target, quantity, hedgeMode);
					}
					return closePosition(symbol, current, quantity, hedgeMode)
							.flatMap(response -> openPosition(symbol, target, quantity, hedgeMode));
				})
				.doOnNext(response -> positionStates.put(symbol, target))
				.then();
	}

	private Mono<OrderResponse> closePosition(String symbol, PositionState current, BigDecimal quantity, boolean hedgeMode) {
		if (current == PositionState.NONE) {
			return Mono.empty();
		}
		String side = current == PositionState.LONG ? "SELL" : "BUY";
		String positionSide = hedgeMode ? current.name() : "";
		return orderClient.placeReduceOnlyMarketOrder(symbol, side, quantity, positionSide)
				.retryWhen(Retry.backoff(2, Duration.ofMillis(200))
						.filter(error -> !(error instanceof IllegalArgumentException)))
				.filter(CtiLbDecisionEngine::shouldProceedAfterClose)
				.switchIfEmpty(Mono.error(new IllegalStateException("Close order rejected")));
	}

	private Mono<OrderResponse> openPosition(String symbol, PositionState target, BigDecimal quantity, boolean hedgeMode) {
		String side = target == PositionState.LONG ? "BUY" : "SELL";
		String positionSide = hedgeMode ? target.name() : "";
		return orderClient.placeMarketOrder(symbol, side, quantity, positionSide);
	}

	private Mono<OrderResponse> openPosition(String symbol, PositionState target, BigDecimal quantity) {
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> openPosition(symbol, target, quantity, hedgeMode));
	}

	BigDecimal resolveQuantity(String symbol, double close) {
		BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
		if (filters == null) {
			triggerFilterRefresh(symbol);
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

	private void logDecision(String symbol, ScoreSignal signal, double close, SignalAction action,
			int confirm1m, CtiDirection confirmedRec, RecStreakTracker.RecUpdate recUpdate,
			CtiDirection recommendationUsed, CtiDirection recommendationRaw, BigDecimal resolvedQty,
			EntryState entryState, Double estimatedPnlPct, String decisionActionReason, String decisionBlockReason) {
		logSummaryIfNeeded(signal.closeTime());
		String decisionAction = recUpdate.missedMove() ? "RESET_PENDING" : resolveDecisionAction(action);
		String insufficientReason = resolveInsufficientReason(signal);
		BinanceFuturesOrderClient.ExchangePosition exchangePosition = exchangePositions.get(symbol);
		Boolean desync = stateDesyncBySymbol.get(symbol);
		BigDecimal entryPrice = entryState == null ? null : entryState.entryPrice();
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
				exchangePosition == null ? "NA" : exchangePosition.positionSide(),
				exchangePosition == null ? null : exchangePosition.positionAmt(),
				desync,
				decisionBlockReason,
				recommendationRaw,
				confirm1m,
				formatPositionSide(positionStates.getOrDefault(symbol, PositionState.NONE)),
				null,
				0,
				missedMoveCount.longValue(),
				confirmHitCount.longValue(),
				flipCount.longValue());
		LOGGER.info(StrategyLogLineBuilder.buildDecisionLine(dto));
	}

	private String resolveHoldReason(ScoreSignal signal, boolean hasSignal, boolean confirmationMet) {
		if (signal.recReason() == CtiScoreCalculator.RecReason.TIE_HOLD) {
			return "TIE_HOLD";
		}
		return CtiLbDecisionEngine.resolveHoldReason(hasSignal, confirmationMet);
	}

	private String validateMinTrade(String symbol, BigDecimal quantity, double price) {
		if (quantity == null || quantity.signum() <= 0) {
			BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
			if (filters == null) {
				triggerFilterRefresh(symbol);
				return "WAIT_FILTERS";
			}
			return "QTY_ZERO_AFTER_STEP";
		}
		BinanceFuturesOrderClient.SymbolFilters filters = symbolFilterService.getFilters(symbol);
		if (filters == null) {
			triggerFilterRefresh(symbol);
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
			boolean reduceOnly, String positionSide, OrderResponse response, String error) {
		String orderId = response == null || response.orderId() == null ? "NA" : response.orderId().toString();
		String status = response == null ? "NA" : response.status();
		String errorValue = error == null ? "NA" : error;
		LOGGER.info("EVENT={} symbol={} reason={} side={} qty={} reduceOnly={} positionSide={} orderId={} status={} error={}",
				event,
				symbol,
				reason,
				side,
				qty == null ? "NA" : qty.stripTrailingZeros().toPlainString(),
				reduceOnly,
				positionSide == null || positionSide.isBlank() ? "NA" : positionSide,
				orderId,
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

	private void triggerFilterRefresh(String symbol) {
		symbolFilterService.refreshFilters(symbol)
				.subscribe();
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
			CtiDirection rec, CtiDirection confirmedRec, SignalAction action) {
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
				"NONE",
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
		} else if (position.entryPrice() != null && position.entryPrice().signum() > 0) {
			CtiDirection side = updated == PositionState.LONG ? CtiDirection.LONG : CtiDirection.SHORT;
			BigDecimal qty = position.positionAmt() == null ? null : position.positionAmt().abs();
			EntryState existing = entryStates.get(symbol);
			if (existing == null) {
				entryStates.put(symbol, new EntryState(side, position.entryPrice(), closeTime, qty));
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

	private enum PositionState {
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
