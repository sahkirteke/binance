package com.binance.wslogger;

public record MarkPriceEvent(String symbol, double markPrice, long eventTimeMs) implements MarketEvent {
}
