package com.binance.wslogger;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

public class MarketDataHub {

    private final Sinks.Many<MarketEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

    public void publish(MarketEvent event) {
        sink.tryEmitNext(event);
    }

    public Flux<MarketEvent> flux() {
        return sink.asFlux();
    }
}
