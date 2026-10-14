package com.example.redisdemo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Bonus controller — lets you SEE what's inside Redis.
 *
 * Useful for understanding the 4 patterns:
 *   GET /api/cache/keys        → lists all key names in Redis  (Pattern 3: key naming)
 *   GET /api/cache/ttl/{key}   → shows remaining TTL in seconds (Pattern 2: TTL)
 *   DELETE /api/cache/flush    → wipes ALL Redis keys
 */
@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class CacheController {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Lists every key currently stored in Redis.
     * You'll see keys like:  myapp:products:1
     *                        myapp:products:all
     *                        myapp:categories:Laptops
     */
    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> listKeys() {
        Set<String> keys = redisTemplate.keys("myapp:*");

        Map<String, Object> result = new HashMap<>();
        result.put("totalKeys", keys == null ? 0 : keys.size());
        result.put("keys", keys);
        result.put("tip", "Key format shows Pattern 3 in action: myapp:<cache>:<key>");

        return ResponseEntity.ok(result);
    }

    /**
     * Shows the remaining TTL (seconds) for a specific Redis key.
     * This visualises Pattern 2 — TTL.
     *
     * Example: GET /api/cache/ttl/myapp:products:1
     */
    @GetMapping("/ttl/{key}")
    public ResponseEntity<Map<String, Object>> getTtl(@PathVariable String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("ttlSeconds", ttl);
        result.put("meaning", ttl == null ? "key does not exist"
                            : ttl == -1   ? "no expiry set (bad!)"
                            : ttl == -2   ? "key does not exist"
                            : "expires in " + ttl + " seconds");

        return ResponseEntity.ok(result);
    }

    /**
     * Shows the raw JSON value stored under a key.
     * This visualises Pattern 1 — Serialization.
     *
     * Example: GET /api/cache/value/myapp:products:1
     */
    @GetMapping("/value/{key}")
    public ResponseEntity<Map<String, Object>> getValue(@PathVariable String key) {
        Object value = redisTemplate.opsForValue().get(key);

        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("value", value);
        result.put("tip", "Value is stored as JSON (Pattern 1 — Serialization)");

        return ResponseEntity.ok(result);
    }

    /**
     * Wipes all keys — useful for resetting the demo
     * to see cache misses again.
     */
    @DeleteMapping("/flush")
    public ResponseEntity<Map<String, String>> flush() {
        Set<String> keys = redisTemplate.keys("myapp:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        Map<String, String> result = new HashMap<>();
        result.put("status", "flushed");
        result.put("deletedKeys", String.valueOf(keys == null ? 0 : keys.size()));

        return ResponseEntity.ok(result);
    }
}
