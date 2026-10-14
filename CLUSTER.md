# Redis Cluster — Setup, API & Testing

This document covers the 6-node Redis Cluster feature added to the Spring Boot Redis demo. It includes the Docker Compose setup, CLI verification, Java code changes, the cluster inspection API, and the unit test suite.

---

## Cluster Topology

```
Master 1 (:7000)  ←──→  Replica 1 (:7003)   slots      0 –  5460
Master 2 (:7001)  ←──→  Replica 2 (:7004)   slots   5461 – 10922
Master 3 (:7002)  ←──→  Replica 3 (:7005)   slots  10923 – 16383
```

Redis splits its keyspace into **16384 hash slots**. Every key is assigned a slot using `CRC16(key) % 16384`. Each master owns a contiguous range of slots. When a client sends a command for a key that lives on a different node, Redis replies with a `MOVED <slot> <host>:<port>` redirect and the client retries automatically.

Replicas are hot standbys. They serve reads (when `ReadFrom.REPLICA_PREFERRED` is configured) and promote themselves to master automatically if their master fails.

---

## Files Added

| File | Purpose |
|---|---|
| `docker-compose.yml` | Single `redis:7-alpine` container running all 6 Redis instances (ports 7000-7005) |
| `.env` | `REDIS_PASSWORD=RedisCluster@2024` — never committed (listed in `.gitignore`) |
| `src/main/resources/application-cluster.properties` | Connection settings and pool config, activated by the `cluster` Spring profile |
| `src/main/java/.../controller/ClusterController.java` | 5 inspection/simulation endpoints, active only when `@Profile("cluster")` |
| `src/test/java/.../ClusterControllerTest.java` | 13 unit tests for `ClusterController` — no real cluster needed |

**Modified files:**

| File | What changed |
|---|---|
| `src/main/java/.../config/RedisConfig.java` | Added `@Bean @Profile("cluster") LettuceConnectionFactory` |
| `pom.xml` | Added `commons-pool2` dependency (required for Lettuce connection pooling) |

---

## Starting the Cluster

```bash
# Start the container (all 6 Redis instances + cluster bootstrap)
docker compose up -d

# Watch startup progress — takes ~15 seconds
docker compose logs -f redis-cluster
# You'll see: "started Redis on port 7000" ... "cluster ready — connect to 127.0.0.1:7000-7005"

# Confirm cluster is healthy
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 cluster info | grep -E "cluster_state|slots_assigned"
# Expected:
# cluster_state:ok
# cluster_slots_assigned:16384
```

> **Restart (keeps data):** `docker compose restart redis-cluster` — instances reload from the persistent volume; no re-bootstrap needed.
> **Full wipe:** `docker compose down -v && docker compose up -d` — destroys the volume and re-bootstraps a clean cluster.

---

## Verifying the Cluster (CLI)

```bash
# Full topology check — reports slot coverage gaps or node failures
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 --cluster check 127.0.0.1:7000

# All nodes: ID, role, host:port, and slot ranges
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 cluster nodes

# Slot coverage count (must be 16384)
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 cluster info | grep cluster_slots_assigned
```

---

## Testing Sharding Live (Client Mode)

`redis-cli -c` enables cluster-aware mode — it follows `MOVED` redirects transparently.

```bash
# Write keys — each lands on whichever master owns that hash slot
docker compose exec redis-cluster redis-cli -c -p 7000 -a RedisCluster@2024 set foo "1"
docker compose exec redis-cluster redis-cli -c -p 7000 -a RedisCluster@2024 set bar "2"
docker compose exec redis-cluster redis-cli -c -p 7000 -a RedisCluster@2024 set baz "3"

# Ask any node which slot a key maps to (no redirect needed — it's metadata)
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 cluster keyslot foo
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 cluster keyslot bar

# Read via a different port — -c follows the MOVED redirect transparently
docker compose exec redis-cluster redis-cli -c -p 7001 -a RedisCluster@2024 get foo

# Count keys per master port (shows distribution across the 3 masters)
docker compose exec redis-cluster redis-cli -p 7000 -a RedisCluster@2024 dbsize
docker compose exec redis-cluster redis-cli -p 7001 -a RedisCluster@2024 dbsize
docker compose exec redis-cluster redis-cli -p 7002 -a RedisCluster@2024 dbsize
```

