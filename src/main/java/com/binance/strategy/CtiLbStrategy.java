package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.strategy.StrategyLogV1.ConfirmHitLogDto;
import com.binance.strategy.StrategyLogV1.DecisionLogDto;
import com.binance.strategy.StrategyLogV1.FlipLogDto;
import com.binance.strategy.StrategyLogV1.MissedMoveLogDto;
import com.binance.strategy.StrategyLogV1.SummaryLogDto;

import reactor.core.publisher.Mono;

@Component
public class CtiLbStrategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(CtiLbStrategy.class);

	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final Map<String, Long> lastCloseTimes = new ConcurrentHashMap<>();
	private final Map<String, PositionState> positionStates = new ConcurrentHashMap<>();
	private final Map<String, RecStreakTracker> recTrackers = new ConcurrentHashMap<>();
	private final LongAdder missedMoveCount = new LongAdder();
	private final LongAdder flipCount = new LongAdder();
	private final LongAdder confirmHitCount = new LongAdder();
	private final Map<String, LongAdder> missedBySymbol = new ConcurrentHashMap<>();
	private final java.util.concurrent.atomic.AtomicLong lastSummaryAtMs = new java.util.concurrent.atomic.AtomicLong();

	public CtiLbStrategy(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
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

		CtiDirection recommendation = signal.insufficientData() ? CtiDirection.NEUTRAL : signal.recommendation();
		RecStreakTracker tracker = recTrackers.computeIfAbsent(symbol, ignored -> new RecStreakTracker());
		RecStreakTracker.RecUpdate recUpdate = tracker.update(
				recommendation,
				signal.closeTime(),
				BigDecimal.valueOf(close),
				strategyProperties.confirmBars());
		int confirm1m = recUpdate.streakCount();
		CtiDirection confirmedRec = confirm1m >= strategyProperties.confirmBars()
				? recUpdate.lastRec()
				: CtiDirection.NEUTRAL;

		if (recUpdate.missedMove()) {
			missedMoveCount.increment();
			missedBySymbol.computeIfAbsent(symbol, ignored -> new LongAdder()).increment();
			logMissedMove(symbol, recUpdate, signal, close);
		}
		if (recUpdate.confirmHit()) {
			confirmHitCount.increment();
			logConfirmHit(symbol, confirmedRec, recUpdate, signal, close);
		}

		PositionState current = positionStates.getOrDefault(symbol, PositionState.NONE);
		action = resolveAction(current, confirmedRec);
		logDecision(symbol, signal, close, action, confirm1m, confirmedRec, recUpdate);

		if (action == SignalAction.HOLD) {
			return;
		}

		PositionState target = confirmedRec == CtiDirection.LONG ? PositionState.LONG : PositionState.SHORT;
		if (action != SignalAction.HOLD) {
			flipCount.increment();
			logFlip(symbol, current, target, signal, close, recommendation, confirmedRec, action);
		}

		if (!strategyProperties.enableOrders()) {
			return;
		}

		Mono<Void> execution = action == SignalAction.ENTER_LONG || action == SignalAction.ENTER_SHORT
				? openPosition(symbol, target, resolveQuantity(close))
						.doOnNext(response -> positionStates.put(symbol, target))
						.then()
				: executeFlip(symbol, target, close);

		execution.doOnError(error -> LOGGER.warn("Failed to execute CTI LB action {}: {}", action, error.getMessage()))
				.onErrorResume(error -> Mono.empty())
				.subscribe();
	}

	private Mono<Void> executeFlip(String symbol, PositionState target, double close) {
		PositionState current = positionStates.getOrDefault(symbol, PositionState.NONE);
		BigDecimal quantity = resolveQuantity(close);
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> closeIfNeeded(symbol, current, quantity, hedgeMode)
						.then(openPosition(symbol, target, quantity, hedgeMode)))
				.doOnNext(response -> positionStates.put(symbol, target))
				.then();
	}

	private Mono<Void> closeIfNeeded(String symbol, PositionState current, BigDecimal quantity, boolean hedgeMode) {
		if (current == PositionState.NONE) {
			return Mono.empty();
		}
		String side = current == PositionState.LONG ? "SELL" : "BUY";
		String positionSide = hedgeMode ? current.name() : "";
		return orderClient.placeReduceOnlyMarketOrder(symbol, side, quantity, positionSide)
				.then();
	}

	private Mono<?> openPosition(String symbol, PositionState target, BigDecimal quantity, boolean hedgeMode) {
		String side = target == PositionState.LONG ? "BUY" : "SELL";
		String positionSide = hedgeMode ? target.name() : "";
		return orderClient.placeMarketOrder(symbol, side, quantity, positionSide);
	}

	private Mono<?> openPosition(String symbol, PositionState target, BigDecimal quantity) {
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> openPosition(symbol, target, quantity, hedgeMode));
	}

	BigDecimal resolveQuantity(double close) {
		BigDecimal notional = strategyProperties.positionNotionalUsdt();
		if (notional != null && notional.signum() > 0 && close > 0) {
			BigDecimal price = BigDecimal.valueOf(close);
			if (strategyProperties.maxPositionUsdt() != null
					&& notional.compareTo(strategyProperties.maxPositionUsdt()) > 0) {
				notional = strategyProperties.maxPositionUsdt();
			}
			BigDecimal quantity = notional.divide(price, MathContext.DECIMAL64);
			return roundDownToStep(quantity, strategyProperties.quantityStep());
		}
		return roundDownToStep(strategyProperties.marketQuantity(), strategyProperties.quantityStep());
	}

	private BigDecimal roundDownToStep(BigDecimal value, BigDecimal step) {
		if (value == null || step == null || step.signum() <= 0) {
			return value;
		}
		BigDecimal ratio = value.divide(step, 0, RoundingMode.DOWN);
		return ratio.multiply(step, MathContext.DECIMAL64);
	}

	private void logDecision(String symbol, ScoreSignal signal, double close, SignalAction action,
			int confirm1m, CtiDirection confirmedRec, RecStreakTracker.RecUpdate recUpdate) {
		logSummaryIfNeeded(signal.closeTime());
		String decisionAction = resolveDecisionAction(action);
		DecisionLogDto dto = new DecisionLogDto(
				symbol,
				signal.closeTime(),
				close,
				signal.cti1mValue(),
				signal.cti5mValue(),
				signal.adx5m(),
				signal.adxBonus() == 1,
				scoreLong(signal.score1m(), signal.score5m(), signal.adxBonus(), signal.hamCtiScore()),
				scoreShort(signal.score1m(), signal.score5m(), signal.adxBonus(), signal.hamCtiScore()),
				signal.adjustedScore(),
				resolveBias(signal.hamCtiScore()),
				signal.recommendation(),
				confirm1m,
				strategyProperties.confirmBars(),
				signal.recommendation(),
				recUpdate.recPending(),
				recUpdate.recFirstSeenAtMs(),
				recUpdate.recFirstSeenPrice(),
				decisionAction,
				formatPositionSide(positionStates.getOrDefault(symbol, PositionState.NONE)),
				BigDecimal.ZERO,
				0,
				missedMoveCount.longValue(),
				confirmHitCount.longValue(),
				flipCount.longValue());
		LOGGER.info(StrategyLogLineBuilder.buildDecisionLine(dto));
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
		MissedMoveLogDto dto = new MissedMoveLogDto(
				symbol,
				recUpdate.missedPending(),
				recUpdate.missedFirstSeenAtMs(),
				recUpdate.missedFirstSeenPrice(),
				signal.closeTime(),
				BigDecimal.valueOf(nowPrice),
				recUpdate.streakBeforeReset(),
				strategyProperties.confirmBars(),
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
		ConfirmHitLogDto dto = new ConfirmHitLogDto(
				symbol,
				confirmedRec,
				recUpdate.confirmFirstSeenAtMs(),
				recUpdate.confirmFirstSeenPrice(),
				signal.closeTime(),
				BigDecimal.valueOf(nowPrice),
				recUpdate.streakCount(),
				strategyProperties.confirmBars(),
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
				resolveDecisionAction(action),
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

	private CtiDirection resolveBias(int hamScore) {
		if (hamScore > 0) {
			return CtiDirection.LONG;
		}
		if (hamScore < 0) {
			return CtiDirection.SHORT;
		}
		return CtiDirection.NEUTRAL;
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

	private enum PositionState {
		LONG,
		SHORT,
		NONE
	}
}
