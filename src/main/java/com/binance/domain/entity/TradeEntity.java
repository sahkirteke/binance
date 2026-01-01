package com.binance.domain.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.binance.domain.enums.OrderSide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "trades")
public class TradeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long orderId;

	@Column(nullable = false)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderSide side;

	@Column(nullable = false, precision = 19, scale = 8)
	private BigDecimal quantity;

	@Column(nullable = false, precision = 19, scale = 8)
	private BigDecimal price;

	@Column(precision = 19, scale = 8)
	private BigDecimal realizedPnl;

	@Column(nullable = false)
	private OffsetDateTime executedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public String getSymbol() {
		return symbol;
	}

	public void setSymbol(String symbol) {
		this.symbol = symbol;
	}

	public OrderSide getSide() {
		return side;
	}

	public void setSide(OrderSide side) {
		this.side = side;
	}

	public BigDecimal getQuantity() {
		return quantity;
	}

	public void setQuantity(BigDecimal quantity) {
		this.quantity = quantity;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public BigDecimal getRealizedPnl() {
		return realizedPnl;
	}

	public void setRealizedPnl(BigDecimal realizedPnl) {
		this.realizedPnl = realizedPnl;
	}

	public OffsetDateTime getExecutedAt() {
		return executedAt;
	}

	public void setExecutedAt(OffsetDateTime executedAt) {
		this.executedAt = executedAt;
	}
}