---

## Running the App Against the Cluster

```bash
# Export the password (must match .env)
export REDIS_PASSWORD=RedisCluster@2024

# Start with the cluster Spring profile
mvn spring-boot:run -Dspring-boot.run.profiles=cluster
```

Spring loads `application.properties` first, then overlays `application-cluster.properties`. The overlay adds the 6-node connection string, password auth resolved from `REDIS_PASSWORD`, and a Lettuce connection pool.

---

## Java Code Changes

Two additions were made. **Neither removes the existing 4 caching patterns** — `RedisTemplate` and `RedisCacheManager` accept any `RedisConnectionFactory` regardless of topology, so patterns 1–4 work identically in cluster mode.

### 1. Cluster-Aware Connection Factory (`RedisConfig.java`)

```java
@Bean
@Profile("cluster")
public LettuceConnectionFactory lettuceConnectionFactory(Environment env) {
    List<String> nodes = Arrays.stream(
        env.getRequiredProperty("spring.data.redis.cluster.nodes").split(",")
    ).map(String::trim).collect(Collectors.toList());

    RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(nodes);
    clusterConfig.setPassword(RedisPassword.of(env.getProperty("spring.data.redis.password")));
    clusterConfig.setMaxRedirects(3);           // follow up to 3 MOVED redirects per command

    ClusterTopologyRefreshOptions topologyRefresh = ClusterTopologyRefreshOptions.builder()
        .enableAllAdaptiveRefreshTriggers()     // react immediately to MOVED, ASK, reconnect
        .enablePeriodicRefresh(Duration.ofSeconds(30))
        .build();

    LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
        .clientOptions(ClusterClientOptions.builder()
            .topologyRefreshOptions(topologyRefresh)
            .build())
        .readFrom(ReadFrom.REPLICA_PREFERRED)   // reads use replicas when available
        .build();

    return new LettuceConnectionFactory(clusterConfig, clientConfig);
}
```

This bean is only created when `--spring.profiles.active=cluster`. Spring Boot's auto-configured standalone `LettuceConnectionFactory` carries `@ConditionalOnMissingBean`, so it steps aside automatically when this one is present.

**`ReadFrom.REPLICA_PREFERRED`** routes reads to a replica if one is available, spreading read load across 3 extra nodes without changing any business code.

**Topology refresh** keeps Lettuce's internal node map current. Without it, the client caches the topology at startup. After a failover, it would keep routing to the old master until restarted. `enableAllAdaptiveRefreshTriggers()` makes Lettuce update on every `MOVED`/`ASK` response.

### 2. `ClusterController.java`

Uses `@Profile("cluster")` — not registered in standalone mode. All 5 methods access cluster-specific operations by casting the raw connection:

```java
redisTemplate.execute((RedisCallback<T>) conn -> {
    RedisClusterConnection cluster = (RedisClusterConnection) conn;
    // cluster.clusterGetClusterInfo()
    // cluster.clusterGetNodes()
    // cluster.clusterGetNodeForSlot(slot)
});
```

`RedisCallback` gives direct access to the raw connection outside of Spring Cache's abstraction layer.

### How the 4 Patterns Survive Cluster Mode

| Pattern | Why it's unaffected |
|---|---|
| 1 — JSON Serialization | `RedisTemplate` serializer config is set at the template level, not the connection level |
| 2 — TTL | `entryTtl()` is applied at write time; Redis enforces it on whichever node owns the key's slot |
| 3 — Key Naming | `computePrefixWith()` runs client-side before the key is sent to any node |
| 4 — Per-Cache Config | `RedisCacheManager` consumes `RedisConnectionFactory`; topology is invisible to it |

---

## Cluster API Reference (`/api/cluster`)

All endpoints are active only when the app starts with `-Dspring-boot.run.profiles=cluster`.

| Method | URL | What it returns |
|---|---|---|
| GET | `/api/cluster/info` | Cluster state, total slots assigned, separate master and replica counts |
| GET | `/api/cluster/nodes` | All 6 nodes: address, role, slot range (masters), master ID (replicas) |
| GET | `/api/cluster/slot?key=foo` | Hash slot for `foo` (`CRC16("foo") % 16384`), which master owns it |
| POST | `/api/cluster/load-test?count=50` | Writes 50 keys, reports how they distribute across the 3 masters |
| GET | `/api/cluster/replication` | Master → replica mapping for every master |

