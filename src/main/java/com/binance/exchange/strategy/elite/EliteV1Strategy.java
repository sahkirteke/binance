package com.binance.exchange.strategy.elite;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.binance.market.dto.KlineEvent;
import com.binance.strategy.Candle;
import com.binance.strategy.Strategy;
import com.binance.strategy.StrategyType;
import com.binance.strategy.SymbolFilterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Component
public class EliteV1Strategy implements Strategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(EliteV1Strategy.class);
	private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
	private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final int MIN_5M_BARS = 60;
	private static final double DEFAULT_NOTIONAL = 50.0;
	private static final double DEFAULT_TICK_SIZE = 0.01;
	private static final Path DECISION_DIR = Paths.get("signals", "decisions");
	private static final Path TRADE_DIR = Paths.get("signals", "trades");

	private final BinanceProperties binanceProperties;
	private final EliteV1Properties props;
	private final ObjectMapper objectMapper;
	private final SymbolFilterService symbolFilterService;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final Scheduler loop = Schedulers.newSingle("elite-v1-loop", true);
	private final Map<String, SymbolState> states = new ConcurrentHashMap<>();
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AsyncJsonlWriter writer = new AsyncJsonlWriter(20_000);
	private volatile Disposable wsSubscription;

	public EliteV1Strategy(BinanceProperties binanceProperties,
			EliteV1Properties props,
			ObjectMapper objectMapper,
			SymbolFilterService symbolFilterService) {
		this.binanceProperties = binanceProperties;
		this.props = props;
		this.objectMapper = objectMapper;
		this.symbolFilterService = symbolFilterService;
	}

	@Override
	public StrategyType type() {
		return StrategyType.ELITE_V1;
	}

	@Override
	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		writer.start();
		validateConfig();
		props.symbols().forEach(symbol -> states.put(symbol, new SymbolState(symbol)));
		symbolFilterService.preloadFilters(props.symbols()).subscribe();

		String streams = props.symbols().stream()
				.map(symbol -> symbol.toLowerCase() + "@kline_1m")
				.collect(Collectors.joining("/"));
		String base = binanceProperties.useTestnet()
				? "wss://stream.binancefuture.com/stream?streams="
				: "wss://fstream.binance.com/stream?streams=";
		URI uri = URI.create(base + streams);

		wsSubscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.publishOn(loop)
				.doOnNext(this::onWsMessage)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe();
		LOGGER.info("ELITE_V1 started mode={} symbols={}", props.mode(), props.symbols().size());
	}

	@Override
	public void stop() {
		if (!started.compareAndSet(true, false)) {
			return;
		}
		if (wsSubscription != null) {
			wsSubscription.dispose();
		}
		writer.stop();
		loop.dispose();
	}

	@PreDestroy
	public void onDestroy() {
		stop();
	}

	private void onWsMessage(String payload) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			JsonNode data = root.path("data");
			KlineEvent event = objectMapper.treeToValue(data.isMissingNode() ? root : data, KlineEvent.class);
			if (event == null || event.kline() == null || !event.kline().closed()) {
				return;
			}
			SymbolState state = states.get(event.symbol());
			if (state == null) {
				return;
			}
			Candle candle = new Candle(
					event.kline().open(),
					event.kline().high(),
					event.kline().low(),
					event.kline().close(),
					event.kline().volume(),
					event.kline().closeTime());
			onClosed1m(state, candle);
		} catch (Exception ex) {
			LOGGER.warn("ELITE_V1 parse error", ex);
		}
	}

	private void onClosed1m(SymbolState state, Candle bar1m) {
		state.last1m.addLast(bar1m);
		if (state.last1m.size() > 1000) {
			state.last1m.removeFirst();
		}
		checkPaperExit(state, bar1m);

		Candle bar5m = state.aggregator.addFinalOneMinute(bar1m);
		if (bar5m == null) {
			return;
		}
		state.last5m.addLast(bar5m);
		if (state.last5m.size() > 1000) {
			state.last5m.removeFirst();
		}
		state.indicators.update(bar5m);
		evaluateAt5m(state, bar5m);
	}

	private void evaluateAt5m(SymbolState state, Candle bar5m) {
		String dayKey = dayKey(bar5m.closeTime());
		if (!Objects.equals(dayKey, state.dayKey)) {
			state.dayKey = dayKey;
			state.tradedToday = false;
			state.entriesToday = 0;
		}

		PreCheckAction preCheck = evaluatePreChecks(
				state.indicators.baselinesReady(),
				state.positionSide,
				state.tradedToday || state.entriesToday >= props.maxEntriesPerSymbolPerDay(),
				openPositionCount(),
				props.maxOpenPositions());
		if (preCheck.action() != DecisionAction.CONTINUE) {
			writeDecision(state, bar5m, preCheck.action().name(), null, null, preCheck.blockReason(), List.of(), null);
			return;
		}

		Metrics metrics = state.indicators.metrics();
		if (!metrics.readyForRegime()) {
			writeDecision(state, bar5m, "INPUTS_NOT_READY", null, null, "INPUTS_NOT_READY", List.of(), metrics);
			return;
		}
		RegimeTag rawRegime = rawRegime(metrics.bwRatio5m, metrics.macdRatio5m);
		RegimeTag activeRegime = state.regimeState.update(rawRegime);
		metrics = metrics.withRegimes(rawRegime, activeRegime);

		Candidate candidate = findCandidate(activeRegime, metrics);
		if (candidate.side == Side.NONE) {
			writeDecision(state, bar5m, "NO_ENTRY", "NONE", null, candidate.blockReason, candidate.failReasons, metrics);
			return;
		}

		openPaperPosition(state, bar5m, candidate);
		writeDecision(state,
				bar5m,
				candidate.side == Side.LONG ? "ENTER_LONG" : "ENTER_SHORT",
				candidate.side.name(),
				candidate.setup,
				null,
				candidate.failReasons,
				metrics);
	}

	static PreCheckAction evaluatePreChecks(boolean baselinesReady,
			Side positionSide,
			boolean tradedToday,
			int globalOpenPositions,
			int maxOpenPositions) {
		if (!baselinesReady) {
			return new PreCheckAction(DecisionAction.INPUTS_NOT_READY, null);
		}
		if (positionSide != Side.NONE) {
			return new PreCheckAction(DecisionAction.IN_POSITION, null);
		}
		if (tradedToday) {
			return new PreCheckAction(DecisionAction.TRADDED_TODAY, "TRADDED_TODAY");
		}
		if (globalOpenPositions >= maxOpenPositions) {
			return new PreCheckAction(DecisionAction.NO_ENTRY, "GLOBAL_MAX_OPEN_POS");
		}
		return new PreCheckAction(DecisionAction.CONTINUE, null);
	}

	private Candidate findCandidate(RegimeTag activeRegime, Metrics m) {
		if (props.longConfig().enabled() && activeRegime == RegimeTag.CHOP) {
			if (m.rsi9_5m >= props.longConfig().rsiMin()
					&& m.rsi9_5m <= props.longConfig().rsiMax()
					&& m.ema20DistPct >= props.longConfig().ema20DistMin()
					&& m.bbPercentB_5m <= props.longConfig().bbPercentBMax()) {
				if (props.longConfig().enableSetup5SafetyGate()) {
					if (m.bbWidth_5m >= props.longConfig().setup5().maxBbWidth()) {
						return Candidate.blocked("SETUP5_BLOCK_BBWIDTH_TOO_WIDE");
					}
					if (m.volRatio >= props.longConfig().setup5().maxVolRatio()) {
						return Candidate.blocked("SETUP5_BLOCK_VOL_SPIKE");
					}
					if (activeRegime == RegimeTag.CHOP && m.bwRatio5m > props.longConfig().setup5().chopMaxBwRatio()) {
						return Candidate.blocked("SETUP5_BLOCK_CHOP_BWRATIO");
					}
				}
				return Candidate.long("SETUP5_ELITE");
			}
		}

		if (props.shortConfig().enabled() && activeRegime == RegimeTag.TREND) {
			if (props.shortConfig().veto().requireBbOutsideFalse() && m.bbOutside_5m) {
				return Candidate.blocked("SHORT_VETO_BB_OUTSIDE");
			}
			if (m.bbPercentB_5m <= props.shortConfig().veto().bbPercentBMinExclusive()) {
				return Candidate.blocked("SHORT_VETO_PB_TOO_LOW");
			}
			if (m.ema20DistPct > props.shortConfig().veto().ema20DistPctMax()) {
				return Candidate.blocked("SHORT_VETO_EMA20_CHASE");
			}
			if (matchShortElite1(m)) {
				return Candidate.short("SHORT_ELITE_1");
			}
			if (matchShortElite2(m)) {
				return Candidate.short("SHORT_ELITE_2");
			}
			return Candidate.blocked("NO_SHORT_ELITE_MATCH");
		}
		return Candidate.blocked("NO_ENTRY");
	}

	private boolean matchShortElite1(Metrics m) {
		EliteV1Properties.ShortEliteBand b = props.shortConfig().elite1();
		return inRangeMinIncMaxExc(m.bbPercentB_5m, b.pbMin(), b.pbMax())
				&& inRangeMinIncMaxExc(m.bwRatio5m, b.bwRatioMin(), b.bwRatioMax())
				&& m.volRatioOfEma <= b.volRatioOfEmaMax()
				&& m.macdRatio5m >= b.macdRatioMin();
	}

	private boolean matchShortElite2(Metrics m) {
		EliteV1Properties.ShortEliteBand b = props.shortConfig().elite2();
		return inRangeMinIncMaxExc(m.bbPercentB_5m, b.pbMin(), b.pbMax())
				&& inRangeMinIncMaxExc(m.bwRatio5m, b.bwRatioMin(), b.bwRatioMax())
				&& m.volRatioOfEma <= b.volRatioOfEmaMax()
				&& m.macdRatio5m >= b.macdRatioMin();
	}

	private boolean inRangeMinIncMaxExc(double value, double min, double maxExclusive) {
		return value >= min && value < maxExclusive;
	}

	private void openPaperPosition(SymbolState state, Candle bar5m, Candidate candidate) {
		double entryPrice = bar5m.close();
		double notional = props.paperNotional() != null ? props.paperNotional() : DEFAULT_NOTIONAL;
		double qty = notional / Math.max(entryPrice, 1e-9);
		double tick = resolveTickSize(state.symbol);

		double tpRaw;
		double slRaw;
		double tpPrice;
		double slPrice;
		if (candidate.side == Side.LONG) {
			tpRaw = entryPrice * (1.0 + props.tpPct());
			slRaw = entryPrice * (1.0 - props.slPct());
			tpPrice = roundUp(tpRaw, tick);
			slPrice = roundDown(slRaw, tick);
		} else {
			tpRaw = entryPrice * (1.0 - props.tpPct());
			slRaw = entryPrice * (1.0 + props.slPct());
			tpPrice = roundDown(tpRaw, tick);
			slPrice = roundUp(slRaw, tick);
		}

		state.positionSide = candidate.side;
		state.entryPrice = entryPrice;
		state.qty = qty;
		state.entryTimeMs = bar5m.closeTime();
		state.order = new VirtualBracketOrder(UUID.randomUUID().toString(), tpPrice, slPrice, state.entryTimeMs);
		state.entriesToday++;
		state.tpRaw = tpRaw;
		state.slRaw = slRaw;
		writeTradeEntry(state, candidate.setup);
	}

	private void checkPaperExit(SymbolState state, Candle bar1m) {
		if (state.positionSide == Side.NONE || state.order == null) {
			return;
		}
		ExitReason touchExit = resolveTouchExit(state.positionSide,
				state.order.tpPrice,
				state.order.slPrice,
				bar1m,
				props.conflictResolution());
		if (touchExit != null) {
			double exitPrice = touchExit == ExitReason.TAKE_PROFIT ? state.order.tpPrice : state.order.slPrice;
			exitPosition(state, touchExit, exitPrice, bar1m.closeTime());
			return;
		}
		if (shouldTimeStop(state.entryTimeMs, bar1m.closeTime(), props.timeStopMinutes())) {
			exitPosition(state, ExitReason.TIME_STOP_20M, bar1m.close(), bar1m.closeTime());
		}
	}

	static boolean shouldTimeStop(long entryTimeMs, long nowMs, int timeStopMinutes) {
		return nowMs - entryTimeMs >= (long) timeStopMinutes * 60_000L;
	}

	static ExitReason resolveTouchExit(Side side,
			double tpPrice,
			double slPrice,
			Candle oneMinuteBar,
			EliteV1Properties.ConflictResolution conflictResolution) {
		boolean touchedTp;
		boolean touchedSl;
		if (side == Side.LONG) {
			touchedTp = oneMinuteBar.high() >= tpPrice;
			touchedSl = oneMinuteBar.low() <= slPrice;
		} else if (side == Side.SHORT) {
			touchedTp = oneMinuteBar.low() <= tpPrice;
			touchedSl = oneMinuteBar.high() >= slPrice;
		} else {
			return null;
		}
		if (!touchedTp && !touchedSl) {
			return null;
		}
		if (touchedTp && touchedSl) {
			return conflictResolution == EliteV1Properties.ConflictResolution.SL_FIRST
					? ExitReason.STOP_LOSS
					: ExitReason.TAKE_PROFIT;
		}
		return touchedTp ? ExitReason.TAKE_PROFIT : ExitReason.STOP_LOSS;
	}

	private void exitPosition(SymbolState state, ExitReason reason, double exitPrice, long exitTimeMs) {
		double pnl = state.positionSide == Side.LONG
				? (exitPrice - state.entryPrice) * state.qty
				: (state.entryPrice - exitPrice) * state.qty;
		writeTradeExit(state, reason, exitPrice, pnl, exitTimeMs);
		state.positionSide = Side.NONE;
		state.order = null;
		state.tradedToday = true;
	}

	private int openPositionCount() {
		return (int) states.values().stream().filter(state -> state.positionSide != Side.NONE).count();
	}

	private void writeDecision(SymbolState state,
			Candle bar5m,
			String action,
			String entryCandidateSide,
			String matchedSetup,
			String blockReason,
			List<String> failReasons,
			Metrics metrics) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "DECISION");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(bar5m.closeTime()).toString());
		node.put("dayKey", state.dayKey);
		node.put("baselinesReady", state.indicators.barCount() >= MIN_5M_BARS);
		node.put("activeRegimeTag", metrics == null ? "UNKNOWN" : metrics.activeRegime.name());
		ObjectNode metricNode = node.putObject("metrics");
		if (metrics != null) {
			metricNode.put("bbWidth_5m", metrics.bbWidth_5m);
			metricNode.put("bwRatio_5m", metrics.bwRatio5m);
			metricNode.put("volRatio", metrics.volRatio);
			metricNode.put("volRatioOfEma", metrics.volRatioOfEma);
			metricNode.put("macdRatio_5m", metrics.macdRatio5m);
			metricNode.put("atrRatio_5m", metrics.atrRatio5m);
			metricNode.put("ema20DistPct", metrics.ema20DistPct);
			metricNode.put("bbPercentB_5m", metrics.bbPercentB_5m);
		}
		node.put("entryCandidateSide", entryCandidateSide == null ? "NONE" : entryCandidateSide);
		node.put("matchedSetup", matchedSetup);
		node.put("action", action);
		node.put("blockReason", blockReason);
		ArrayNode reasons = node.putArray("failReasons");
		failReasons.forEach(reasons::add);
		writer.write(DECISION_DIR.resolve(state.symbol + "-" + state.dayKey + ".jsonl"), node.toString(), false);
	}

	private void writeTradeEntry(SymbolState state, String setup) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "ENTRY");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(state.entryTimeMs).toString());
		node.put("setup", setup);
		node.put("entryPrice", state.entryPrice);
		node.put("qty", state.qty);
		node.put("tpRaw", state.tpRaw);
		node.put("tpPrice", state.order.tpPrice);
		node.put("slRaw", state.slRaw);
		node.put("slPrice", state.order.slPrice);
		node.put("tpPct", props.tpPct());
		node.put("slPct", props.slPct());
		writer.write(TRADE_DIR.resolve(state.symbol + "-" + state.dayKey + ".jsonl"), node.toString(), true);
	}

	private void writeTradeExit(SymbolState state, ExitReason reason, double exitPrice, double realizedPnl, long exitTimeMs) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "EXIT");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(exitTimeMs).toString());
		node.put("exitReason", reason.name());
		node.put("exitPrice", exitPrice);
		node.put("realizedPnl", realizedPnl);
		writer.write(TRADE_DIR.resolve(state.symbol + "-" + state.dayKey + ".jsonl"), node.toString(), true);
	}

	private double resolveTickSize(String symbol) {
		var filters = symbolFilterService.getFilters(symbol);
		if (filters == null || filters.tickSize() == null) {
			return DEFAULT_TICK_SIZE;
		}
		return filters.tickSize().doubleValue();
	}

	static double roundUp(double raw, double tickSize) {
		BigDecimal tick = BigDecimal.valueOf(tickSize);
		return BigDecimal.valueOf(raw)
				.divide(tick, 0, RoundingMode.CEILING)
				.multiply(tick)
				.doubleValue();
	}

	static double roundDown(double raw, double tickSize) {
		BigDecimal tick = BigDecimal.valueOf(tickSize);
		return BigDecimal.valueOf(raw)
				.divide(tick, 0, RoundingMode.FLOOR)
				.multiply(tick)
				.doubleValue();
	}

	private static RegimeTag rawRegime(double bwRatio, double macdRatio) {
		if (bwRatio < 1.15 && macdRatio < 1.50) {
			return RegimeTag.CHOP;
		}
		return RegimeTag.TREND;
	}

	private String dayKey(long closeTimeMs) {
		LocalDate date = Instant.ofEpochMilli(closeTimeMs).atZone(ISTANBUL).toLocalDate();
		return DAY_FMT.format(date);
	}

	private void validateConfig() {
		if (!"1m".equalsIgnoreCase(props.timeframe())) {
			throw new IllegalStateException("ELITE_V1 timeframe must be 1m");
		}
		if (!"5m".equalsIgnoreCase(props.evalEvery())) {
			throw new IllegalStateException("ELITE_V1 evalEvery must be 5m");
		}
		if (props.inputsNotReadyPolicy() != EliteV1Properties.InputsNotReadyPolicy.NO_TRADE) {
			throw new IllegalStateException("ELITE_V1 supports only inputsNotReadyPolicy=NO_TRADE");
		}
	}

	enum Side {
		NONE,
		LONG,
		SHORT
	}

	enum RegimeTag {
		CHOP,
		TREND
	}

	enum ExitReason {
		TAKE_PROFIT,
		STOP_LOSS,
		TIME_STOP_20M
	}

	enum DecisionAction {
		CONTINUE,
		INPUTS_NOT_READY,
		IN_POSITION,
		TRADDED_TODAY,
		NO_ENTRY
	}

	record PreCheckAction(DecisionAction action, String blockReason) {
	}

	private record Candidate(Side side, String setup, String blockReason, List<String> failReasons) {
		static Candidate long(String setup) {
			return new Candidate(Side.LONG, setup, null, List.of());
		}

		static Candidate short(String setup) {
			return new Candidate(Side.SHORT, setup, null, List.of());
		}

		static Candidate blocked(String blockReason) {
			return new Candidate(Side.NONE, null, blockReason, List.of(blockReason));
		}
	}

	private record VirtualBracketOrder(String orderId, double tpPrice, double slPrice, long entryTimeMs) {
	}

	private record Metrics(double bbWidth_5m,
			double bwRatio5m,
			double volRatio,
			double volRatioOfEma,
			double macdRatio5m,
			double atrRatio5m,
			double ema20DistPct,
			double bbPercentB_5m,
			double rsi9_5m,
			boolean bbOutside_5m,
			RegimeTag rawRegime,
			RegimeTag activeRegime,
			boolean readyForRegime) {
		Metrics withRegimes(RegimeTag raw, RegimeTag active) {
			return new Metrics(bbWidth_5m,
					bwRatio5m,
					volRatio,
					volRatioOfEma,
					macdRatio5m,
					atrRatio5m,
					ema20DistPct,
					bbPercentB_5m,
					rsi9_5m,
					bbOutside_5m,
					raw,
					active,
					readyForRegime);
		}
	}

	private static final class SymbolState {
		private final String symbol;
		private final FiveBarAggregator aggregator = new FiveBarAggregator();
		private final Deque<Candle> last1m = new ArrayDeque<>();
		private final Deque<Candle> last5m = new ArrayDeque<>();
		private final IndicatorState indicators = new IndicatorState();
		private final RegimeState regimeState = new RegimeState();
		private String dayKey;
		private boolean tradedToday;
		private int entriesToday;
		private Side positionSide = Side.NONE;
		private double entryPrice;
		private double qty;
		private long entryTimeMs;
		private double tpRaw;
		private double slRaw;
		private VirtualBracketOrder order;

		private SymbolState(String symbol) {
			this.symbol = symbol;
		}
	}

	private static final class FiveBarAggregator {
		private final List<Candle> buffer = new ArrayList<>(5);

		private Candle addFinalOneMinute(Candle finalOneMinute) {
			buffer.add(finalOneMinute);
			if (buffer.size() < 5) {
				return null;
			}
			Candle first = buffer.get(0);
			Candle last = buffer.get(4);
			double high = buffer.stream().mapToDouble(Candle::high).max().orElse(first.high());
			double low = buffer.stream().mapToDouble(Candle::low).min().orElse(first.low());
			double volume = buffer.stream().mapToDouble(Candle::volume).sum();
			Candle out = new Candle(first.open(), high, low, last.close(), volume, last.closeTime());
			buffer.clear();
			return out;
		}
	}

	private static final class RegimeState {
		private RegimeTag active = RegimeTag.CHOP;
		private final Deque<RegimeTag> lastRaw = new ArrayDeque<>();
		private int cooldownBarsLeft;

		private RegimeTag update(RegimeTag raw) {
			lastRaw.addLast(raw);
			if (lastRaw.size() > 2) {
				lastRaw.removeFirst();
			}
			if (cooldownBarsLeft > 0) {
				cooldownBarsLeft--;
				return active;
			}
			if (lastRaw.size() == 2) {
				RegimeTag first = lastRaw.peekFirst();
				RegimeTag second = lastRaw.peekLast();
				if (first == second && active != first) {
					active = first;
					cooldownBarsLeft = 3;
				}
			}
			return active;
		}
	}

	private static final class IndicatorState {
		private final Deque<Double> closeWindow20 = new ArrayDeque<>();
		private final Deque<Double> volumeWindow20 = new ArrayDeque<>();
		private final Deque<Double> trWindow14 = new ArrayDeque<>();
		private double ema20;
		private boolean ema20Ready;
		private double prevClose;
		private boolean prevCloseReady;
		private double prevCloseAtr;
		private boolean prevCloseAtrReady;
		private double avgGain;
		private double avgLoss;
		private int rsiCounter;
		private double ema12;
		private boolean ema12Ready;
		private double ema26;
		private boolean ema26Ready;
		private double macdSignal;
		private boolean macdSignalReady;
		private int barCount;
		private final Ewma bwEma = new Ewma(20);
		private final Ewma atrEma = new Ewma(20);
		private final Ewma macdAbsEma = new Ewma(20);
		private final Ewma volumeEma = new Ewma(20);
		private final Ewma volRatioEma = new Ewma(20);
		private Metrics latest;
		private boolean baselinesReady;
		private int regimeReadyBars;

		private void update(Candle bar) {
			barCount++;
			double close = bar.close();
			updateEma20(close);
			updateRsi(close);
			double tr = updateAtr(bar);
			double atr14 = average(trWindow14);
			double atrEmaValue = atrEma.update(atr14);
			double atrRatio = ratio(atr14, atrEmaValue);
			double bbWidth = computeBbWidth(close);
			double bwEmaValue = bwEma.update(bbWidth);
			double bwRatio = ratio(bbWidth, bwEmaValue);

			updateMacd(close);
			double macd = ema12 - ema26;
			double macdDelta = macd - macdSignal;
			double macdAbs = macdAbsEma.update(Math.abs(macdDelta));
			double macdRatio = ratio(Math.abs(macdDelta), macdAbs);

			double volEmaValue = volumeEma.update(bar.volume());
			double volRatio = ratio(bar.volume(), volEmaValue);
			double volRatioOfEma;
			if (volRatioEma.initialized()) {
				volRatioOfEma = ratio(volRatio, volRatioEma.update(volRatio));
			} else {
				volRatioEma.update(volRatio);
				volRatioOfEma = 1.0;
			}

			double std = std(closeWindow20);
			double sma = average(closeWindow20);
			double bbUpper = sma + 2.0 * std;
			double bbLower = sma - 2.0 * std;
			double bbPercentB = ratio(close - bbLower, Math.max(bbUpper - bbLower, 1e-9));
			boolean bbOutside = close > bbUpper || close < bbLower;
			double ema20DistPct = ratio(Math.abs(close - ema20), Math.max(close, 1e-9));
			double rsi = computeRsi();

			boolean readyForRegime = bwEma.initialized()
					&& atrEma.initialized()
					&& macdAbsEma.initialized();
			if (readyForRegime) {
				regimeReadyBars++;
			}
			baselinesReady = readyForRegime && regimeReadyBars >= MIN_5M_BARS;

			latest = new Metrics(
					bbWidth,
					bwRatio,
					volRatio,
					volRatioOfEma,
					macdRatio,
					atrRatio,
					ema20DistPct,
					bbPercentB,
					rsi,
					bbOutside,
					RegimeTag.CHOP,
					RegimeTag.CHOP,
					readyForRegime);
		}

		private void updateEma20(double close) {
			if (!ema20Ready) {
				ema20 = close;
				ema20Ready = true;
			} else {
				ema20 = ema(ema20, close, 20);
			}
			push(closeWindow20, close, 20);
		}

		private void updateRsi(double close) {
			if (!prevCloseReady) {
				prevClose = close;
				prevCloseReady = true;
				return;
			}
			double change = close - prevClose;
			double gain = Math.max(change, 0);
			double loss = Math.max(-change, 0);
			rsiCounter++;
			if (rsiCounter == 1) {
				avgGain = gain;
				avgLoss = loss;
			} else {
				avgGain = (avgGain * 8 + gain) / 9;
				avgLoss = (avgLoss * 8 + loss) / 9;
			}
			prevClose = close;
		}

		private double updateAtr(Candle bar) {
			double tr;
			if (!prevCloseAtrReady) {
				tr = bar.high() - bar.low();
				prevCloseAtrReady = true;
			} else {
				tr = Math.max(bar.high() - bar.low(),
						Math.max(Math.abs(bar.high() - prevCloseAtr), Math.abs(bar.low() - prevCloseAtr)));
			}
			prevCloseAtr = bar.close();
			push(trWindow14, tr, 14);
			return tr;
		}

		private void updateMacd(double close) {
			if (!ema12Ready) {
				ema12 = close;
				ema12Ready = true;
			} else {
				ema12 = ema(ema12, close, 12);
			}
			if (!ema26Ready) {
				ema26 = close;
				ema26Ready = true;
			} else {
				ema26 = ema(ema26, close, 26);
			}
			double macd = ema12 - ema26;
			if (!macdSignalReady) {
				macdSignal = macd;
				macdSignalReady = true;
			} else {
				macdSignal = ema(macdSignal, macd, 9);
			}
		}

		private double computeBbWidth(double close) {
			double std = std(closeWindow20);
			double sma = average(closeWindow20);
			double bbUpper = sma + 2.0 * std;
			double bbLower = sma - 2.0 * std;
			return ratio(bbUpper - bbLower, Math.max(close, 1e-9));
		}

		private double computeRsi() {
			if (avgLoss == 0.0) {
				return 100.0;
			}
			double rs = avgGain / avgLoss;
			return 100.0 - (100.0 / (1.0 + rs));
		}

		private double ema(double previous, double current, int period) {
			double alpha = 2.0 / (period + 1.0);
			return alpha * current + (1.0 - alpha) * previous;
		}

		private double average(Deque<Double> values) {
			if (values.isEmpty()) {
				return 0.0;
			}
			return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
		}

		private double std(Deque<Double> values) {
			if (values.size() < 2) {
				return 0.0;
			}
			double avg = average(values);
			double var = values.stream().mapToDouble(v -> (v - avg) * (v - avg)).average().orElse(0.0);
			return Math.sqrt(var);
		}

		private double ratio(double value, double base) {
			return base <= 1e-9 ? 1.0 : value / base;
		}

		private void push(Deque<Double> values, double value, int maxSize) {
			values.addLast(value);
			if (values.size() > maxSize) {
				values.removeFirst();
			}
		}

		private Metrics metrics() {
			return latest;
		}

		private int barCount() {
			return barCount;
		}

		private boolean baselinesReady() {
			return baselinesReady;
		}
	}

	private static final class Ewma {
		private final double alpha;
		private boolean initialized;
		private double value;

		private Ewma(int period) {
			this.alpha = 2.0 / (period + 1.0);
		}

		private double update(double next) {
			if (!initialized) {
				value = next;
				initialized = true;
			} else {
				value = alpha * next + (1.0 - alpha) * value;
			}
			return value;
		}

		private boolean initialized() {
			return initialized;
		}
	}

	private static final class AsyncJsonlWriter {
		private final Logger logger = LoggerFactory.getLogger(AsyncJsonlWriter.class);
		private final ArrayBlockingQueue<LogItem> queue;
		private final AtomicBoolean running = new AtomicBoolean(false);
		private final AtomicLong dropped = new AtomicLong();
		private Thread worker;

		private AsyncJsonlWriter(int capacity) {
			this.queue = new ArrayBlockingQueue<>(capacity);
		}

		private void start() {
			if (!running.compareAndSet(false, true)) {
				return;
			}
			worker = new Thread(() -> {
				while (running.get() || !queue.isEmpty()) {
					try {
						LogItem item = queue.poll();
						if (item == null) {
							Thread.sleep(10);
							continue;
						}
						Files.createDirectories(item.path().getParent());
						Files.writeString(item.path(), item.line() + "\n", StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					} catch (InterruptedException interruptedException) {
						Thread.currentThread().interrupt();
					} catch (IOException ioException) {
						logger.warn("elite log write failed", ioException);
					}
				}
			});
			worker.setName("elite-v1-log-writer");
			worker.start();
		}

		private void write(Path path, String line, boolean critical) {
			LogItem item = new LogItem(path, line);
			if (critical) {
				try {
					queue.put(item);
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
				}
				return;
			}
			if (!queue.offer(item)) {
				long droppedCount = dropped.incrementAndGet();
				if (droppedCount % 100 == 0) {
					logger.warn("elite async decision log dropped={} entries", droppedCount);
				}
			}
		}

		private void stop() {
			running.set(false);
			if (worker != null) {
				worker.interrupt();
			}
		}

		private record LogItem(Path path, String line) {
		}
	}
}
