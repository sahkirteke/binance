package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CtiLbStrategyTest {

	@Test
	void isFiniteBetweenIsInclusive() {
		assertThat(CtiLbStrategy.isFiniteBetween(0.0114, 0.0114, 0.0130)).isTrue();
		assertThat(CtiLbStrategy.isFiniteBetween(0.0130, 0.0114, 0.0130)).isTrue();
		assertThat(CtiLbStrategy.isFiniteBetween(0.0113, 0.0114, 0.0130)).isFalse();
		assertThat(CtiLbStrategy.isFiniteBetween(Double.NaN, 0.0114, 0.0130)).isFalse();
	}

	@Test
	void isFiniteAtLeastIsInclusive() {
		assertThat(CtiLbStrategy.isFiniteAtLeast(0.004, 0.004)).isTrue();
		assertThat(CtiLbStrategy.isFiniteAtLeast(0.0039, 0.004)).isFalse();
		assertThat(CtiLbStrategy.isFiniteAtLeast(Double.NaN, 0.004)).isFalse();
	}
}