### `GET /api/cluster/info`

```bash
curl -s http://localhost:8080/api/cluster/info
```

```json
{
  "clusterState": "ok",
  "slotsAssigned": 16384,
  "slotsFail": 0,
  "knownNodes": 6,
  "clusterSize": 3,
  "mastersCount": 3,
  "replicasCount": 3
}
```

### `GET /api/cluster/nodes`

```bash
curl -s http://localhost:8080/api/cluster/nodes
```

```json
[
  { "id": "abc123", "address": "127.0.0.1:7000", "role": "MASTER", "connected": true, "slotRange": "0-5460" },
  { "id": "def456", "address": "127.0.0.1:7003", "role": "REPLICA", "connected": true, "masterId": "abc123" }
]
```

### `GET /api/cluster/slot?key=foo`

```bash
curl -s "http://localhost:8080/api/cluster/slot?key=foo"
```

```json
{
  "key": "foo",
  "hashSlot": 12356,
  "totalSlots": 16384,
  "formula": "CRC16(\"foo\") % 16384 = 12356",
  "ownerNode": "127.0.0.1:7002",
  "ownerRole": "MASTER"
}
```

### `POST /api/cluster/load-test?count=50`

```bash
curl -s -X POST "http://localhost:8080/api/cluster/load-test?count=50"
```

```json
{
  "keysWritten": 50,
  "ttlMinutes": 5,
  "distributionPerNode": {
    "127.0.0.1:7000": 18,
    "127.0.0.1:7001": 16,
    "127.0.0.1:7002": 16
  },
  "sampleKeys": [
    { "key": "test:load:0", "slot": 9691,  "node": "127.0.0.1:7001" },
    { "key": "test:load:1", "slot": 11332, "node": "127.0.0.1:7002" }
  ],
  "note": "Keys distribute across nodes based on CRC16 hash slot (0–16383 split across 3 masters)"
}
```

Keys distribute roughly evenly (≈16–17 per master) because CRC16 produces a uniform distribution across 16384 slots.

### `GET /api/cluster/replication`

```bash
curl -s http://localhost:8080/api/cluster/replication
```

```json
{
  "masterReplicaMap": {
    "127.0.0.1:7000": ["127.0.0.1:7003"],
    "127.0.0.1:7001": ["127.0.0.1:7004"],
    "127.0.0.1:7002": ["127.0.0.1:7005"]
  },
  "note": "Each master owns ~5461 hash slots; its replica is a hot standby for automatic failover"
}
```

---

## `docker-compose.yml` Design Decisions

### Why a single container for all 6 Redis instances?

**The multi-container approach fails on Docker Desktop (Mac/Windows).** When you run 6 Redis containers, each one announces `127.0.0.1` as its cluster IP (the default). Inside any Docker container, `127.0.0.1` refers to that container's *own* loopback interface — not another container's. So when Node-0 tries to gossip with Node-1 at `127.0.0.1:17001`, it connects to its own port 17001, which isn't open. Gossip fails, and `cluster_state` stays `fail` permanently. `--cluster-announce-ip` doesn't help because there's no host IP that's consistently reachable from all containers on Docker Desktop's network.

The solution: run all 6 Redis instances inside **one container**. Within a single container, `127.0.0.1:7000–7005` are all reachable by every instance — they share the same network namespace. Inter-node gossip works. All 6 ports are published to the host (`7000:7000` … `7005:7005`) so the Java client can connect normally from outside Docker.

### Why `redis:7-alpine` with a startup script instead of Bitnami?

Bitnami's `redis-cluster` image was removed from Docker Hub and GHCR in late 2024. Pulling any tag returns `not found` or `denied`. The official `redis:7-alpine` image is always available and doesn't require authentication. The startup script replaces what Bitnami's entrypoint did automatically: start each instance with `--daemonize yes`, wait for all to accept `PING`, then bootstrap the cluster with `redis-cli --cluster create`.

### Why `--daemonize yes` for each instance?

The container needs to run 6 Redis processes in the background, then block on `tail -f /dev/null` so Docker doesn't exit. `--daemonize yes` forks each Redis server into the background, freeing the shell to continue with the next instance. Without it, the first `redis-server` call would block the script and no other instances would start.

