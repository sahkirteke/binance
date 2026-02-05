package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CtiLbDecisionEngineTest {

	@Test
	void resolveStopPriceUsesBpsFraction() {
		BigDecimal stopLossBps = BigDecimal.valueOf(20);
		double entry = 100.0;
		assertThat(CtiLbDecisionEngine.resolveStopPrice(CtiDirection.LONG, entry, stopLossBps))
				.isCloseTo(99.8, within(1e-6));
		assertThat(CtiLbDecisionEngine.resolveStopPrice(CtiDirection.SHORT, entry, stopLossBps))
				.isCloseTo(100.2, within(1e-6));
	}
}
