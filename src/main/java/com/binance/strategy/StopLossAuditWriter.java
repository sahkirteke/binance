package com.binance.strategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class StopLossAuditWriter {
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
			.withZone(ZoneId.systemDefault());
	private final Path baseDir;
	private final ObjectMapper objectMapper;
	private final Map<Path, Object> locks = new ConcurrentHashMap<>();

	public StopLossAuditWriter(Path baseDir, ObjectMapper objectMapper) {
		this.baseDir = baseDir;
		this.objectMapper = objectMapper;
	}

	public void recordOrderUpdate(OrderTracker.OrderUpdate update) {
		if (update == null || update.symbol() == null) {
			return;
		}
		String date = DATE_FORMAT.format(Instant.ofEpochMilli(update.eventTime()));
		Path path = baseDir.resolve("orders").resolve(update.symbol() + "-" + date + ".jsonl");
		ObjectNode node = objectMapper.createObjectNode();
		node.put("symbol", update.symbol());
		node.put("orderId", update.orderId());
		node.put("status", update.status());
		node.put("execType", update.execType());
		node.put("side", update.side());
		node.put("positionSide", update.positionSide());
		node.put("clientOrderId", update.clientOrderId());
		node.put("reduceOnly", update.reduceOnly());
		node.put("closePosition", update.closePosition());
		node.put("eventTime", update.eventTime());
		node.put("orderType", update.orderType());
		node.put("stopPrice", update.stopPrice());
		node.put("price", update.price());
		node.put("origQty", update.origQty());
		node.put("executedQty", update.executedQty());
		node.put("avgPrice", update.avgPrice());
		appendJsonLine(path, node);
	}

	public void recordTrailingUpdate(String symbol, String reason, long eventTime,
			Double oldStopPrice, Double newStopPrice, Double trailWidth, Double profitStop,
			Double hardStop, Double recoveryStop) {
		if (symbol == null) {
			return;
		}
		String date = DATE_FORMAT.format(Instant.ofEpochMilli(eventTime));
		Path path = baseDir.resolve("trailing").resolve(symbol + "-" + date + ".jsonl");
		ObjectNode node = objectMapper.createObjectNode();
		node.put("symbol", symbol);
		node.put("eventTime", eventTime);
		node.put("reason", reason == null ? "NA" : reason);
		putNullable(node, "oldStopPrice", oldStopPrice);
		putNullable(node, "newStopPrice", newStopPrice);
		putNullable(node, "trailWidth", trailWidth);
		putNullable(node, "profitStop", profitStop);
		putNullable(node, "hardStop", hardStop);
		putNullable(node, "recoveryStop", recoveryStop);
		appendJsonLine(path, node);
	}

	public void recordTradeTruth(TradeTruthRecord record) {
		if (record == null || record.symbol() == null) {
			return;
		}
		Path jsonPath = baseDir.resolve("trades_truth.json");
		Path csvPath = baseDir.resolve("trades_truth.csv");
		ObjectNode node = objectMapper.createObjectNode();
		node.put("symbol", record.symbol());
		node.put("side", record.side());
		node.put("entryTime", record.entryTime());
		node.put("exitTime", record.exitTime());
		node.put("entryQty", record.entryQty());
		node.put("exitQty", record.exitQty());
		node.put("entryAvgPrice", record.entryAvgPrice());
		node.put("exitAvgPrice", record.exitAvgPrice());
		node.put("exitReason", record.exitReason());
		node.put("expectedSlPct", record.expectedSlPct());
		node.put("expectedStopPrice", record.expectedStopPrice());
		node.put("stopPriceUsed", record.stopPriceUsed());
		node.put("actualMovePct", record.actualMovePct());
		node.put("realizedPnl", record.realizedPnl());
		node.put("recomputedPnl", record.recomputedPnl());
		node.put("deltaPnl", record.deltaPnl());
		node.put("mismatchReason", record.mismatchReason());
		appendJsonLine(jsonPath, node);
		appendCsvLine(csvPath, record);
	}

	public void recordSummary(ObjectNode summary) {
		if (summary == null) {
			return;
		}
		Path summaryPath = baseDir.resolve("summary.json");
		synchronized (lockFor(summaryPath)) {
			try {
				Files.createDirectories(summaryPath.getParent());
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
			} catch (IOException ignored) {
				// Best effort audit output.
			}
		}
	}

	private void appendJsonLine(Path path, ObjectNode node) {
		synchronized (lockFor(path)) {
			try {
				Files.createDirectories(path.getParent());
				String line = objectMapper.writeValueAsString(node) + "\n";
				Files.write(path, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			} catch (IOException ignored) {
				// Best effort audit output.
			}
		}
	}

	private void appendCsvLine(Path path, TradeTruthRecord record) {
		synchronized (lockFor(path)) {
			try {
				Files.createDirectories(path.getParent());
				if (!Files.exists(path)) {
					String header = String.join(",",
							"symbol", "side", "entryTime", "exitTime", "entryQty", "exitQty", "entryAvgPrice",
							"exitAvgPrice", "exitReason", "expectedSlPct", "expectedStopPrice", "stopPriceUsed",
							"actualMovePct", "realizedPnl", "recomputedPnl", "deltaPnl", "mismatchReason") + "\n";
					Files.write(path, header.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
							StandardOpenOption.APPEND);
				}
				String line = String.join(",",
						record.symbol(),
						record.side(),
						String.valueOf(record.entryTime()),
						String.valueOf(record.exitTime()),
						record.entryQty(),
						record.exitQty(),
						record.entryAvgPrice(),
						record.exitAvgPrice(),
						record.exitReason(),
						record.expectedSlPct(),
						record.expectedStopPrice(),
						record.stopPriceUsed(),
						record.actualMovePct(),
						record.realizedPnl(),
						record.recomputedPnl(),
						record.deltaPnl(),
						record.mismatchReason()) + "\n";
				Files.write(path, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			} catch (IOException ignored) {
				// Best effort audit output.
			}
		}
	}

	private void putNullable(ObjectNode node, String field, Double value) {
		if (value == null || !Double.isFinite(value)) {
			node.putNull(field);
		} else {
			node.put(field, value);
		}
	}

	private Object lockFor(Path path) {
		return locks.computeIfAbsent(path, ignored -> new Object());
	}

	public record TradeTruthRecord(
			String symbol,
			String side,
			long entryTime,
			long exitTime,
			String entryQty,
			String exitQty,
			String entryAvgPrice,
			String exitAvgPrice,
			String exitReason,
			String expectedSlPct,
			String expectedStopPrice,
			String stopPriceUsed,
			String actualMovePct,
			String realizedPnl,
			String recomputedPnl,
			String deltaPnl,
			String mismatchReason) {
	}
}
