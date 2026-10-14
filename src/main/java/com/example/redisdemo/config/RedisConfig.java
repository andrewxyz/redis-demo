package com.example.redisdemo.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║          ALL 4 REDIS PATTERNS IN ONE CLASS                   ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * Pattern 1 — Serialization  : store values as readable JSON
 * Pattern 2 — TTL            : every cached value expires automatically
 * Pattern 3 — Key naming     : keys follow  app:cacheName:id  format
 * Pattern 4 — Cache config   : each cache type has its own TTL policy
 */
@Configuration
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisConfig {

    // ─────────────────────────────────────────────────────────
    // PATTERN 1 — Serialization
    //
    // Build a Jackson ObjectMapper that embeds the Java class
    // name into the JSON so it can be deserialized back correctly.
    //
    // Without this, Redis stores binary like: \xac\xed\x00\x05...
    // With this, Redis stores readable JSON like:
    //   {"@class":"com.example...Product","id":1,"name":"MacBook"}
    // ─────────────────────────────────────────────────────────
    private Jackson2JsonRedisSerializer<Object> buildJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();

        // Embeds the type info so Jackson knows which class to recreate on read
        mapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        return new Jackson2JsonRedisSerializer<>(mapper, Object.class);
    }

    /**
     * RedisTemplate is used for manual Redis operations
     * (get / set / delete) outside of @Cacheable.
     *
     * Pattern 1 in action: keys are plain Strings,
     *                      values are JSON.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        Jackson2JsonRedisSerializer<Object> jsonSerializer = buildJsonSerializer();
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // Keys stored as plain readable strings (e.g. "myapp:products:1")
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Values stored as JSON (e.g. {"id":1,"name":"MacBook Pro",...})
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // ─────────────────────────────────────────────────────────
    // PATTERNS 2, 3 & 4 — TTL + Key naming + Per-cache config
    //
    // RedisCacheManager is the brain of @Cacheable.
    // It decides: how long to keep each cache, what key format
    // to use, and what serializer to use.
    // ─────────────────────────────────────────────────────────

    /**
     * Default configuration shared by all caches unless overridden.
     */
    private RedisCacheConfiguration defaultCacheConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
            // Pattern 2 — TTL: data expires after 10 minutes by default
            .entryTtl(Duration.ofMinutes(10))

            // Pattern 3 — Key naming: prefix = "myapp:<cacheName>:"
            // So @Cacheable("products") with key="#id" → "myapp:products:1"
            .computePrefixWith(cacheName -> "myapp:" + cacheName + ":")

            // Pattern 1 — Serialization: use JSON for cached values
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(buildJsonSerializer())
            )

            // Never cache null — avoids storing "nothing" in Redis
            .disableCachingNullValues();
    }

    /**
     * Pattern 4 — Cache config as code: each cache gets its own TTL.
     *
     * Think about HOW STALE the data can be for each use case:
     *   - products       → 10 min  (rarely changes)
     *   - categories     → 30 min  (almost never changes)
     *   - userSessions   → 30 min  (session timeout)
     *   - exchangeRates  →  1 min  (changes constantly)
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {

        Map<String, RedisCacheConfiguration> perCacheConfigs = new HashMap<>();

        // products cache: 10 minutes
        perCacheConfigs.put("products",
            defaultCacheConfig().entryTtl(Duration.ofMinutes(10)));

        // categories cache: 30 minutes (category list barely changes)
        perCacheConfigs.put("categories",
            defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));

        // userSessions cache: 30 minutes (mirrors session timeout)
        perCacheConfigs.put("userSessions",
            defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));

        // exchangeRates cache: 1 minute (price data must be fresh)
        perCacheConfigs.put("exchangeRates",
            defaultCacheConfig().entryTtl(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultCacheConfig())           // fallback for unlisted caches
            .withInitialCacheConfigurations(perCacheConfigs)
            .build();
    }
}
