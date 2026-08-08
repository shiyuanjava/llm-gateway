package com.llm.gateway.redis;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/** Creates independent control/cache Redis clients while allowing either to share an endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRedisProperties.class)
public class GatewayRedisConfiguration {

    @Bean(name = {"redisConnectionFactory", "controlRedisConnectionFactory"})
    @Primary
    LettuceConnectionFactory controlRedisConnectionFactory(GatewayRedisProperties properties) {
        return connectionFactory(properties.getControl());
    }

    @Bean(name = "cacheRedisConnectionFactory")
    LettuceConnectionFactory cacheRedisConnectionFactory(GatewayRedisProperties properties) {
        return connectionFactory(properties.getCache());
    }

    @Bean(name = {"stringRedisTemplate", "controlRedisTemplate"})
    @Primary
    StringRedisTemplate controlRedisTemplate(
            @Qualifier("controlRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(name = "cacheRedisTemplate")
    StringRedisTemplate cacheRedisTemplate(
            @Qualifier("cacheRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private LettuceConnectionFactory connectionFactory(GatewayRedisProperties.Endpoint endpoint) {
        RedisConfiguration server = serverConfiguration(endpoint);
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
                .commandTimeout(endpoint.getCommandTimeout())
                .shutdownTimeout(Duration.ZERO);
        LettuceClientConfiguration client =
                endpoint.isSsl() ? builder.useSsl().and().build() : builder.build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client);
        factory.setValidateConnection(true);
        return factory;
    }

    private RedisConfiguration serverConfiguration(GatewayRedisProperties.Endpoint endpoint) {
        List<String> nodes = endpoint.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("Redis nodes 不能为空");
        }
        return switch (endpoint.getMode()) {
            case STANDALONE -> standalone(endpoint, RedisNode.fromString(nodes.getFirst()));
            case SENTINEL -> sentinel(endpoint, nodes);
            case CLUSTER -> cluster(endpoint, nodes);
        };
    }

    private RedisStandaloneConfiguration standalone(GatewayRedisProperties.Endpoint endpoint, RedisNode node) {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(node.getRequiredHost(), node.getRequiredPort());
        configuration.setDatabase(endpoint.getDatabase());
        credentials(configuration, endpoint);
        return configuration;
    }

    private RedisSentinelConfiguration sentinel(GatewayRedisProperties.Endpoint endpoint, List<String> nodes) {
        if (!StringUtils.hasText(endpoint.getMaster())) {
            throw new IllegalStateException("Sentinel 模式必须配置 master");
        }
        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
        configuration.master(endpoint.getMaster());
        nodes.stream().map(RedisNode::fromString).forEach(configuration::addSentinel);
        configuration.setDatabase(endpoint.getDatabase());
        credentials(configuration, endpoint);
        return configuration;
    }

    private RedisClusterConfiguration cluster(GatewayRedisProperties.Endpoint endpoint, List<String> nodes) {
        RedisClusterConfiguration configuration = new RedisClusterConfiguration(nodes);
        configuration.setMaxRedirects(endpoint.getMaxRedirects());
        credentials(configuration, endpoint);
        return configuration;
    }

    private void credentials(RedisConfiguration configuration, GatewayRedisProperties.Endpoint endpoint) {
        RedisConfiguration.WithAuthentication authentication = (RedisConfiguration.WithAuthentication) configuration;
        if (StringUtils.hasText(endpoint.getUsername())) {
            authentication.setUsername(endpoint.getUsername());
        }
        if (StringUtils.hasText(endpoint.getPassword())) {
            authentication.setPassword(RedisPassword.of(endpoint.getPassword()));
        }
    }
}
