# Spring Boot 3 Redis Patterns Demo

A hands-on project for learning the **4 Redis caching patterns** that matter in production. Built with Spring Boot 3, backed by H2 (simulates a real DB), and wired to a real Redis instance — so you can watch cache hits, misses, evictions, and TTL expiry happen live.

**Stack:** Spring Boot 3.2.3 · Java 17 · Redis 7 · H2 · Maven

---

## The 4 Patterns at a Glance

| # | Pattern | Where | What it does | Why it matters |
|---|---|---|---|---|
| 1 | **JSON Serialization** | `RedisConfig.java` | Stores values as readable JSON instead of binary | Human-readable, language-agnostic, survives class refactors |
| 2 | **TTL** | `RedisConfig.java` | Every cached entry expires automatically | Prevents stale data and memory bloat |
| 3 | **Key Naming** | `RedisConfig.java` | Keys follow `myapp:cache:id` format | Prevents collisions, enables targeted bulk operations |
| 4 | **Per-Cache Config** | `RedisConfig.java` | Each cache type has its own TTL | Volatile data (exchange rates) and stable data (categories) need different freshness rules |

---

## Architecture

```
HTTP Request
     │
     ▼
ProductController          ← routes the request
     │
     ▼
ProductService             ← Spring AOP proxy intercepts here
     │
     ├─── Cache HIT?  ──► Redis ──► return cached value (DB never called)
     │
     └─── Cache MISS? ──► ProductRepository ──► H2 Database
                               │
                               └──► write result to Redis ──► return value
```

**Key mental model:** `@Cacheable` does not live inside your method — Spring wraps the entire service bean in an AOP proxy at startup. When a caller invokes `findById(1L)`, the proxy intercepts the call, checks Redis first, and only delegates to your actual method on a cache miss. This is why calling a `@Cacheable` method from within the same class bypasses caching entirely — internal calls skip the proxy.

---

## Project Structure

```
redis-demo/
├── pom.xml
└── src/main/java/com/example/redisdemo/
    ├── RedisDemoApplication.java       ← entry point; seeds 5 products into H2 on startup
    ├── config/
    │   ├── RedisConfig.java            ← ALL 4 PATTERNS LIVE HERE
    │   └── GlobalExceptionHandler.java ← returns JSON errors instead of HTML stack traces
    ├── model/
    │   └── Product.java                ← JPA entity (id, name, price, category)
    ├── repository/
    │   └── ProductRepository.java      ← Spring Data JPA; adds findByCategory()
    ├── service/
    │   └── ProductService.java         ← all cache annotations live here
    └── controller/
        ├── ProductController.java      ← CRUD REST API at /api/products
        └── CacheController.java        ← inspect Redis state at /api/cache
```

---

## Prerequisites & Quick Start

**Requirements:** Java 17+, Maven 3.8+, Docker (for Redis)

**1. Start Redis:**
```bash
docker run -d -p 6379:6379 --name redis redis:7-alpine
```

**Verify Redis is running:**
```bash
docker exec -it redis redis-cli ping
# Expected: PONG
```

**2. Start the app:**
```bash
mvn spring-boot:run
```

On startup you'll see:
```
✅  5 products seeded into H2 database
```

The app seeds: MacBook Pro, iPhone 15, AirPods Pro, iPad Air, Apple Watch.

---

## The 4 Patterns in Depth

### Pattern 1 — JSON Serialization

**What:** By default Spring serializes cached objects as Java binary (`\xac\xed\x00\x05...`) — unreadable and fragile. This project configures Jackson JSON serialization instead.

**The code (`RedisConfig.java`):**
```java
private Jackson2JsonRedisSerializer<Object> buildJsonSerializer() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.activateDefaultTyping(
        LaissezFaireSubTypeValidator.instance,
        ObjectMapper.DefaultTyping.NON_FINAL,
        JsonTypeInfo.As.PROPERTY
    );
    return new Jackson2JsonRedisSerializer<>(mapper, Object.class);
}
```

`activateDefaultTyping` embeds a `@class` field into the JSON so Jackson knows which Java class to reconstruct on deserialization:
```json
{
  "@class": "com.example.redisdemo.model.Product",
  "id": 1,
  "name": "MacBook Pro",
  "price": 2499.99,
  "category": "Laptops"
}
```

**Demo:**
```bash
# 1. Populate the cache
curl http://localhost:8080/api/products/1

# 2. Inspect the raw stored value — you'll see clean JSON
curl http://localhost:8080/api/cache/value/myapp:products:1
```

