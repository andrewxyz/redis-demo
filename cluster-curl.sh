#!/bin/bash
# cluster-curl.sh — End-to-end curl scenarios for the Redis Cluster demo
#
# Prerequisites:
#   1. Docker cluster running:  docker compose up -d
#   2. App running with cluster profile:
#        export REDIS_PASSWORD=RedisCluster@2024
#        mvn spring-boot:run -Dspring-boot.run.profiles=cluster
#
# Usage:
#   bash cluster-curl.sh            # run all scenarios
#   bash cluster-curl.sh slot       # run one section by name:
#                                   #   info | nodes | slot | load | replication
#                                   #   products | cache | sharding

BASE="http://localhost:8080"
SEP="─────────────────────────────────────────────────────────────"

header() { echo; echo "$SEP"; echo "  $1"; echo "$SEP"; }
run()    { echo; echo "▶ $1"; echo "  $ $2"; eval "$2" | python3 -m json.tool 2>/dev/null || eval "$2"; echo; }

# ══════════════════════════════════════════════════════════════
# 1. CLUSTER INFO  —  GET /api/cluster/info
# ══════════════════════════════════════════════════════════════
section_info() {
  header "1. Cluster Info — cluster_state, slot coverage, node counts"

  run "Healthy cluster — expect cluster_state:ok and slotsAssigned:16384" \
    "curl -s $BASE/api/cluster/info"
}

# ══════════════════════════════════════════════════════════════
# 2. CLUSTER NODES  —  GET /api/cluster/nodes
# ══════════════════════════════════════════════════════════════
section_nodes() {
  header "2. Cluster Nodes — all 6 nodes with role, address, slot range"

  run "All nodes — expect 3 MASTER + 3 REPLICA entries" \
    "curl -s $BASE/api/cluster/nodes"

  echo "  TIP: Masters show 'slotRange', replicas show 'masterId'."
}

# ══════════════════════════════════════════════════════════════
# 3. HASH SLOT  —  GET /api/cluster/slot?key=
# ══════════════════════════════════════════════════════════════
section_slot() {
  header "3. Hash Slot — CRC16(key) % 16384, which master owns the slot"

  run "Key 'foo' — shows slot number and owner node" \
    "curl -s '$BASE/api/cluster/slot?key=foo'"

  run "Key 'bar' — different key → different slot" \
    "curl -s '$BASE/api/cluster/slot?key=bar'"

  run "Key 'baz'" \
    "curl -s '$BASE/api/cluster/slot?key=baz'"

  run "Hash tag {user}:123 — braces force slot on the tag only, so {user}:123 and {user}:456 land on the SAME master" \
    "curl -s '$BASE/api/cluster/slot?key=%7Buser%7D:123'"

  run "Hash tag {user}:456 — same slot as {user}:123 above (both hash on 'user')" \
    "curl -s '$BASE/api/cluster/slot?key=%7Buser%7D:456'"

  echo "  TIP: Compare the hashSlot values — {user}:123 and {user}:456 will be identical."
}

# ══════════════════════════════════════════════════════════════
# 4. LOAD TEST  —  POST /api/cluster/load-test?count=
# ══════════════════════════════════════════════════════════════
section_load() {
  header "4. Load Test — write N keys, observe how they distribute across masters"

  run "Write 30 keys — distributionPerNode shows roughly 10 keys per master" \
    "curl -s -X POST '$BASE/api/cluster/load-test?count=30'"

  run "Write 100 keys — larger sample, distribution should be ~33 per master" \
    "curl -s -X POST '$BASE/api/cluster/load-test?count=100'"

  echo "  TIP: CRC16 gives a uniform distribution — keys split roughly equally"
  echo "       across the 3 masters regardless of key content."
}

# ══════════════════════════════════════════════════════════════
# 5. REPLICATION  —  GET /api/cluster/replication
# ══════════════════════════════════════════════════════════════
section_replication() {
  header "5. Replication Map — master → replica for each of the 3 masters"

  run "Master-replica pairs — each master maps to exactly one replica" \
    "curl -s $BASE/api/cluster/replication"

  echo "  TIP: masterReplicaMap shows the hot-standby assignment."
  echo "       If a master fails, its replica auto-promotes."
}

