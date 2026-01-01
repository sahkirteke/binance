package com.binance.api.dto;

import java.time.OffsetDateTime;

public record UserDto(
		Long id,
		String username,
		OffsetDateTime createdAt) {
}