Expected:
```json
{
  "key": "myapp:products:1",
  "value": { "id": 1, "name": "MacBook Pro", "price": 2499.99, "category": "Laptops" },
  "tip": "Value is stored as JSON (Pattern 1 — Serialization)"
}
```

---

### Pattern 2 — TTL (Auto-Expiry)

**What:** Every cached entry has a time-to-live. When it expires, Redis deletes it automatically. The next request becomes a cache miss and re-fetches from the DB — keeping data eventually fresh without any manual cleanup.

**The code (`RedisConfig.java`):**
```java
RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(10))  // default TTL for all caches
```

The clock starts at **write time** (cache miss), not at read time. Reading a cached value does not reset the timer.

**Demo:**
```bash
# Fetch a product to populate the cache
curl http://localhost:8080/api/products/1

# Check how many seconds remain before the key expires
curl http://localhost:8080/api/cache/ttl/myapp:products:1
```

Expected:
```json
{
  "key": "myapp:products:1",
  "ttlSeconds": 597,
  "meaning": "expires in 597 seconds"
}
```

---

### Pattern 3 — Key Naming

**What:** Redis has one flat global keyspace — no folders or namespaces. Without a convention, keys from different apps or features collide silently. This project enforces `myapp:<cacheName>:<key>` for every entry.

**The code (`RedisConfig.java`):**
```java
.computePrefixWith(cacheName -> "myapp:" + cacheName + ":")
```

Spring calls this function at write time and prepends the result to the `key` value from the annotation:

```
"myapp:" + "products" + ":"  +  "1"       →  myapp:products:1
"myapp:" + "products" + ":"  +  "all"     →  myapp:products:all
"myapp:" + "categories" + ":"  +  "Laptops" →  myapp:categories:Laptops
```

| Segment | Purpose |
|---|---|
| `myapp:` | App isolation — other apps on the same Redis instance can't collide |
| `products:` | Group isolation — lets you target all keys of one type |
| `1` / `all` | Entry identity — the specific cached value |

**Demo:**
```bash
# See all keys this app has written to Redis
curl http://localhost:8080/api/cache/keys
```

Expected:
```json
{
  "totalKeys": 3,
  "keys": ["myapp:products:1", "myapp:products:all", "myapp:categories:Laptops"],
  "tip": "Key format shows Pattern 3 in action: myapp:<cache>:<key>"
}
```

---

### Pattern 4 — Per-Cache Configuration

**What:** Different data has different freshness requirements. One global TTL is always wrong for at least one cache. `RedisCacheManager` holds a map of cache name → config, so each cache gets exactly the TTL it needs.

**The code (`RedisConfig.java`):**
```java
Map<String, RedisCacheConfiguration> perCacheConfigs = new HashMap<>();
perCacheConfigs.put("products",      defaultCacheConfig().entryTtl(Duration.ofMinutes(10)));
perCacheConfigs.put("categories",    defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));
perCacheConfigs.put("userSessions",  defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));
perCacheConfigs.put("exchangeRates", defaultCacheConfig().entryTtl(Duration.ofMinutes(1)));

return RedisCacheManager.builder(factory)
    .cacheDefaults(defaultCacheConfig())           // fallback for any cache not in the map
    .withInitialCacheConfigurations(perCacheConfigs)
    .build();
```

| Cache | TTL | Reasoning |
|---|---|---|
| `products` | 10 min | Product info rarely changes |
| `categories` | 30 min | Category list almost never changes |
| `userSessions` | 30 min | Mirrors the session timeout window |
| `exchangeRates` | 1 min | Stale rates = wrong prices charged to users |

The `value` string in `@Cacheable(value = "products")` is the lookup key into this map. If a cache name is not in the map, `cacheDefaults()` (10 min) is used as the fallback — it does not crash.

---

## Cache Annotation Guide

All four Spring cache annotations are demonstrated in `ProductService.java`:

| Annotation | Behaviour | Used on |
|---|---|---|
| `@Cacheable` | Check cache first; call method and store result only on miss | `findById`, `findAll`, `findByCategory` |
| `@CachePut` | Always call the method; always write return value into cache | `createProduct`, `updateProduct` |
| `@CacheEvict` | Remove one or more keys from cache after method runs | `deleteProduct` |
| `@Caching` | Combine multiple cache operations on a single method | `createProduct`, `updateProduct`, `deleteProduct` |

