package com.llm.gateway.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the two logical Redis roles. */
@ConfigurationProperties(prefix = "gateway.redis")
public class GatewayRedisProperties {

    public enum Mode {
        STANDALONE,
        SENTINEL,
        CLUSTER
    }

    private String namespace = "llmgw";
    private String environment = "local";
    private boolean healthEnabled;
    private int cacheMaxValueBytes = 524_288;
    private Circuit circuit = new Circuit();
    private Endpoint control = new Endpoint();
    private Endpoint cache = new Endpoint();

    public static class Circuit {
        private int failureThreshold = 3;
        private Duration openDuration = Duration.ofSeconds(2);

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int value) {
            this.failureThreshold = value;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration value) {
            this.openDuration = value;
        }
    }

    public static class Endpoint {
        private Mode mode = Mode.STANDALONE;
        private List<String> nodes = new ArrayList<>(List.of("localhost:6379"));
        private String master;
        private String username;
        private String password;
        private int database;
        private int maxRedirects = 5;
        private boolean ssl;
        private Duration commandTimeout = Duration.ofMillis(500);

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode value) {
            this.mode = value;
        }

        public List<String> getNodes() {
            return nodes;
        }

        public void setNodes(List<String> value) {
            this.nodes = value;
        }

        public String getMaster() {
            return master;
        }

        public void setMaster(String value) {
            this.master = value;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String value) {
            this.username = value;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String value) {
            this.password = value;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int value) {
            this.database = value;
        }

        public int getMaxRedirects() {
            return maxRedirects;
        }

        public void setMaxRedirects(int value) {
            this.maxRedirects = value;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean value) {
            this.ssl = value;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration value) {
            this.commandTimeout = value;
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String value) {
        this.namespace = value;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String value) {
        this.environment = value;
    }

    public boolean isHealthEnabled() {
        return healthEnabled;
    }

    public void setHealthEnabled(boolean value) {
        this.healthEnabled = value;
    }

    public int getCacheMaxValueBytes() {
        return cacheMaxValueBytes;
    }

    public void setCacheMaxValueBytes(int value) {
        this.cacheMaxValueBytes = value;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    public void setCircuit(Circuit value) {
        this.circuit = value;
    }

    public Endpoint getControl() {
        return control;
    }

    public void setControl(Endpoint value) {
        this.control = value;
    }

    public Endpoint getCache() {
        return cache;
    }

    public void setCache(Endpoint value) {
        this.cache = value;
    }
}
