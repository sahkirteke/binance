package com.binance.strategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.binance.market.dto.DepthUpdateEvent;
import com.binance.market.dto.OrderBookDepthResponse;

public class LocalOrderBook {

	private final NavigableMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
	private final NavigableMap<BigDecimal, BigDecimal> asks = new TreeMap<>();
	private long lastUpdateId = -1;

	public synchronized void reset() {
		bids.clear();
		asks.clear();
		lastUpdateId = -1;
	}

	public synchronized void applySnapshot(OrderBookDepthResponse snapshot) {
		bids.clear();
		asks.clear();
		lastUpdateId = snapshot.lastUpdateId();
		applyLevels(bids, snapshot.bids());
		applyLevels(asks, snapshot.asks());
	}

	public synchronized DepthDelta applyDepthUpdate(DepthUpdateEvent update) {
		BigDecimal added = BigDecimal.ZERO;
		BigDecimal removed = BigDecimal.ZERO;
		DepthDelta bidDelta = applyUpdateLevels(bids, update.bids());
		DepthDelta askDelta = applyUpdateLevels(asks, update.asks());
		added = added.add(bidDelta.added()).add(askDelta.added());
		removed = removed.add(bidDelta.removed()).add(askDelta.removed());
		lastUpdateId = update.finalUpdateId();
		return new DepthDelta(added, removed);
	}

	public synchronized long lastUpdateId() {
		return lastUpdateId;
	}

	public synchronized Optional<BigDecimal> bestBid() {
		return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
	}

	public synchronized Optional<BigDecimal> bestAsk() {
		return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
	}

	public synchronized OrderBookView view(int depthLevels) {
		List<BigDecimal> bidQtys = new ArrayList<>();
		List<BigDecimal> askQtys = new ArrayList<>();
		bids.values().stream().limit(depthLevels).forEach(bidQtys::add);
		asks.values().stream().limit(depthLevels).forEach(askQtys::add);
		BigDecimal bestBid = bids.isEmpty() ? null : bids.firstKey();
		BigDecimal bestAsk = asks.isEmpty() ? null : asks.firstKey();
		return new OrderBookView(bestBid, bestAsk, bidQtys, askQtys);
	}

	private void applyLevels(NavigableMap<BigDecimal, BigDecimal> book, List<List<String>> levels) {
		if (levels == null) {
			return;
		}
		for (List<String> level : levels) {
			if (level.size() < 2) {
				continue;
			}
			BigDecimal price = new BigDecimal(level.get(0));
			BigDecimal qty = new BigDecimal(level.get(1));
			if (qty.signum() == 0) {
				book.remove(price);
			} else {
				book.put(price, qty);
			}
		}
	}

	private DepthDelta applyUpdateLevels(NavigableMap<BigDecimal, BigDecimal> book, List<List<String>> levels) {
		BigDecimal added = BigDecimal.ZERO;
		BigDecimal removed = BigDecimal.ZERO;
		if (levels == null) {
			return new DepthDelta(added, removed);
		}
		for (List<String> level : levels) {
			if (level.size() < 2) {
				continue;
			}
			BigDecimal price = new BigDecimal(level.get(0));
			BigDecimal newQty = new BigDecimal(level.get(1));
			BigDecimal oldQty = book.getOrDefault(price, BigDecimal.ZERO);
			BigDecimal delta = newQty.subtract(oldQty);
			if (delta.signum() > 0) {
				added = added.add(delta);
			} else if (delta.signum() < 0) {
				removed = removed.add(delta.abs());
			}
			if (newQty.signum() == 0) {
				book.remove(price);
			} else {
				book.put(price, newQty);
			}
		}
		return new DepthDelta(added, removed);
	}

	public record OrderBookView(BigDecimal bestBid, BigDecimal bestAsk, List<BigDecimal> bidQtys,
			List<BigDecimal> askQtys) {
	}

	public record DepthDelta(BigDecimal added, BigDecimal removed) {
	}
}
