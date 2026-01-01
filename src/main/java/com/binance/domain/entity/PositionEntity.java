package com.binance.domain.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.binance.domain.enums.PositionSide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "positions")
public class PositionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PositionSide side;

	@Column(nullable = false, precision = 19, scale = 8)
	private BigDecimal quantity;

	@Column(nullable = false, precision = 19, scale = 8)
	private BigDecimal entryPrice;

	@Column(precision = 19, scale = 8)
	private BigDecimal exitPrice;

	@Column(nullable = false)
	private OffsetDateTime openedAt = OffsetDateTime.now();

	@Column
	private OffsetDateTime closedAt;

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

	public PositionSide getSide() {
		return side;
	}

	public void setSide(PositionSide side) {
		this.side = side;
	}

	public BigDecimal getQuantity() {
		return quantity;
	}

	public void setQuantity(BigDecimal quantity) {
		this.quantity = quantity;
	}

	public BigDecimal getEntryPrice() {
		return entryPrice;
	}

	public void setEntryPrice(BigDecimal entryPrice) {
		this.entryPrice = entryPrice;
	}

	public BigDecimal getExitPrice() {
		return exitPrice;
	}

	public void setExitPrice(BigDecimal exitPrice) {
		this.exitPrice = exitPrice;
	}

	public OffsetDateTime getOpenedAt() {
		return openedAt;
	}

	public void setOpenedAt(OffsetDateTime openedAt) {
		this.openedAt = openedAt;
	}

	public OffsetDateTime getClosedAt() {
		return closedAt;
	}

	public void setClosedAt(OffsetDateTime closedAt) {
		this.closedAt = closedAt;
	}
}
