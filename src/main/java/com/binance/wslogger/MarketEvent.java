package com.binance.wslogger;

public interface MarketEvent {
    String symbol();

    long eventTimeMs();
}