# ══════════════════════════════════════════════════════════════
# 6. PRODUCTS (4 CACHE PATTERNS still work in cluster mode)
# ══════════════════════════════════════════════════════════════
section_products() {
  header "6. Product Cache — the 4 patterns work transparently in cluster mode"

  run "GET all products — first call hits DB, caches under myapp:products:all" \
    "curl -s $BASE/api/products"

  run "GET product 1 — cached as myapp:products:1 (TTL visible via /api/cache/ttl/...)" \
    "curl -s $BASE/api/products/1"

  run "GET product 1 again — cache HIT (watch the app logs for TRACE cache hit)" \
    "curl -s $BASE/api/products/1"

  run "GET by category Laptops — cached as myapp:categories:Laptops" \
    "curl -s $BASE/api/products/category/Laptops"

  run "CREATE a new product — @CachePut writes it directly into the cache" \
    "curl -s -X POST $BASE/api/products \
      -H 'Content-Type: application/json' \
      -d '{\"name\":\"Cluster Test Product\",\"price\":99.99,\"category\":\"Test\"}'"

  run "UPDATE product 1 — @CachePut refreshes myapp:products:1" \
    "curl -s -X PUT $BASE/api/products/1 \
      -H 'Content-Type: application/json' \
      -d '{\"name\":\"Laptop Pro X Updated\",\"price\":1399.99,\"category\":\"Laptops\"}'"

  run "GET product 1 after update — should return the updated name/price from cache" \
    "curl -s $BASE/api/products/1"

  run "DELETE product 1 — @CacheEvict removes myapp:products:1 and myapp:products:all" \
    "curl -s -X DELETE $BASE/api/products/1"

  run "GET product 1 after delete — cache miss → DB miss → 404 (or empty)" \
    "curl -s -o /dev/null -w '%{http_code}' $BASE/api/products/1"
}

# ══════════════════════════════════════════════════════════════
# 7. CACHE INSPECTION  —  /api/cache/* (Pattern 2 & 3 visibility)
# ══════════════════════════════════════════════════════════════
section_cache() {
  header "7. Cache Inspection — see keys, TTL, and raw JSON values in Redis"

  # Warm the cache first
  curl -s "$BASE/api/products" > /dev/null
  curl -s "$BASE/api/products/2" > /dev/null
  curl -s "$BASE/api/products/category/Laptops" > /dev/null

  run "List all myapp:* keys — shows Pattern 3 key naming (myapp:<cache>:<id>)" \
    "curl -s $BASE/api/cache/keys"

  run "TTL for myapp:products:2 — shows Pattern 2: remaining seconds before eviction" \
    "curl -s $BASE/api/cache/ttl/myapp:products:2"

  run "Raw JSON value — shows Pattern 1: object serialized as JSON in Redis" \
    "curl -s $BASE/api/cache/value/myapp:products:2"

  run "Flush all cache keys — wipe Redis so you can observe cold-cache behaviour" \
    "curl -s -X DELETE $BASE/api/cache/flush"

  run "List keys after flush — should be empty" \
    "curl -s $BASE/api/cache/keys"
}

# ══════════════════════════════════════════════════════════════
# 8. SHARDING VISIBILITY  —  slot lookup for product cache keys
# ══════════════════════════════════════════════════════════════
section_sharding() {
  header "8. Sharding Visibility — which master holds each product cache key"

  # Warm the cache so the keys actually exist in Redis
  curl -s "$BASE/api/products/2" > /dev/null
  curl -s "$BASE/api/products/3" > /dev/null
  curl -s "$BASE/api/products/category/Laptops" > /dev/null

  run "Slot for myapp:products:2" \
    "curl -s '$BASE/api/cluster/slot?key=myapp:products:2'"

  run "Slot for myapp:products:3" \
    "curl -s '$BASE/api/cluster/slot?key=myapp:products:3'"

  run "Slot for myapp:categories:Laptops" \
    "curl -s '$BASE/api/cluster/slot?key=myapp:categories:Laptops'"

  run "Slot for myapp:products:all" \
    "curl -s '$BASE/api/cluster/slot?key=myapp:products:all'"

  echo "  TIP: Notice that different cache keys land on different masters."
  echo "       Redis distributes them automatically — no code change needed."
}

# ══════════════════════════════════════════════════════════════
# DISPATCH
# ══════════════════════════════════════════════════════════════
case "${1:-all}" in
  info)        section_info ;;
  nodes)       section_nodes ;;
  slot)        section_slot ;;
  load)        section_load ;;
  replication) section_replication ;;
  products)    section_products ;;
  cache)       section_cache ;;
  sharding)    section_sharding ;;
  all)
    section_info
    section_nodes
    section_slot
    section_load
    section_replication
    section_products
    section_cache
    section_sharding
    header "Done — all cluster scenarios complete"
    ;;
  *)
    echo "Usage: bash cluster-curl.sh [info|nodes|slot|load|replication|products|cache|sharding|all]"
    exit 1
    ;;
esac