### Why `--cluster-replicas 1`?

Tells `redis-cli --cluster create` to assign one replica per master. With 6 nodes and `--cluster-replicas 1`, the tool automatically assigns 3 masters (ports 7000–7002) and 3 replicas (ports 7003–7005). The replica assignments are automatic — you don't need to specify which replica pairs with which master.

### Why a named volume (`redis-cluster-data`)?

All six Redis instances write their state to `/data` inside the container, which is backed by the `redis-cluster-data` Docker named volume:

| File | Purpose |
|---|---|
| `/data/nodes-700X.conf` | Cluster node registry — node IDs, roles, slot assignments (one per instance) |
| `/data/appendonly-700X.aof` | AOF log — every write command appended immediately (one per instance) |
| `/data/dump-700X.rdb` | RDB snapshot — periodic full-data backup (one per instance) |
| `/data/redis-700X.log` | Per-instance log file |
| `/data/.bootstrapped` | Marker file — created once after `redis-cli --cluster create` succeeds |

Without a volume, all of this is lost on restart and the cluster has to be rebuilt from scratch.

**Why per-instance filenames (`dump-7000.rdb`, `appendonly-7001.aof`, …)?**

All 6 Redis instances share the same `/data` directory. Without explicit filenames, every instance writes to the default `dump.rdb` and `appendonly.aof`, racing to overwrite each other's files. On restart, each instance would load another instance's data. The `--dbfilename dump-$$port.rdb` and `--appendfilename appendonly-$$port.aof` flags give each instance its own file.

**Why AOF (`--appendonly yes`) instead of relying on RDB?**

RDB (the default) snapshots data periodically. With default save intervals (`save 3600 1` — snapshot after one hour if at least one key changed), a restart after writing just a few keys would lose all data because the snapshot had never been triggered. AOF appends every write command to disk immediately, so no data is lost between snapshots.

**Why a `.bootstrapped` marker instead of checking `nodes.conf`?**

Redis writes `nodes.conf` immediately when it starts — with just its own node entry — before joining any cluster. Checking `[ -s /data/nodes-7000.conf ]` would always return true (non-empty) from the very first startup, causing the bootstrap to be skipped on the first run and leaving 6 isolated nodes that never form a cluster. The `.bootstrapped` file is written by the startup script only after `redis-cli --cluster create` exits successfully, so it reliably indicates a fully formed cluster.

- `docker compose restart redis-cluster` — instances come back up, load existing state, skip bootstrap. All data intact.
- `docker compose down && docker compose up -d` — volume survives `down`; bootstrap still skipped on next start.
- `docker compose down -v && docker compose up -d` — `-v` deletes the volume and `.bootstrapped`; bootstrap runs fresh on next start.

---

## Testing

```bash
mvn test   # runs all 15 tests — no Redis, no Docker needed
```

### `ProductServiceTest.java` (2 tests)

