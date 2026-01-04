package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StrategyLogV1Test {

	@Test
	void decisionLineHasStablePrefixOrderAndPlainDecimals() {
		StrategyLogV1.DecisionLogDto dto = new StrategyLogV1.DecisionLogDto(
				"BTCUSDT",
				1700000000000L,
				43000.0,
				0.12,
				-0.05,
				0.000507,
				true,
				"ADX5M>20",
				true,
				true,
				2,
				21,
				16,
				14,
				false,
				"OK",
				1,
				-1,
				0,
				1,
				1,
				1,
				2.0,
				CtiDirection.LONG,
				CtiDirection.LONG,
				CtiDirection.LONG,
				2,
				2,
				CtiDirection.LONG,
				"SCORE_RULES",
				CtiDirection.LONG,
				1700000000000L,
				java.math.BigDecimal.ONE,
				"FLIP_TO_LONG",
				"OK",
				"FLAT",
				java.math.BigDecimal.ZERO,
				java.math.BigDecimal.ONE,
				java.math.BigDecimal.TEN,
				java.math.BigDecimal.TEN,
				0,
				1L,
				1L,
				1L);

		String line = StrategyLogLineBuilder.buildDecisionLine(dto);
		assertTrue(line.startsWith("EVENT=DECISION strategy=CTI_SCORE symbol=BTCUSDT tf=1m closeTime="));
		assertFalse(line.contains("E-"));
		assertFalse(line.contains("E+"));

		int idxSymbol = line.indexOf(" symbol=");
		int idxClose = line.indexOf(" close=");
		int idxAction = line.indexOf(" action=");
		assertTrue(idxSymbol < idxClose && idxClose < idxAction);
	}
}