**`updateProduct` — the canonical example** (`ProductService.java`):
```java
@Caching(
    put   = { @CachePut(value = "products", key = "#product.id") },  // write updated entry
    evict = { @CacheEvict(value = "products",   key = "'all'"),       // bust the list cache
              @CacheEvict(value = "categories", allEntries = true) }  // bust all category caches
)
public Product updateProduct(Product product) { ... }
```

- `@CachePut` writes the updated product back into the cache immediately — no wasted DB hit on the next `findById`.
- `@CacheEvict(key = "'all'")` removes only the full-list entry — surgical, not nuclear.
- `@CacheEvict(allEntries = true)` on `categories` is intentional: after an update you don't know the product's old category, so you can't target a specific key. Wiping all category entries is the safest move.

---

## Observing Cache Behavior

TRACE-level cache logging is pre-configured in `application.properties`. Watch your terminal as you run these steps in order:

```bash
# Step 1 — cache MISS: DB is called, result stored in Redis
curl http://localhost:8080/api/products/1
# Console: 🗄️  Cache MISS — fetching product 1 from DATABASE

# Step 2 — cache HIT: Redis serves the response, DB is never called
curl http://localhost:8080/api/products/1
# Console: (nothing — served silently from Redis)

# Step 3 — update: cache entry for id=1 is refreshed, list cache is evicted
curl -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"id":1,"name":"MacBook Pro M4","price":2699.99,"category":"Laptops"}'
# Console: 💾  Updating product 1 and refreshing cache

# Step 4 — cache HIT again: updated value served from cache (no DB hit)
curl http://localhost:8080/api/products/1
# Console: (nothing — @CachePut already wrote the new value)

# Step 5 — delete: cache entry evicted
curl -X DELETE http://localhost:8080/api/products/1
# Console: 🗑️  Deleting product 1 and evicting from cache

# Step 6 — cache MISS: product is gone from both cache and DB
curl http://localhost:8080/api/products/1
# Response: 404 {"error": "Product not found: 1"}
```

---

## Full API Reference

### Products (`/api/products`)

| Method | URL | Description | Cache effect |
|---|---|---|---|
| GET | `/api/products` | Get all products | `@Cacheable` — key `myapp:products:all` |
| GET | `/api/products/{id}` | Get one product | `@Cacheable` — key `myapp:products:{id}` |
| GET | `/api/products/category/{cat}` | Get by category | `@Cacheable` — key `myapp:categories:{cat}` |
| POST | `/api/products` | Create product | `@CachePut` on new entry + evict list and category caches |
| PUT | `/api/products/{id}` | Update product | `@CachePut` on entry + evict list and category caches |
| DELETE | `/api/products/{id}` | Delete product | `@CacheEvict` on entry, list, and all category caches |

### Cache Inspection (`/api/cache`)

| Method | URL | Description |
|---|---|---|
| GET | `/api/cache/keys` | List all `myapp:*` keys in Redis |
| GET | `/api/cache/ttl/{key}` | Get remaining TTL in seconds |
| GET | `/api/cache/value/{key}` | Get the raw JSON value stored under a key |
| DELETE | `/api/cache/flush` | Delete all `myapp:*` keys |

### H2 Console
- URL: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:redisdb`
- Username: `sa` / Password: *(empty)*

---

## Testing

```bash
mvn test
```

Tests run **without a real Redis instance**. Two mechanisms make this work:

1. `spring.cache.type=simple` switches the cache to an in-memory `ConcurrentMapCacheManager`.
2. `@ConditionalOnProperty` on `RedisConfig` and `CacheController` skips those beans entirely when the cache type is not `redis` — so no `RedisConnectionFactory` is needed.

**What each test verifies (`ProductServiceTest.java`):**

| Test | What it proves |
|---|---|
| `findById_shouldCacheResult_onSecondCall` | `productRepository.findById()` is called exactly once despite two `service.findById()` calls — the second is served from cache |
| `deleteProduct_shouldEvictFromCache` | After `deleteProduct()`, the next `findById()` is a cache miss and hits the DB again — proving eviction works |

---

## CRUD Examples

```bash
# Create
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Samsung TV","price":1299.99,"category":"Electronics"}'

# Read all
curl http://localhost:8080/api/products

# Read by category
curl http://localhost:8080/api/products/category/Laptops

# Update
curl -X PUT http://localhost:8080/api/products/2 \
  -H "Content-Type: application/json" \
  -d '{"id":2,"name":"iPhone 15 Pro","price":1199.99,"category":"Phones"}'

