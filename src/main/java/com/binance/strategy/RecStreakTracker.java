package com.binance.strategy;

public class RecStreakTracker {

	private CtiDirection lastRec = CtiDirection.NEUTRAL;
	private int streakCount;
	private CtiDirection recPending = CtiDirection.NEUTRAL;
	private long recFirstSeenAtMs;
	private java.math.BigDecimal recFirstSeenPrice;

	public RecUpdate update(CtiDirection rec, long closeTimeMs, java.math.BigDecimal closePrice, int confirmBars) {
		CtiDirection safeRec = rec == null ? CtiDirection.NEUTRAL : rec;
		boolean missedMove = false;
		boolean confirmHit = false;
		CtiDirection missedPending = CtiDirection.NEUTRAL;
		long missedFirstSeenAtMs = 0L;
		java.math.BigDecimal missedFirstSeenPrice = null;
		long confirmFirstSeenAtMs = 0L;
		java.math.BigDecimal confirmFirstSeenPrice = null;
		int streakBeforeReset = streakCount;
		if (safeRec == CtiDirection.NEUTRAL) {
			if (recPending != CtiDirection.NEUTRAL && streakCount < confirmBars) {
				missedMove = true;
				missedPending = recPending;
				missedFirstSeenAtMs = recFirstSeenAtMs;
				missedFirstSeenPrice = recFirstSeenPrice;
			}
			reset();
			return new RecUpdate(lastRec, streakCount, recPending, recFirstSeenAtMs, recFirstSeenPrice, missedMove,
					confirmHit, missedPending, missedFirstSeenAtMs, missedFirstSeenPrice, streakBeforeReset,
					confirmFirstSeenAtMs, confirmFirstSeenPrice);
		}

		if (safeRec == lastRec) {
			streakCount++;
		} else {
			if (recPending != CtiDirection.NEUTRAL && streakCount < confirmBars) {
				missedMove = true;
				missedPending = recPending;
				missedFirstSeenAtMs = recFirstSeenAtMs;
				missedFirstSeenPrice = recFirstSeenPrice;
			}
			lastRec = safeRec;
			streakCount = 1;
			recPending = safeRec;
			recFirstSeenAtMs = closeTimeMs;
			recFirstSeenPrice = closePrice;
		}

		if (recPending != CtiDirection.NEUTRAL && streakCount >= confirmBars && safeRec == lastRec) {
			confirmHit = true;
			confirmFirstSeenAtMs = recFirstSeenAtMs;
			confirmFirstSeenPrice = recFirstSeenPrice;
			recPending = CtiDirection.NEUTRAL;
			recFirstSeenAtMs = 0L;
			recFirstSeenPrice = null;
		}

		return new RecUpdate(lastRec, streakCount, recPending, recFirstSeenAtMs, recFirstSeenPrice, missedMove,
				confirmHit, missedPending, missedFirstSeenAtMs, missedFirstSeenPrice, streakBeforeReset,
				confirmFirstSeenAtMs, confirmFirstSeenPrice);
	}

	public void reset() {
		lastRec = CtiDirection.NEUTRAL;
		streakCount = 0;
		recPending = CtiDirection.NEUTRAL;
		recFirstSeenAtMs = 0L;
		recFirstSeenPrice = null;
	}

	public CtiDirection lastRec() {
		return lastRec;
	}

	public int streakCount() {
		return streakCount;
	}

	public CtiDirection recPending() {
		return recPending;
	}

	public long recFirstSeenAtMs() {
		return recFirstSeenAtMs;
	}

	public java.math.BigDecimal recFirstSeenPrice() {
		return recFirstSeenPrice;
	}

	public record RecUpdate(
			CtiDirection lastRec,
			int streakCount,
			CtiDirection recPending,
			long recFirstSeenAtMs,
			java.math.BigDecimal recFirstSeenPrice,
			boolean missedMove,
			boolean confirmHit,
			CtiDirection missedPending,
			long missedFirstSeenAtMs,
			java.math.BigDecimal missedFirstSeenPrice,
			int streakBeforeReset,
			long confirmFirstSeenAtMs,
			java.math.BigDecimal confirmFirstSeenPrice) {
	}
}
