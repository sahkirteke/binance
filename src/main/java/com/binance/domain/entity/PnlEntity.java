package com.binance.domain.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pnl_history")
public class PnlEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String symbol;

	@Column(nullable = false, precision = 19, scale = 8)
	private BigDecimal realizedPnl;

	@Column(nullable = false)
	private OffsetDateTime recordedAt = OffsetDateTime.now();

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getSymbol() {
		return symbol;
	}

	public void setSymbol(String symbol) {
		this.symbol = symbol;
	}

	public BigDecimal getRealizedPnl() {
		return realizedPnl;
	}

	public void setRealizedPnl(BigDecimal realizedPnl) {
		this.realizedPnl = realizedPnl;
	}

	public OffsetDateTime getRecordedAt() {
		return recordedAt;
	}

	public void setRecordedAt(OffsetDateTime recordedAt) {
		this.recordedAt = recordedAt;
	}
}