# Delete
curl -X DELETE http://localhost:8080/api/products/2
```

---

## Concepts & Gotchas

### Stale Reads — Is Retrieved Data Ever Invalid?

**Scenario:** Service 1 calls `findAll()` at T=0ms. Service 2 calls `deleteProduct()` 10ms later.
Does the data returned by Service 1 become invalid?

**Yes — and this is expected behavior, not a bug.**

```
T=0ms    Service 1: findAll() → cache hit → reads list [A, B, C] into memory
T=10ms   Service 2: deleteProduct(C) → deletes C from DB → evicts myapp:products:all
T=??     Service 1: returns [A, B, C] to its caller  ← C is already gone from DB
```

Once data is pulled out of Redis into a service's memory, no subsequent cache eviction can reach in and update it. Service 1 holds a snapshot from the moment it read — that snapshot is now stale.

This is called **eventual consistency**: the system will converge to the correct state, just not instantaneously.

#### How this codebase limits the damage

| Mechanism | How it helps |
|---|---|
| `@CacheEvict` on delete/update | Ensures no *future* request gets the stale entry |
| TTL (10 min for `products`) | Absolute backstop — stale data cannot survive beyond the TTL even if eviction logic has a bug |

#### Does your use case tolerate it?

| Use case | Staleness tolerance |
|---|---|
| Product catalog on an e-commerce site | Usually yes — a few ms is fine |
| Bank account balance | No — stale reads cause real harm |
| Real-time inventory / stock levels | Depends — often no |

If you need stronger guarantees, the solution space involves distributed locks, versioned cache entries, or skipping the cache for that specific read — all of which add significant complexity.

---

### TTL — When Does the 10-Minute Clock Actually Start?

`entryTtl(Duration.ofMinutes(10))` sets a **fixed TTL**: the clock starts the moment the key is **written** to Redis (on first cache miss). It does **not** reset on reads.

```
T=0min    findAll() → cache MISS → hits DB → writes myapp:products:all to Redis
          ← 10-minute clock starts here

T=2min    findAll() → cache HIT  ← clock NOT reset, 8 min left
T=5min    findAll() → cache HIT  ← clock NOT reset, 5 min left
T=9min    findAll() → cache HIT  ← clock NOT reset, 1 min left

T=10min   Redis automatically deletes myapp:products:all

T=10min+  findAll() → cache MISS → hits DB → writes key again → fresh 10min clock
```

Redis itself deletes the key — not Spring. Spring only sets the expiry at write time; Redis runs its own background expiry process.

#### Fixed TTL vs Sliding TTL

| | Fixed TTL | Sliding TTL |
|---|---|---|
| Clock resets on read? | No | Yes |
| Clock resets on write? | Yes | Yes |
| Risk | Hot data expires even if actively used | Popular data may never expire |
| Spring default | Yes | No — needs custom config |

**Fixed TTL (this project — `entryTtl`):**
```java
// Clock starts at write time, never resets on read.
// Key expires exactly 10 minutes after it was first cached.
RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(10));
```

**Sliding TTL (custom — resets on every read):**
```java
// Requires a custom CacheWriter; Spring does not support this out of the box.
// Every cache hit pushes the expiry 10 minutes into the future.
// Risk: a very popular key may never expire and serve stale data indefinitely.
RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(10));

RedisCacheWriter writer = new SlidingTtlCacheWriter(factory, Duration.ofMinutes(10));
RedisCacheManager.builder(writer)
    .cacheDefaults(config)
    .build();
```

Fixed TTL is almost always the right choice for caching — you want a guarantee that even the most popular entry eventually gets refreshed from the database.

---

### Key Naming — How Redis Keys Are Built

`.computePrefixWith(cacheName -> "myapp:" + cacheName + ":")` is a function that runs every time Spring writes a key to Redis. It takes the cache name and returns the prefix. Spring then appends the `key="#id"` value from the annotation after it.

```
prefix                       +  key from annotation
"myapp:" + cacheName + ":"   +  #id value

→ "myapp:products:"   + "1"       = myapp:products:1
→ "myapp:products:"   + "all"     = myapp:products:all
→ "myapp:categories:" + "Laptops" = myapp:categories:Laptops
```

Redis has one flat global keyspace — no folders, no tables. Without a naming convention:

```
# Without convention — flat, collision-prone
1
all
Laptops

