package com.binance.api.dto;

import java.math.BigDecimal;

import com.binance.domain.enums.OrderSide;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderRequestDto(
		@NotBlank String symbol,
		@NotNull OrderSide side,
		@NotNull @Positive BigDecimal quantity) {
}
