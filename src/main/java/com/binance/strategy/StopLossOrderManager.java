package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class StopLossOrderManager {

	private static final String WORKING_TYPE_MARK_PRICE = "MARK_PRICE";
	private static final Path SIGNAL_OUTPUT_DIR = Paths.get("signals", "decisions");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("ddMMyyyy HH:mm:ss");

	private final StrategyProperties strategyProperties;
	private final SymbolFilterService symbolFilterService;
	private final StopLossOrderExecutor paperExecutor;
	private final StopLossOrderExecutor liveExecutor;
	private final ObjectMapper objectMapper;
	private final Map<String, StopLossOrderState> activeOrders = new ConcurrentHashMap<>();
	private final Map<String, Double> lastMarkPrice = new ConcurrentHashMap<>();
	private final Map<String, Double> lastPrice = new ConcurrentHashMap<>();
	private volatile StopLossTriggerHandler triggerHandler;

	public StopLossOrderManager(StrategyProperties strategyProperties,
			SymbolFilterService symbolFilterService,
			PaperStopLossOrderExecutor paperExecutor,
			LiveStopLossOrderExecutor liveExecutor,
			ObjectMapper objectMapper) {
		this.strategyProperties = strategyProperties;
		this.symbolFilterService = symbolFilterService;
		this.paperExecutor = paperExecutor;
		this.liveExecutor = liveExecutor;
		this.objectMapper = objectMapper;
	}

	public void registerTriggerHandler(StopLossTriggerHandler triggerHandler) {
		this.triggerHandler = triggerHandler;
	}

	public void onEntryFilled(String symbol, CtiDirection side, BigDecimal entryAvgPrice) {
		if (symbol == null || side == null || entryAvgPrice == null || entryAvgPrice.signum() <= 0) {
			return;
		}
		StopLossMode mode = resolveMode();
		double slPct = resolveStopLossPct();
		if (slPct <= 0) {
			return;
		}
		BigDecimal tickSize = resolveTickSize(symbol);
		double entryPrice = entryAvgPrice.doubleValue();
		double stopPriceRaw = side == CtiDirection.SHORT
				? entryPrice * (1.0 + slPct)
				: entryPrice * (1.0 - slPct);
		double stopPriceRounded = roundStopPrice(stopPriceRaw, tickSize, side);
		StopLossOrderRequest request = new StopLossOrderRequest(
				symbol,
				side,
				stopPriceRounded,
				WORKING_TYPE_MARK_PRICE,
				true,
				true,
				entryAvgPrice,
				slPct,
				stopPriceRaw,
				tickSize);
		StopLossOrderResult result = executorFor(mode).placeStopLoss(request);
		activeOrders.put(symbol, new StopLossOrderState(result.orderId(), side, stopPriceRounded, mode));
		writeOrderCreated(symbol, side, entryPrice, slPct, stopPriceRaw, stopPriceRounded, tickSize, mode,
				result.orderId());
	}

	public void onPriceUpdate(String symbol, Double markPriceValue, Double lastPriceValue) {
		if (symbol == null) {
			return;
		}
		if (markPriceValue != null && Double.isFinite(markPriceValue)) {
			lastMarkPrice.put(symbol, markPriceValue);
		}
		if (lastPriceValue != null && Double.isFinite(lastPriceValue)) {
			lastPrice.put(symbol, lastPriceValue);
		}
		StopLossOrderState orderState = activeOrders.get(symbol);
		if (orderState == null) {
			return;
		}
		double triggerPrice = currentStopTriggerPrice(symbol);
		if (!Double.isFinite(triggerPrice)) {
			return;
		}
		boolean triggered = orderState.side() == CtiDirection.LONG
				? triggerPrice <= orderState.stopPrice()
				: triggerPrice >= orderState.stopPrice();
		if (!triggered) {
			return;
		}
		writeStopTriggered(symbol, orderState.side(), triggerPrice, orderState.stopPrice(), orderState.mode(),
				orderState.orderId());
		activeOrders.remove(symbol);
		executorFor(orderState.mode()).cancelStopLoss(symbol, orderState.orderId());
		if (triggerHandler != null) {
			triggerHandler.onStopLossTriggered(new StopLossTrigger(
					symbol,
					orderState.side(),
					triggerPrice,
					orderState.stopPrice(),
					orderState.orderId(),
					orderState.mode()));
		}
	}

	public double currentStopTriggerPrice(String symbol) {
		Double mark = lastMarkPrice.get(symbol);
		if (mark != null && Double.isFinite(mark)) {
			return mark;
		}
		Double last = lastPrice.get(symbol);
		return last != null && Double.isFinite(last) ? last : Double.NaN;
	}

	public void onPositionClosed(String symbol) {
		if (symbol == null) {
			return;
		}
		StopLossOrderState state = activeOrders.remove(symbol);
		if (state != null) {
			executorFor(state.mode()).cancelStopLoss(symbol, state.orderId());
		}
	}

	StopLossOrderState activeOrderForTest(String symbol) {
		return activeOrders.get(symbol);
	}

	private StopLossOrderExecutor executorFor(StopLossMode mode) {
		return mode == StopLossMode.LIVE ? liveExecutor : paperExecutor;
	}

	private StopLossMode resolveMode() {
		StopLossProperties stopLoss = strategyProperties.stopLoss();
		StopLossMode mode = stopLoss == null ? null : stopLoss.mode();
		return mode == null ? StopLossMode.PAPER : mode;
	}

	private double resolveStopLossPct() {
		if (strategyProperties.stopLossPct() > 0) {
			return strategyProperties.stopLossPct();
		}
		BigDecimal stopLossBps = strategyProperties.stopLossBps();
		if (stopLossBps == null) {
			return 0.0;
		}
		return stopLossBps.doubleValue() / 10000.0;
	}

	private BigDecimal resolveTickSize(String symbol) {
		BinanceFuturesOrderClient.SymbolFilters filters = symbol == null ? null : symbolFilterService.getFilters(symbol);
		if (filters != null && filters.tickSize() != null) {
			return filters.tickSize();
		}
		return strategyProperties.priceTick();
	}

	private double roundStopPrice(double price, BigDecimal tickSize, CtiDirection side) {
		if (tickSize == null || tickSize.signum() <= 0 || !Double.isFinite(price)) {
			return price;
		}
		BigDecimal value = BigDecimal.valueOf(price);
		BigDecimal divided = value.divide(tickSize, 0,
				side == CtiDirection.SHORT ? java.math.RoundingMode.CEILING : java.math.RoundingMode.FLOOR);
		return divided.multiply(tickSize, MathContext.DECIMAL64).doubleValue();
	}

	private void writeOrderCreated(String symbol, CtiDirection side, double entryAvgPrice, double slPct,
			double stopPriceRaw, double stopPriceRounded, BigDecimal tickSize, StopLossMode mode, String orderId) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("event", "SL_ORDER_CREATED");
		payload.put("decisionTime", formatTimestamp(System.currentTimeMillis()));
		payload.put("symbol", symbol);
		payload.put("side", side == null ? "NA" : side.name());
		payload.put("entryAvgPrice", entryAvgPrice);
		payload.put("slPct", slPct);
		payload.put("stopPriceRaw", stopPriceRaw);
		payload.put("stopPriceRounded", stopPriceRounded);
		payload.put("tickSize", tickSize == null ? "NA" : tickSize.stripTrailingZeros().toPlainString());
		payload.put("mode", mode == null ? "NA" : mode.name());
		payload.put("orderId", orderId == null ? "NA" : orderId);
		appendJsonLine(symbol, payload);
	}

	private void writeStopTriggered(String symbol, CtiDirection side, double triggerPrice, double stopPriceRounded,
			StopLossMode mode, String orderId) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("event", "SL_TRIGGERED");
		payload.put("decisionTime", formatTimestamp(System.currentTimeMillis()));
		payload.put("symbol", symbol);
		payload.put("side", side == null ? "NA" : side.name());
		payload.put("triggerPrice", triggerPrice);
		payload.put("stopPriceRounded", stopPriceRounded);
		payload.put("filledPrice", stopPriceRounded);
		payload.put("mode", mode == null ? "NA" : mode.name());
		payload.put("orderId", orderId == null ? "NA" : orderId);
		appendJsonLine(symbol, payload);
	}

	private void appendJsonLine(String symbol, ObjectNode payload) {
		if (symbol == null) {
			return;
		}
		try {
			Files.createDirectories(SIGNAL_OUTPUT_DIR);
			String fileName = symbol + "-" + formatDateYmd(System.currentTimeMillis()) + ".jsonl";
			Path path = SIGNAL_OUTPUT_DIR.resolve(fileName);
			String jsonLine = objectMapper.writeValueAsString(payload) + "\n";
			Files.write(path, jsonLine.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// ignore logging errors
		}
	}

	private String formatDateYmd(long timestampMs) {
		return Instant.ofEpochMilli(timestampMs)
				.atZone(ZoneId.systemDefault())
				.format(DATE_FORMAT);
	}

	private String formatTimestamp(long timestampMs) {
		return Instant.ofEpochMilli(timestampMs)
				.atZone(ZoneId.systemDefault())
				.format(TS_FORMAT);
	}

	record StopLossOrderState(String orderId, CtiDirection side, double stopPrice, StopLossMode mode) {
		StopLossOrderState {
			Objects.requireNonNull(side, "side");
		}
	}
}