Covered in the main [README.md — Testing](README.md#testing) section.

### `ClusterControllerTest.java` (13 tests)

Pure unit tests. No Spring context is loaded, no Docker is needed. `ClusterController` is instantiated directly by Mockito's `@InjectMocks`.

#### Strategy

`ClusterController` has one integration point — `redisTemplate.execute(RedisCallback)`. Every test intercepts this call and runs the callback against a mock `RedisClusterConnection`:

```java
@SuppressWarnings("unchecked")
@BeforeEach
void routeExecuteCallbackThroughMockConnection() {
    when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(inv -> {
        RedisCallback<?> cb = inv.getArgument(0);
        return cb.doInRedis(clusterConn);   // inject mock — no real Redis needed
    });
}
```

Each test then stubs exactly what the cluster API should return and asserts the response body.

#### Coverage

| Endpoint | Tests | What is verified |
|---|---|---|
| `GET /api/cluster/info` | 2 | Cluster state field, slot count, master and replica counts are separated correctly |
| `GET /api/cluster/nodes` | 2 | All 6 nodes returned with address, role, and masterId fields populated correctly |
| `GET /api/cluster/slot` | 3 | Slot is in range 0–16383, owner node address matches the stub, two different keys produce two different slots (deterministic CRC16) |
| `POST /api/cluster/load-test` | 3 | Exact `opsForValue().set()` call count via `verify()`, per-node distribution map, sample key entries contain `key`/`slot`/`node` fields |
| `GET /api/cluster/replication` | 3 | Single master-replica pair, full 3-pair mapping, edge case of a master with no replica (returns empty list, not null) |

#### Helper Methods

```java
private RedisClusterNode masterNode(String id, String host, int port) {
    RedisClusterNode node = mock(RedisClusterNode.class);
    when(node.getId()).thenReturn(id);
    when(node.getHost()).thenReturn(host);
    when(node.getPort()).thenReturn(port);
    when(node.getType()).thenReturn(RedisClusterNode.NodeType.MASTER);
    when(node.isConnected()).thenReturn(true);
    when(node.getMasterId()).thenReturn(null);
    when(node.getSlotRange()).thenReturn(null);
    return node;
}
```

Each helper stubs a full node profile. Different tests use different subsets of the fields, which is why lenient strictness is needed (see Gotcha 2 below).

#### Gotcha 1 — `UnfinishedStubbingException`

Calling a helper inside a `thenReturn()` argument triggers Mockito's unfinished stubbing detector. When Java evaluates the argument, the outer `when()` is already in pending state; the inner `when()` call inside the helper collides with it.

```java
// WRONG — masterNode() calls when() while the outer when() is still pending
when(clusterConn.clusterGetNodeForSlot(anyInt()))
    .thenReturn(masterNode("m1", "127.0.0.1", 7000));

// CORRECT — complete all internal stubs first, then set up the outer stub
RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);
```

The same rule applies to `List.of(masterNode(...), replicaNode(...))` passed inline to `thenReturn()`. Always assign to a local variable first.

#### Gotcha 2 — `UnnecessaryStubbingException`

The helpers stub a full node profile: `getId`, `getHost`, `getPort`, `getType`, `isConnected`, `getMasterId`, `getSlotRange`. Different tests use different subsets — `slotForKey` only reads `getHost`/`getPort`, while `nodes` also reads `getId`/`isConnected`. Mockito strict mode flags every unused stub as a test error.

The fix is `@MockitoSettings(strictness = Strictness.LENIENT)` at the class level. This is the correct choice when shared helpers intentionally stub more than any single test uses:

```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterControllerTest { ... }
```

Lenient mode suppresses `UnnecessaryStubbingException` while still catching real mistakes like missing `thenReturn()` or wrong argument matchers.

---

## Troubleshooting

### `cluster_state:fail` + `knownNodes:1` from `/api/cluster/info`

**Symptom:** The endpoint responds but returns `clusterState:fail`, `slotsAssigned:0`, and `knownNodes:1` instead of 6.

**Cause:** The app is not talking to the bootstrapped cluster. There are two common reasons:

#### 1. Orphan containers are squatting on ports 7000–7005

If you previously ran a multi-container setup (e.g., `redis-node-0` … `redis-node-5`), those containers may still be running and holding ports 7000–7005. When `docker compose up -d` starts the new single-container cluster, it silently fails to bind the ports. The new container runs but has **no host port mappings**, so the app connects to whatever else is on those ports — usually the old standalone orphan nodes, which are not part of a cluster.

Check with:
```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}' | grep redis
```

If you see `redis-node-*` containers alongside `redis-cluster`, the orphans are still alive. Fix:
```bash
docker compose down --remove-orphans
docker compose up -d
```

After this, `docker ps` should show `redis-demo-redis-cluster-1` with `0.0.0.0:7000-7005->7000-7005/tcp`.

#### 2. App is connecting to a standalone Redis on port 6379

If there is a separate `redis` container (or local Redis) running on `localhost:6379`, and the app was started without the `cluster` profile, it connects there instead. A standalone Redis reports `cluster_state:fail` because no cluster has been bootstrapped on it.

Verify nothing else owns port 6379 and that the app was started correctly:
```bash
export REDIS_PASSWORD=RedisCluster@2024
mvn spring-boot:run -Dspring-boot.run.profiles=cluster
```

**Quick diagnosis:** run `docker ps --format 'table {{.Names}}\t{{.Ports}}'` — the `redis-cluster` container must show port bindings on 7000–7005, and no other container should be bound to those ports.
