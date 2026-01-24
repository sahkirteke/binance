package com.binance.wslogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WsSnapshotLoggerProperties.class)
@ConditionalOnProperty(prefix = "wsSnapshotLogger", name = "enabled", havingValue = "true")
public class WsSnapshotLoggerConfiguration {

    @Bean
    public JsonlWriter jsonlWriter(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        return new JsonlWriter(properties, objectMapper);
    }

    @Bean
    public WsMarketDataClient wsMarketDataClient(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        return new WsMarketDataClient(properties, objectMapper);
    }

    @Bean
    public SnapshotScheduler snapshotScheduler(WsSnapshotLoggerProperties properties, JsonlWriter writer) {
        return new SnapshotScheduler(properties, writer);
    }

    @Bean
    public WsSnapshotLoggerModuleStarter wsSnapshotLoggerModuleStarter(
            WsSnapshotLoggerProperties properties,
            WsMarketDataClient wsMarketDataClient,
            SnapshotScheduler snapshotScheduler,
            JsonlWriter writer) {
        return new WsSnapshotLoggerModuleStarter(properties, wsMarketDataClient, snapshotScheduler, writer);
    }
}
