package com.binance.wslogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WsSnapshotLoggerProperties.class)
public class WsSnapshotLoggerConfiguration {

    @Bean
    public MarketDataHub marketDataHub() {
        return new MarketDataHub();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "enabled", havingValue = "true")
    public JsonlWriter jsonlWriter(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        return new JsonlWriter(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "mode", havingValue = "existing")
    public BookTickerStreamWatcher bookTickerStreamWatcher(
            WsSnapshotLoggerProperties properties,
            ObjectMapper objectMapper,
            MarketDataHub marketDataHub) {
        return new BookTickerStreamWatcher(properties, objectMapper, marketDataHub);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "mode", havingValue = "existing")
    public AggTradeStreamWatcher aggTradeStreamWatcher(
            WsSnapshotLoggerProperties properties,
            ObjectMapper objectMapper,
            MarketDataHub marketDataHub) {
        return new AggTradeStreamWatcher(properties, objectMapper, marketDataHub);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "mode", havingValue = "standalone")
    public WsMarketDataClient wsMarketDataClient(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        return new WsMarketDataClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "enabled", havingValue = "true")
    public SnapshotScheduler snapshotScheduler(WsSnapshotLoggerProperties properties, JsonlWriter writer) {
        return new SnapshotScheduler(properties, writer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ws-snapshot-logger", name = "enabled", havingValue = "true")
    public WsSnapshotLoggerModuleStarter wsSnapshotLoggerModuleStarter(
            WsSnapshotLoggerProperties properties,
            MarketDataHub marketDataHub,
            org.springframework.beans.factory.ObjectProvider<WsMarketDataClient> wsMarketDataClient,
            SnapshotScheduler snapshotScheduler,
            JsonlWriter writer) {
        return new WsSnapshotLoggerModuleStarter(properties, marketDataHub, wsMarketDataClient, snapshotScheduler, writer);
    }
}