# With myapp:cacheName:key — structured, self-documenting
myapp:products:1
myapp:products:all
myapp:categories:Laptops
```

#### The three segments each solve a specific problem

| Segment | Example | Problem it solves |
|---|---|---|
| `myapp:` | `myapp:` | App isolation — two apps on the same Redis instance can't collide |
| `cacheName:` | `products:` | Group isolation — lets you target all keys of one type |
| `key` | `1`, `all` | Entry identity — the specific cached value |

#### Without `.computePrefixWith(...)` — Spring's default

```
products::1        ← double colon, no app namespace
products::all
categories::Laptops
```

Fine for local dev, a liability in production where multiple services share one Redis cluster.

#### What this unlocks operationally

Because all keys follow the same pattern, you can do safe targeted bulk operations:

```bash
# See everything this app owns — without touching other apps' keys
redis-cli KEYS "myapp:*"

# See only product cache entries
redis-cli KEYS "myapp:products:*"

# Flush only the products cache, not categories or sessions
redis-cli DEL $(redis-cli KEYS "myapp:products:*")
```

This is exactly what `CacheController.flush()` does — it uses `myapp:*` as the pattern so it only deletes this app's keys even if other apps live on the same Redis instance.

---

### `@ConditionalOnProperty` — Making Redis Beans Optional for Tests

**The problem:** Tests use `spring.cache.type=simple` (in-memory, no Redis needed) and exclude Redis auto-configuration. But `RedisConfig` and `CacheController` both hard-depend on `RedisConnectionFactory` / `RedisTemplate` — beans that no longer exist when Redis is excluded. Spring fails to start the test context.

```
Caused by: NoSuchBeanDefinitionException:
  No qualifying bean of type 'RedisConnectionFactory' available
```

**The fix:** Annotate both classes with `@ConditionalOnProperty` so they only load when Redis is actually in use:

```java
// RedisConfig.java
@Configuration
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisConfig { ... }

// CacheController.java
@RestController
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class CacheController { ... }
```

**How the three cases resolve:**

| `spring.cache.type` value | Effect |
|---|---|
| Not set (production default) | `matchIfMissing = true` → both classes load → Redis works normally |
| `redis` (explicit) | Matches `havingValue` → both classes load |
| `simple` (tests) | Does not match → both classes skipped → in-memory cache takes over |

`matchIfMissing = true` is the key detail — it means "if the property is absent from the environment entirely, treat the condition as satisfied." This ensures production behaviour is unchanged even when `spring.cache.type` is never explicitly set in `application.properties`.

---

### Per-Cache TTL — Why One TTL Does Not Fit All Data

Setting one global TTL forces a bad trade-off:

| If you pick a short TTL | If you pick a long TTL |
|---|---|
| Stable data (categories) expires too fast → unnecessary DB hits | Volatile data (exchange rates) goes stale → wrong prices shown to users |

`RedisCacheManager` solves this by holding a **map of cache name → config**. The `value` string in your annotation is the lookup key into that map:

```java
@Cacheable(value = "products", ...)      // → looks up "products"      → 10 min TTL
@Cacheable(value = "exchangeRates", ...) // → looks up "exchangeRates" →  1 min TTL
```

#### Why each TTL is what it is

| Cache | TTL | Reasoning |
|---|---|---|
| `products` | 10 min | Product info changes infrequently — 10 min staleness is acceptable |
| `categories` | 30 min | Category list almost never changes — long TTL = fewer wasted DB hits |
| `userSessions` | 30 min | Mirrors the session timeout — cache and session expire together |
| `exchangeRates` | 1 min | Currency rates change constantly — stale rates = wrong prices charged to users |

#### What happens if you add a new `@Cacheable` not in the map

```java
@Cacheable(value = "recommendations", key = "#userId")
public List<Product> getRecommendations(Long userId) { ... }
```

`"recommendations"` is not in `perCacheConfigs`, so `RedisCacheManager` falls back to `.cacheDefaults()` — the 10-minute default. It does not crash; it silently uses the fallback. This is why `.cacheDefaults()` always needs to be set.

#### Config as code — not CLI commands

TTL values live in Java, in the same repo as the business logic, reviewed in the same pull request:

```java
// Declared in code — versioned, visible, consistent across every environment
perCacheConfigs.put("exchangeRates", defaultCacheConfig().entryTtl(Duration.ofMinutes(1)));
```

Compare that to setting TTL manually in Redis CLI — invisible to the team, lost on Redis restart:

```bash
# Avoid this — TTL lives outside the codebase, impossible to review or audit
redis-cli EXPIRE myapp:exchangeRates:USD 60
```
