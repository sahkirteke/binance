package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StopLossVerifierTest {

	@Test
	void recordTracksPassRateAndFailures() {
		StopLossVerifier verifier = new StopLossVerifier(0.0002, 0.95, 20, 5);
		for (int i = 0; i < 19; i++) {
			verifier.record("SYM", CtiDirection.LONG, 0.002, 0.002, 100.0, 99.8, 99.8);
		}
		StopLossVerifier.VerificationResult result = verifier.record(
				"SYM",
				CtiDirection.LONG,
				0.002,
				0.0015,
				100.0,
				99.85,
				99.8);
		assertThat(result.total()).isEqualTo(20);
		assertThat(result.pass()).isEqualTo(19);
		assertThat(result.passRate()).isGreaterThanOrEqualTo(0.95);
		assertThat(result.failures()).hasSize(1);
		assertThat(verifier.shouldAlert(result)).isFalse();
	}
}
