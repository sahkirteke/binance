package com.binance.wslogger;

public record BookTickerEvent(
        String symbol,
        double bid,
        double ask,
        double bidQty,
        double askQty,
        long eventTimeMs) implements MarketEvent {
}
