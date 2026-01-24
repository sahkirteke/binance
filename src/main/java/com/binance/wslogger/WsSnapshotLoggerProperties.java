package com.binance.wslogger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ws-snapshot-logger")
public class WsSnapshotLoggerProperties {

    private boolean enabled = false;
    private List<String> symbols = new ArrayList<>();
    private int logEverySec = 3;
    private int warmupSamples = 20;
    private int rollingShort = 20;
    private int rollingLong = 60;
    private Path baseDir = Path.of("/opt/binance-bot/signals/ws");
    private String wsBaseUrl = "wss://fstream.binance.com/stream";
    private boolean clientPingEnabled = false;
    private Duration pingInterval = Duration.ofSeconds(30);
    private Duration pongTimeout = Duration.ofSeconds(45);
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration responseTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    private Duration reconnectBackoffMin = Duration.ofSeconds(2);
    private Duration reconnectBackoffMax = Duration.ofSeconds(60);
    private Duration reconnectGlobalSpacing = Duration.ofMillis(500);
    private int maxReconnectAttempts = 1000;
    private Duration banCooldown = Duration.ofMinutes(5);
    private int banDisconnectThreshold = 3;
    private int stableConnectionSeconds = 120;
    private int writerQueueCapacity = 10000;
    private Duration writerFlushInterval = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }

    public int getLogEverySec() {
        return logEverySec;
    }

    public void setLogEverySec(int logEverySec) {
        this.logEverySec = logEverySec;
    }

    public int getWarmupSamples() {
        return warmupSamples;
    }

    public void setWarmupSamples(int warmupSamples) {
        this.warmupSamples = warmupSamples;
    }

    public int getRollingShort() {
        return rollingShort;
    }

    public void setRollingShort(int rollingShort) {
        this.rollingShort = rollingShort;
    }

    public int getRollingLong() {
        return rollingLong;
    }

    public void setRollingLong(int rollingLong) {
        this.rollingLong = rollingLong;
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }

    public String getWsBaseUrl() {
        return wsBaseUrl;
    }

    public void setWsBaseUrl(String wsBaseUrl) {
        this.wsBaseUrl = wsBaseUrl;
    }

    public boolean isClientPingEnabled() {
        return clientPingEnabled;
    }

    public void setClientPingEnabled(boolean clientPingEnabled) {
        this.clientPingEnabled = clientPingEnabled;
    }

    public Duration getPingInterval() {
        return pingInterval;
    }

    public void setPingInterval(Duration pingInterval) {
        this.pingInterval = pingInterval;
    }

    public Duration getPongTimeout() {
        return pongTimeout;
    }

    public void setPongTimeout(Duration pongTimeout) {
        this.pongTimeout = pongTimeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getReconnectBackoffMin() {
        return reconnectBackoffMin;
    }

    public void setReconnectBackoffMin(Duration reconnectBackoffMin) {
        this.reconnectBackoffMin = reconnectBackoffMin;
    }

    public Duration getReconnectBackoffMax() {
        return reconnectBackoffMax;
    }

    public void setReconnectBackoffMax(Duration reconnectBackoffMax) {
        this.reconnectBackoffMax = reconnectBackoffMax;
    }

    public Duration getReconnectGlobalSpacing() {
        return reconnectGlobalSpacing;
    }

    public void setReconnectGlobalSpacing(Duration reconnectGlobalSpacing) {
        this.reconnectGlobalSpacing = reconnectGlobalSpacing;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public void setMaxReconnectAttempts(int maxReconnectAttempts) {
        this.maxReconnectAttempts = maxReconnectAttempts;
    }

    public Duration getBanCooldown() {
        return banCooldown;
    }

    public void setBanCooldown(Duration banCooldown) {
        this.banCooldown = banCooldown;
    }

    public int getBanDisconnectThreshold() {
        return banDisconnectThreshold;
    }

    public void setBanDisconnectThreshold(int banDisconnectThreshold) {
        this.banDisconnectThreshold = banDisconnectThreshold;
    }

    public int getStableConnectionSeconds() {
        return stableConnectionSeconds;
    }

    public void setStableConnectionSeconds(int stableConnectionSeconds) {
        this.stableConnectionSeconds = stableConnectionSeconds;
    }

    public int getWriterQueueCapacity() {
        return writerQueueCapacity;
    }

    public void setWriterQueueCapacity(int writerQueueCapacity) {
        this.writerQueueCapacity = writerQueueCapacity;
    }

    public Duration getWriterFlushInterval() {
        return writerFlushInterval;
    }

    public void setWriterFlushInterval(Duration writerFlushInterval) {
        this.writerFlushInterval = writerFlushInterval;
    }
}
