package com.binance.strategy;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

public class RollingSum {
	private final long windowMillis;
	private final Deque<Entry> entries = new ArrayDeque<>();
	private BigDecimal total = BigDecimal.ZERO;

	public RollingSum(long windowMillis) {
		this.windowMillis = windowMillis;
	}

	public synchronized void add(BigDecimal value, long timestamp) {
		if (value == null) {
			return;
		}
		entries.addLast(new Entry(timestamp, value));
		total = total.add(value);
		prune(timestamp);
	}

	public synchronized BigDecimal total(long now) {
		prune(now);
		return total;
	}

	private void prune(long now) {
		while (!entries.isEmpty() && now - entries.peekFirst().timestamp > windowMillis) {
			Entry entry = entries.removeFirst();
			total = total.subtract(entry.value);
		}
	}

	private record Entry(long timestamp, BigDecimal value) {
	}
}
