package com.binance.strategy;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

public class MidPriceHistory {
	private final Deque<Entry> entries = new ArrayDeque<>();

	public synchronized void add(BigDecimal mid, long timestamp) {
		if (mid == null) {
			return;
		}
		entries.addLast(new Entry(timestamp, mid));
		prune(timestamp, 2000);
	}

	public synchronized BigDecimal valueAtOrBefore(long cutoffTimestamp) {
		BigDecimal candidate = null;
		for (Entry entry : entries) {
			if (entry.timestamp <= cutoffTimestamp) {
				candidate = entry.value;
			} else {
				break;
			}
		}
		return candidate;
	}

	private void prune(long now, long maxAgeMillis) {
		while (!entries.isEmpty() && now - entries.peekFirst().timestamp > maxAgeMillis) {
			entries.removeFirst();
		}
	}

	private record Entry(long timestamp, BigDecimal value) {
	}
}
