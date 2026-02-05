package com.binance.strategy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class StopLossVerifier {
	private final double tolerance;
	private final double minPassRate;
	private final int minSamples;
	private final int maxFailureSamples;
	private int total;
	private int pass;
	private final Deque<String> failureSamples = new ArrayDeque<>();

	public StopLossVerifier(double tolerance, double minPassRate, int minSamples, int maxFailureSamples) {
		this.tolerance = tolerance;
		this.minPassRate = minPassRate;
		this.minSamples = minSamples;
		this.maxFailureSamples = maxFailureSamples;
	}

	public synchronized VerificationResult record(String symbol, CtiDirection side, double expectedSlPct,
			double actualMovePct, double entryPrice, double exitPrice, double stopPriceUse) {
		total += 1;
		boolean ok = Math.abs(actualMovePct - expectedSlPct) <= tolerance;
		if (ok) {
			pass += 1;
		} else {
			String sample = String.format(
					"symbol=%s side=%s entry=%.8f exit=%.8f stop=%.8f expected=%.6f actual=%.6f",
					symbol,
					side == null ? "NA" : side.name(),
					entryPrice,
					exitPrice,
					stopPriceUse,
					expectedSlPct,
					actualMovePct);
			failureSamples.addLast(sample);
			while (failureSamples.size() > maxFailureSamples) {
				failureSamples.removeFirst();
			}
		}
		return snapshot();
	}

	public synchronized VerificationResult snapshot() {
		double passRate = total == 0 ? 1.0 : pass / (double) total;
		List<String> samples = new ArrayList<>(failureSamples);
		return new VerificationResult(total, pass, passRate, Collections.unmodifiableList(samples));
	}

	public boolean shouldAlert(VerificationResult result) {
		return result.total() >= minSamples && result.passRate() < minPassRate;
	}

	public record VerificationResult(int total, int pass, double passRate, List<String> failures) {
	}
}
