package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.exchange.dto.OrderResponse;
import com.binance.market.BinanceMarketClient;
import com.binance.market.dto.BookTickerResponse;
import com.binance.market.dto.DepthUpdateEvent;
import com.binance.market.dto.OrderBookDepthResponse;
import com.binance.market.dto.TradeEvent;
import com.binance.strategy.LocalOrderBook.DepthDelta;
import com.binance.strategy.LocalOrderBook.OrderBookView;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class EtcEthDepthStrategyWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(EtcEthDepthStrategyWatcher.class);
	private static final BigDecimal EPSILON = new BigDecimal("0.00000001");
	private static final int SNAPSHOT_LIMIT = 1000;

	private final BinanceMarketClient marketClient;
	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

	private final LocalOrderBook orderBook = new LocalOrderBook();
	private final RollingSum addedVol;
	private final RollingSum removedVol;
	private final RollingSum buyTradeVol;
	private final RollingSum sellTradeVol;
	private final Ewma obiEwma;
	private final Ewma toiEwma;
	private final Ewma cancelRatioEwma;
	private final MidPriceHistory midHistory = new MidPriceHistory();

	private final Object depthLock = new Object();
	private final List<DepthUpdateEvent> depthBuffer = new ArrayList<>();
	private final AtomicBoolean depthSynced = new AtomicBoolean(false);
	private final AtomicBoolean snapshotInFlight = new AtomicBoolean(false);
	private final AtomicLong firstBufferedUpdateId = new AtomicLong(-1);
	private final AtomicInteger resyncCount = new AtomicInteger(0);

	private final AtomicReference<Direction> positionState = new AtomicReference<>(Direction.FLAT);
	private final AtomicReference<Direction> desiredDirection = new AtomicReference<>(Direction.NONE);
	private final AtomicReference<Direction> lastCandidate = new AtomicReference<>(Direction.NONE);
	private final AtomicLong candidateSince = new AtomicLong(0);
	private final AtomicLong lastTradeTimestamp = new AtomicLong(0);
	private final AtomicReference<BigDecimal> entryPrice = new AtomicReference<>();
	private final AtomicLong entryTimestamp = new AtomicLong(0);
	private final AtomicReference<BigDecimal> entryQty = new AtomicReference<>();
	private final AtomicReference<BigDecimal> latestSpreadBps = new AtomicReference<>();
	private final AtomicReference<BigDecimal> latestFuturesMid = new AtomicReference<>();
	private final AtomicInteger consecutiveLosses = new AtomicInteger(0);
	private final AtomicReference<BigDecimal> dailyLoss = new AtomicReference<>(BigDecimal.ZERO);
	private final AtomicReference<LocalDate> lossDay = new AtomicReference<>(LocalDate.now());
	private final AtomicBoolean tradingLock = new AtomicBoolean(false);
	private final AtomicLong lastFlipTimestamp = new AtomicLong(0);
	private final Object flipLock = new Object();
	private final Deque<Long> flipHistory = new ArrayDeque<>();

	public EtcEthDepthStrategyWatcher(BinanceMarketClient marketClient,
			BinanceFuturesOrderClient orderClient,
			StrategyProperties strategyProperties,
			ObjectMapper objectMapper) {
		this.marketClient = marketClient;
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
		this.objectMapper = objectMapper;
		this.addedVol = new RollingSum(strategyProperties.rollingWindowMs());
		this.removedVol = new RollingSum(strategyProperties.rollingWindowMs());
		this.buyTradeVol = new RollingSum(strategyProperties.rollingWindowMs());
		this.sellTradeVol = new RollingSum(strategyProperties.rollingWindowMs());
		this.obiEwma = new Ewma(new BigDecimal("0.3"));
		this.toiEwma = new Ewma(new BigDecimal("0.3"));
		this.cancelRatioEwma = new Ewma(new BigDecimal("0.3"));
	}

	@PostConstruct
	public void startStreams() {
		startDepthStream();
		startTradeStream();
	}

	@Scheduled(fixedDelayString = "${strategy.tick-interval-ms:200}")
	public void computeSignals() {
		if (!depthSynced.get()) {
			return;
		}

		long now = System.currentTimeMillis();
		OrderBookView view = orderBook.view(strategyProperties.depthLevels());
		if (view.bestBid() == null || view.bestAsk() == null) {
			return;
		}

		BigDecimal mid = view.bestBid().add(view.bestAsk()).divide(new BigDecimal("2"), MathContext.DECIMAL64);
		midHistory.add(mid, now);
		BigDecimal midPast = midHistory.valueAtOrBefore(now - 500);
		BigDecimal midReturn = midPast == null ? BigDecimal.ZERO
				: mid.subtract(midPast).divide(midPast, MathContext.DECIMAL64);

		BigDecimal obi = computeWeightedImbalance(view);
		BigDecimal toi = computeTradeImbalance(now);
		BigDecimal cancelRatio = computeCancelRatio(now);

		BigDecimal obiValue = obiEwma.update(obi);
		BigDecimal toiValue = toiEwma.update(toi);
		BigDecimal cancelValue = cancelRatioEwma.update(cancelRatio);

		Direction candidate = evaluateCandidate(obiValue, toiValue, cancelValue, midReturn);
		updateDesiredDirection(candidate, now);

		FlipDecision flipDecision = evaluateFlipDecision(now, obiValue, toiValue, cancelValue);

		LOGGER.info(
				"ETC tick: obi={}, toi={}, cancelRatio={}, midReturn={}, candidate={}, desired={}, position={}, spreadBps={}, depthSynced={}, resyncCount={}, canFlip={}, flipReason={}, lastFlipTs={}, flipsInLast5Min={}",
				obiValue,
				toiValue,
				cancelValue,
				midReturn,
				candidate,
				desiredDirection.get(),
				positionState.get(),
				latestSpreadBps.get(),
				depthSynced.get(),
				resyncCount.get(),
				flipDecision.canFlip(),
				flipDecision.reason(),
				lastFlipTimestamp.get(),
				flipDecision.flipsInLast5Min());

		Direction current = positionState.get();
		if (flipDecision.shouldFlip()) {
			flipPosition(current, flipDecision.flipDirection(), now);
			return;
		}
		if (current == Direction.LONG && shouldExitLong(obiValue, toiValue, cancelValue)) {
			closePosition(Direction.LONG);
			return;
		}
		if (current == Direction.SHORT && shouldExitShort(obiValue, toiValue, cancelValue)) {
			closePosition(Direction.SHORT);
			return;
		}
		if (current == Direction.FLAT) {
			openIfReady(now);
		}
	}

	@Scheduled(fixedDelayString = "${strategy.poll-interval-ms:2000}")
	public void refreshFuturesSpread() {
		marketClient.fetchFuturesBookTicker(strategyProperties.tradeSymbol())
				.doOnNext(this::updateSpread)
				.doOnError(error -> LOGGER.warn("Failed to fetch futures book ticker", error))
				.subscribe();
	}

	private void updateSpread(BookTickerResponse ticker) {
		if (ticker == null || ticker.bidPrice() == null || ticker.askPrice() == null) {
			return;
		}
		BigDecimal mid = ticker.bidPrice().add(ticker.askPrice()).divide(new BigDecimal("2"), MathContext.DECIMAL64);
		BigDecimal spread = ticker.askPrice().subtract(ticker.bidPrice());
		BigDecimal spreadBps = spread.divide(mid, MathContext.DECIMAL64).multiply(new BigDecimal("10000"));
		latestSpreadBps.set(spreadBps);
		latestFuturesMid.set(mid);
	}

	private void startDepthStream() {
		String symbol = strategyProperties.referenceSymbol().toLowerCase();
		URI uri = URI.create("wss://stream.binance.com:9443/ws/" + symbol + "@depth@100ms");
		webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(this::handleDepthMessage)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe();
	}

	private void startTradeStream() {
		String symbol = strategyProperties.referenceSymbol().toLowerCase();
		URI uri = URI.create("wss://stream.binance.com:9443/ws/" + symbol + "@trade");
		webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(this::handleTradeMessage)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe();
	}

	private void handleDepthMessage(String payload) {
		try {
			DepthUpdateEvent event = objectMapper.readValue(payload, DepthUpdateEvent.class);
			handleDepthUpdate(event);
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse depth message", ex);
		}
	}

	private void handleTradeMessage(String payload) {
		try {
			TradeEvent event = objectMapper.readValue(payload, TradeEvent.class);
			handleTradeEvent(event);
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse trade message", ex);
		}
	}

	private void handleDepthUpdate(DepthUpdateEvent event) {
		synchronized (depthLock) {
			if (!depthSynced.get()) {
				bufferDepthEvent(event);
				return;
			}
			long expected = orderBook.lastUpdateId() + 1;
			if (event.finalUpdateId() < expected) {
				return;
			}
			if (event.firstUpdateId() > expected) {
				resyncOrderBook("Depth gap: expected " + expected + " got U=" + event.firstUpdateId());
				bufferDepthEvent(event);
				return;
			}
			if (event.firstUpdateId() <= expected && event.finalUpdateId() >= expected) {
				DepthDelta delta = orderBook.applyDepthUpdate(event);
				recordDepthDelta(delta, event.eventTime());
			}
		}
	}

	private void bufferDepthEvent(DepthUpdateEvent event) {
		depthBuffer.add(event);
		if (firstBufferedUpdateId.get() == -1) {
			firstBufferedUpdateId.set(event.firstUpdateId());
		}
		if (snapshotInFlight.compareAndSet(false, true)) {
			fetchSnapshot();
		}
	}

	private void fetchSnapshot() {
		marketClient.fetchSpotOrderBookDepth(strategyProperties.referenceSymbol(), SNAPSHOT_LIMIT)
				.doOnNext(this::applySnapshot)
				.doOnError(error -> {
					LOGGER.warn("Snapshot fetch failed", error);
					snapshotInFlight.set(false);
				})
				.subscribe();
	}

	private void applySnapshot(OrderBookDepthResponse snapshot) {
		synchronized (depthLock) {
			if (snapshot.lastUpdateId() < firstBufferedUpdateId.get()) {
				snapshotInFlight.set(false);
				fetchSnapshot();
				return;
			}

			orderBook.applySnapshot(snapshot);
			long lastUpdateId = snapshot.lastUpdateId();
			List<DepthUpdateEvent> toProcess = depthBuffer.stream()
					.filter(event -> event.finalUpdateId() > lastUpdateId)
					.toList();
			depthBuffer.clear();

			boolean processed = false;
			for (DepthUpdateEvent event : toProcess) {
				if (!processed) {
					if (event.firstUpdateId() <= lastUpdateId + 1 && event.finalUpdateId() >= lastUpdateId + 1) {
						processed = true;
					} else {
						continue;
					}
				}
				long expected = orderBook.lastUpdateId() + 1;
				if (event.finalUpdateId() < expected) {
					continue;
				}
				if (event.firstUpdateId() > expected) {
					resyncOrderBook("Snapshot sync gap: expected " + expected + " got U=" + event.firstUpdateId());
					bufferDepthEvent(event);
					snapshotInFlight.set(false);
					return;
				}
				if (event.firstUpdateId() <= expected && event.finalUpdateId() >= expected) {
					DepthDelta delta = orderBook.applyDepthUpdate(event);
					recordDepthDelta(delta, event.eventTime());
				}
			}

			depthSynced.set(true);
			snapshotInFlight.set(false);
		}
	}

	private void recordDepthDelta(DepthDelta delta, long eventTime) {
		long now = System.currentTimeMillis();
		addedVol.add(delta.added(), now);
		removedVol.add(delta.removed(), now);
		long latency = now - eventTime;
		LOGGER.debug("Depth latency: {} ms", latency);
	}

	private void handleTradeEvent(TradeEvent event) {
		long now = System.currentTimeMillis();
		BigDecimal notional = event.price().multiply(event.quantity());
		if (event.buyerMaker()) {
			sellTradeVol.add(notional, now);
		} else {
			buyTradeVol.add(notional, now);
		}
		long latency = now - event.eventTime();
		LOGGER.debug("Trade latency: {} ms", latency);
	}

	private BigDecimal computeWeightedImbalance(OrderBookView view) {
		BigDecimal bidWeighted = BigDecimal.ZERO;
		BigDecimal askWeighted = BigDecimal.ZERO;
		for (int i = 0; i < strategyProperties.depthLevels(); i++) {
			BigDecimal weight = weightForLevel(i);
			if (i < view.bidQtys().size()) {
				bidWeighted = bidWeighted.add(weight.multiply(view.bidQtys().get(i)));
			}
			if (i < view.askQtys().size()) {
				askWeighted = askWeighted.add(weight.multiply(view.askQtys().get(i)));
			}
		}
		BigDecimal numerator = bidWeighted.subtract(askWeighted);
		BigDecimal denominator = bidWeighted.add(askWeighted).add(EPSILON);
		return numerator.divide(denominator, MathContext.DECIMAL64);
	}

	private BigDecimal weightForLevel(int level) {
		double exponent = -strategyProperties.weightLambda().doubleValue() * level;
		return BigDecimal.valueOf(Math.exp(exponent));
	}

	private BigDecimal computeTradeImbalance(long now) {
		BigDecimal buy = buyTradeVol.total(now);
		BigDecimal sell = sellTradeVol.total(now);
		BigDecimal numerator = buy.subtract(sell);
		BigDecimal denominator = buy.add(sell).add(EPSILON);
		return numerator.divide(denominator, MathContext.DECIMAL64);
	}

	private BigDecimal computeCancelRatio(long now) {
		BigDecimal added = addedVol.total(now);
		BigDecimal removed = removedVol.total(now);
		return removed.divide(added.add(removed).add(EPSILON), MathContext.DECIMAL64);
	}

	private Direction evaluateCandidate(BigDecimal obi, BigDecimal toi, BigDecimal cancelRatio, BigDecimal midReturn) {
		if (obi == null || toi == null || cancelRatio == null) {
			return Direction.NONE;
		}
		BigDecimal obiThreshold = strategyProperties.obiEntry().multiply(new BigDecimal("0.90"));
		BigDecimal toiThreshold = strategyProperties.toiMin().multiply(new BigDecimal("0.85"));
		BigDecimal cancelMax = strategyProperties.cancelMax();
		boolean obiOkLong = obi.compareTo(obiThreshold) > 0;
		boolean toiOkLong = toi.compareTo(toiThreshold) > 0;
		boolean obiOkShort = obi.compareTo(obiThreshold.negate()) < 0;
		boolean toiOkShort = toi.compareTo(toiThreshold.negate()) < 0;
		boolean cancelOk = cancelRatio.compareTo(cancelMax) < 0;
		boolean longCandidate = obiOkLong && toiOkLong && cancelOk;
		boolean shortCandidate = obiOkShort && toiOkShort && cancelOk;
		if (longCandidate) {
			return Direction.LONG;
		}
		if (shortCandidate) {
			return Direction.SHORT;
		}
		if (!obiOkLong && !obiOkShort) {
			LOGGER.info("Gate fail: OBI value={}, threshold={}", obi, obiThreshold);
		}
		if (!toiOkLong && !toiOkShort) {
			LOGGER.info("Gate fail: TOI value={}, threshold={}", toi, toiThreshold);
		}
		if (!cancelOk) {
			LOGGER.info("Gate fail: CANCEL value={}, max={}", cancelRatio, cancelMax);
		}
		return Direction.NONE;
	}

	private void updateDesiredDirection(Direction candidate, long now) {
		if (candidate == Direction.NONE) {
			lastCandidate.set(Direction.NONE);
			desiredDirection.set(Direction.NONE);
			candidateSince.set(0);
			return;
		}
		if (candidate == lastCandidate.get()) {
			if (now - candidateSince.get() >= strategyProperties.persistMs()) {
				Direction previous = desiredDirection.getAndSet(candidate);
				if (previous != candidate) {
					LOGGER.info("{} {}", strategyProperties.referenceSymbol(),
							candidate == Direction.LONG ? "BUY" : "SELL");
				}
			}
		} else {
			lastCandidate.set(candidate);
			candidateSince.set(now);
		}
	}

	private boolean shouldExitLong(BigDecimal obi, BigDecimal toi, BigDecimal cancelRatio) {
		return obi.compareTo(strategyProperties.obiExit()) < 0
				|| toi.signum() < 0
				|| cancelRatio.compareTo(strategyProperties.cancelMax()) >= 0;
	}

	private boolean shouldExitShort(BigDecimal obi, BigDecimal toi, BigDecimal cancelRatio) {
		return obi.compareTo(strategyProperties.obiExit().negate()) > 0
				|| toi.signum() > 0
				|| cancelRatio.compareTo(strategyProperties.cancelMax()) >= 0;
	}

	private void openIfReady(long now) {
		int effectiveCooldownMs = Math.max(0, (int) Math.round(strategyProperties.cooldownMs() * 0.7));
		if (now - lastTradeTimestamp.get() < effectiveCooldownMs) {
			return;
		}
		if (!riskAllowed()) {
			return;
		}
		Direction desired = desiredDirection.get();
		if (desired == Direction.NONE) {
			return;
		}
		BigDecimal spreadBps = latestSpreadBps.get();
		if (spreadBps != null
				&& spreadBps.compareTo(strategyProperties.maxSpreadBps().multiply(new BigDecimal("1.5"))) > 0) {
			LOGGER.info("Spread too wide ({} bps). Skipping entry.", spreadBps);
			return;
		}
		openPosition(desired);
	}

	private void openPosition(Direction direction) {
		if (!tradingLock.compareAndSet(false, true)) {
			return;
		}
		if (!strategyProperties.enableOrders()) {
			LOGGER.warn("[TESTNET] Order placement disabled. Set strategy.enable-orders=true to send orders.");
			tradingLock.set(false);
			return;
		}

		BigDecimal quantity = calculatePositionQuantity();
		String side = direction == Direction.LONG ? "BUY" : "SELL";
		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> orderClient.placeMarketOrder(
						strategyProperties.tradeSymbol(),
						side,
						quantity,
						hedgeMode ? direction.name() : "")
						.flatMap(response -> placeProtectionOrders(response, direction, quantity, hedgeMode)))
				.doOnNext(response -> {
					entryPrice.set(response.entryOrder().avgPrice());
					entryTimestamp.set(System.currentTimeMillis());
					entryQty.set(quantity);
					positionState.set(direction);
					lastTradeTimestamp.set(System.currentTimeMillis());
					LOGGER.info("Opened {} position on {} at {}", direction, strategyProperties.tradeSymbol(),
							response.entryOrder().avgPrice());
				})
				.doOnError(error -> LOGGER.warn("Failed to open position", error))
				.doFinally(signal -> tradingLock.set(false))
				.subscribe();
	}

	private Mono<ProtectionOrders> placeProtectionOrders(OrderResponse response, Direction direction, BigDecimal quantity,
			boolean hedgeMode) {
		BigDecimal entry = response.avgPrice();
		if (entry == null) {
			return Mono.just(new ProtectionOrders(response, null, null));
		}
		BigDecimal stopPrice = stopPrice(entry, direction);
		BigDecimal takeProfit = takeProfitPrice(entry, direction);
		return orderClient.placeStopMarketOrder(strategyProperties.tradeSymbol(),
				direction == Direction.LONG ? "SELL" : "BUY",
				quantity,
				stopPrice,
				true,
				hedgeMode ? direction.name() : "")
				.flatMap(stopOrder -> orderClient.placeTakeProfitMarketOrder(strategyProperties.tradeSymbol(),
						direction == Direction.LONG ? "SELL" : "BUY",
						quantity,
						takeProfit,
						true,
						hedgeMode ? direction.name() : "")
						.map(tpOrder -> new ProtectionOrders(response, stopOrder, tpOrder)));
	}

	private void closePosition(Direction direction) {
		if (!tradingLock.compareAndSet(false, true)) {
			return;
		}
		if (!strategyProperties.enableOrders()) {
			LOGGER.warn("[TESTNET] Order placement disabled. Set strategy.enable-orders=true to send orders.");
			tradingLock.set(false);
			return;
		}
		String side = direction == Direction.LONG ? "SELL" : "BUY";
		BigDecimal quantity = entryQty.get() != null ? entryQty.get() : calculatePositionQuantity();
		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> orderClient.placeReduceOnlyMarketOrder(strategyProperties.tradeSymbol(),
						side,
						quantity,
						hedgeMode ? direction.name() : "")
						.flatMap(response -> orderClient.cancelAllOpenOrders(strategyProperties.tradeSymbol())
								.thenReturn(response)))
				.doOnNext(response -> {
					positionState.set(Direction.FLAT);
					entryTimestamp.set(0);
					entryQty.set(null);
					lastTradeTimestamp.set(System.currentTimeMillis());
					updateLossTracking(response, direction);
					LOGGER.info("Closed {} position on {}", direction, strategyProperties.tradeSymbol());
				})
				.doOnError(error -> LOGGER.warn("Failed to close position", error))
				.doFinally(signal -> tradingLock.set(false))
				.subscribe();
	}

	private void flipPosition(Direction from, Direction to, long now) {
		if (!tradingLock.compareAndSet(false, true)) {
			return;
		}
		if (!strategyProperties.enableOrders()) {
			LOGGER.warn("[TESTNET] Order placement disabled. Set strategy.enable-orders=true to send orders.");
			tradingLock.set(false);
			return;
		}
		String closeSide = from == Direction.LONG ? "SELL" : "BUY";
		String openSide = to == Direction.LONG ? "BUY" : "SELL";
		BigDecimal quantity = entryQty.get() != null ? entryQty.get() : calculatePositionQuantity();
		orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> orderClient.placeReduceOnlyMarketOrder(strategyProperties.tradeSymbol(),
						closeSide,
						quantity,
						hedgeMode ? from.name() : "")
						.flatMap(closeResponse -> orderClient.cancelAllOpenOrders(strategyProperties.tradeSymbol())
								.thenReturn(new CloseContext(closeResponse, hedgeMode))))
				.retryWhen(Retry.backoff(3, Duration.ofMillis(200)))
				.flatMap(context -> orderClient.placeMarketOrder(
						strategyProperties.tradeSymbol(),
						openSide,
						quantity,
						context.hedgeMode() ? to.name() : "")
						.flatMap(openResponse -> placeProtectionOrders(openResponse, to, quantity, context.hedgeMode())
								.map(protection -> new FlipOrders(context.closeOrder(), protection))))
				.doOnNext(flipOrders -> {
					ProtectionOrders protection = flipOrders.protectionOrders();
					updateLossTracking(flipOrders.closeOrder(), from);
					entryPrice.set(protection.entryOrder().avgPrice());
					entryTimestamp.set(now);
					entryQty.set(quantity);
					positionState.set(to);
					lastTradeTimestamp.set(now);
					lastFlipTimestamp.set(now);
					recordFlip(now);
					LOGGER.info("Flip executed: oldPos={}, newPos={}, closeOrderId={}, openOrderId={}, slOrderId={}, tpOrderId={}",
							from,
							to,
							flipOrders.closeOrder().orderId(),
							protection.entryOrder().orderId(),
							protection.stopOrder() != null ? protection.stopOrder().orderId() : null,
							protection.takeProfitOrder() != null ? protection.takeProfitOrder().orderId() : null);
				})
				.doOnError(error -> LOGGER.warn("Flip failed during close/open sequence", error))
				.doFinally(signal -> tradingLock.set(false))
				.subscribe();
	}

	private void updateLossTracking(OrderResponse response, Direction direction) {
		BigDecimal exitPrice = response.avgPrice();
		BigDecimal entry = entryPrice.get();
		if (exitPrice == null || entry == null) {
			return;
		}
		BigDecimal quantity = calculatePositionQuantity();
		BigDecimal pnl;
		if (direction == Direction.LONG) {
			pnl = exitPrice.subtract(entry).multiply(quantity);
		} else {
			pnl = entry.subtract(exitPrice).multiply(quantity);
		}
		LocalDate today = LocalDate.now();
		if (!today.equals(lossDay.get())) {
			lossDay.set(today);
			dailyLoss.set(BigDecimal.ZERO);
			consecutiveLosses.set(0);
		}
		if (pnl.signum() < 0) {
			dailyLoss.set(dailyLoss.get().add(pnl.abs()));
			consecutiveLosses.incrementAndGet();
		} else {
			consecutiveLosses.set(0);
		}
		entryPrice.set(null);
	}

	private boolean riskAllowed() {
		if (dailyLoss.get().compareTo(strategyProperties.maxDailyLossUsdt()) >= 0) {
			LOGGER.warn("Daily loss limit exceeded: {}", dailyLoss.get());
			return false;
		}
		if (consecutiveLosses.get() >= strategyProperties.maxConsecutiveLosses()) {
			LOGGER.warn("Max consecutive losses reached: {}", consecutiveLosses.get());
			return false;
		}
		return true;
	}

	private FlipDecision evaluateFlipDecision(long now, BigDecimal obi, BigDecimal toi, BigDecimal cancelRatio) {
		Direction current = positionState.get();
		if (current == Direction.FLAT || current == Direction.NONE) {
			return new FlipDecision(false, false, Direction.NONE, "no-position", flipsInLast5Min(now));
		}
		if (!strategyProperties.flipEnabled()) {
			return new FlipDecision(false, false, Direction.NONE, "flip-disabled", flipsInLast5Min(now));
		}
		boolean strongLong = isStrongLong(obi, toi, cancelRatio);
		boolean strongShort = isStrongShort(obi, toi, cancelRatio);
		if (current == Direction.LONG && !strongShort) {
			return new FlipDecision(false, false, Direction.NONE, "no-strong-reversal", flipsInLast5Min(now));
		}
		if (current == Direction.SHORT && !strongLong) {
			return new FlipDecision(false, false, Direction.NONE, "no-strong-reversal", flipsInLast5Min(now));
		}
		long entryTs = entryTimestamp.get();
		if (entryTs > 0 && now - entryTs < strategyProperties.minHoldMs()) {
			return new FlipDecision(false, false, Direction.NONE, "min-hold", flipsInLast5Min(now));
		}
		long lastFlip = lastFlipTimestamp.get();
		if (lastFlip > 0 && now - lastFlip < strategyProperties.flipCooldownMs()) {
			return new FlipDecision(false, false, Direction.NONE, "flip-cooldown", flipsInLast5Min(now));
		}
		int flips = flipsInLast5Min(now);
		if (flips >= strategyProperties.maxFlipsPer5Min()) {
			return new FlipDecision(false, false, Direction.NONE, "max-flips", flips);
		}
		BigDecimal spreadBps = latestSpreadBps.get();
		if (spreadBps != null && spreadBps.compareTo(strategyProperties.flipSpreadMaxBps()) > 0) {
			return new FlipDecision(false, false, Direction.NONE, "spread-too-wide", flips);
		}
		if (!riskAllowed()) {
			return new FlipDecision(false, false, Direction.NONE, "risk-block", flips);
		}
		Direction flipTo = current == Direction.LONG ? Direction.SHORT : Direction.LONG;
		return new FlipDecision(true, true, flipTo, "strong-reversal", flips);
	}

	private boolean isStrongLong(BigDecimal obi, BigDecimal toi, BigDecimal cancelRatio) {
		return obi.compareTo(strategyProperties.strongObi()) > 0
				&& toi.compareTo(strategyProperties.strongToi()) > 0
				&& cancelRatio.compareTo(strategyProperties.cancelMax()) < 0;
	}

	private boolean isStrongShort(BigDecimal obi, BigDecimal toi, BigDecimal cancelRatio) {
		return obi.compareTo(strategyProperties.strongObi().negate()) < 0
				&& toi.compareTo(strategyProperties.strongToi().negate()) < 0
				&& cancelRatio.compareTo(strategyProperties.cancelMax()) < 0;
	}

	private void recordFlip(long now) {
		synchronized (flipLock) {
			flipHistory.addLast(now);
			pruneFlipHistory(now);
		}
	}

	private int flipsInLast5Min(long now) {
		synchronized (flipLock) {
			pruneFlipHistory(now);
			return flipHistory.size();
		}
	}

	private void pruneFlipHistory(long now) {
		long cutoff = now - Duration.ofMinutes(5).toMillis();
		while (!flipHistory.isEmpty() && flipHistory.peekFirst() < cutoff) {
			flipHistory.removeFirst();
		}
	}

	private BigDecimal calculatePositionQuantity() {
		BigDecimal notional = strategyProperties.positionNotionalUsdt();
		BigDecimal mid = latestFuturesMid.get();
		if (notional != null && mid != null && mid.signum() > 0) {
			BigDecimal quantity = notional.divide(mid, MathContext.DECIMAL64);
			if (strategyProperties.maxPositionUsdt() != null
					&& notional.compareTo(strategyProperties.maxPositionUsdt()) > 0) {
				quantity = strategyProperties.maxPositionUsdt().divide(mid, MathContext.DECIMAL64);
			}
			return roundDownToStep(quantity, strategyProperties.quantityStep());
		}
		return roundDownToStep(strategyProperties.marketQuantity(), strategyProperties.quantityStep());
	}

	private BigDecimal stopPrice(BigDecimal entry, Direction direction) {
		BigDecimal bps = strategyProperties.stopLossBps().divide(new BigDecimal("10000"), MathContext.DECIMAL64);
		if (direction == Direction.LONG) {
			return roundDownToStep(entry.multiply(BigDecimal.ONE.subtract(bps, MathContext.DECIMAL64), MathContext.DECIMAL64),
					strategyProperties.priceTick());
		}
		return roundDownToStep(entry.multiply(BigDecimal.ONE.add(bps, MathContext.DECIMAL64), MathContext.DECIMAL64),
				strategyProperties.priceTick());
	}

	private BigDecimal takeProfitPrice(BigDecimal entry, Direction direction) {
		BigDecimal bps = strategyProperties.takeProfitBps().divide(new BigDecimal("10000"), MathContext.DECIMAL64);
		if (direction == Direction.LONG) {
			return roundDownToStep(entry.multiply(BigDecimal.ONE.add(bps, MathContext.DECIMAL64), MathContext.DECIMAL64),
					strategyProperties.priceTick());
		}
		return roundDownToStep(entry.multiply(BigDecimal.ONE.subtract(bps, MathContext.DECIMAL64), MathContext.DECIMAL64),
				strategyProperties.priceTick());
	}

	private BigDecimal roundDownToStep(BigDecimal value, BigDecimal step) {
		if (value == null || step == null || step.signum() <= 0) {
			return value;
		}
		BigDecimal ratio = value.divide(step, 0, java.math.RoundingMode.DOWN);
		return ratio.multiply(step, MathContext.DECIMAL64);
	}

	private void resyncOrderBook(String reason) {
		resyncCount.incrementAndGet();
		LOGGER.warn("Order book resync triggered ({}). Reason: {}", resyncCount.get(), reason);
		orderBook.reset();
		depthSynced.set(false);
		depthBuffer.clear();
		firstBufferedUpdateId.set(-1);
	}

	private enum Direction {
		LONG,
		SHORT,
		FLAT,
		NONE
	}

	private record ProtectionOrders(OrderResponse entryOrder, OrderResponse stopOrder, OrderResponse takeProfitOrder) {
	}

	private record FlipOrders(OrderResponse closeOrder, ProtectionOrders protectionOrders) {
	}

	private record FlipDecision(boolean shouldFlip, boolean canFlip, Direction flipDirection, String reason,
			int flipsInLast5Min) {
	}

	private record CloseContext(OrderResponse closeOrder, boolean hedgeMode) {
	}
}
