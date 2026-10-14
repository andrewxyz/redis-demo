package com.example.redisdemo.controller;

import io.lettuce.core.cluster.SlotHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ClusterInfo;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * Cluster inspection and simulation endpoints.
 * Only active when the "cluster" Spring profile is enabled.
 *
 * Start app with: mvn spring-boot:run -Dspring-boot.run.profiles=cluster
 */
@RestController
@RequestMapping("/api/cluster")
@Profile("cluster")
@RequiredArgsConstructor
@Slf4j
public class ClusterController {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * GET /api/cluster/info
     * Returns cluster health: state, slot coverage, node counts.
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> result = redisTemplate.execute((RedisCallback<Map<String, Object>>) conn -> {
            RedisClusterConnection cluster = (RedisClusterConnection) conn;
            ClusterInfo clusterInfo = cluster.clusterGetClusterInfo();
            Iterable<RedisClusterNode> nodes = cluster.clusterGetNodes();

            List<RedisClusterNode> nodeList = new ArrayList<>();
            nodes.forEach(nodeList::add);

            long masters = nodeList.stream()
                .filter(n -> n.getType() == RedisClusterNode.NodeType.MASTER).count();
            long replicas = nodeList.stream()
                .filter(n -> n.getType() == RedisClusterNode.NodeType.REPLICA).count();

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("clusterState",    clusterInfo.getState());
            map.put("slotsAssigned",   clusterInfo.getSlotsAssigned());
            map.put("slotsFail",       clusterInfo.getSlotsFail());
            map.put("knownNodes",      clusterInfo.getKnownNodes());
            map.put("clusterSize",     clusterInfo.getClusterSize());
            map.put("mastersCount",    masters);
            map.put("replicasCount",   replicas);
            return map;
        });
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/cluster/nodes
     * Lists every node: address, role, slot range (masters), master ID (replicas).
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<Map<String, Object>>> nodes() {
        List<Map<String, Object>> result = redisTemplate.execute((RedisCallback<List<Map<String, Object>>>) conn -> {
            RedisClusterConnection cluster = (RedisClusterConnection) conn;
            List<Map<String, Object>> list = new ArrayList<>();

            for (RedisClusterNode node : cluster.clusterGetNodes()) {
                Map<String, Object> nodeMap = new LinkedHashMap<>();
                nodeMap.put("id",        node.getId());
                nodeMap.put("address",   node.getHost() + ":" + node.getPort());
                nodeMap.put("role",      node.getType() != null ? node.getType().name() : "UNKNOWN");
                nodeMap.put("connected", node.isConnected());
                if (node.getSlotRange() != null && !node.getSlotRange().getSlots().isEmpty()) {
                    nodeMap.put("slotRange", node.getSlotRange().toString());
                }
                if (node.getMasterId() != null) {
                    nodeMap.put("masterId", node.getMasterId());
                }
                list.add(nodeMap);
            }
            return list;
        });
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/cluster/slot?key=mykey
     * Shows which hash slot and which master node owns a given key.
     * Hash slot is calculated client-side: CRC16(key) % 16384.
     */
    @GetMapping("/slot")
    public ResponseEntity<Map<String, Object>> slotForKey(@RequestParam String key) {
        int slot = SlotHash.getSlot(key.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = redisTemplate.execute((RedisCallback<Map<String, Object>>) conn -> {
            RedisClusterConnection cluster = (RedisClusterConnection) conn;
            RedisClusterNode master = cluster.clusterGetNodeForSlot(slot);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key",        key);
            map.put("hashSlot",   slot);
            map.put("totalSlots", 16384);
            map.put("formula",    "CRC16(\"" + key + "\") % 16384 = " + slot);
            if (master != null) {
                map.put("ownerNode", master.getHost() + ":" + master.getPort());
                map.put("ownerRole", master.getType() != null ? master.getType().name() : "UNKNOWN");
            }
            return map;
        });
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/cluster/load-test?count=50
     * Writes N keys into the cluster and reports how they distribute across the 3 master nodes.
     * Demonstrates that Redis Cluster shards data automatically by hash slot.
     */
    @PostMapping("/load-test")
    public ResponseEntity<Map<String, Object>> loadTest(@RequestParam(defaultValue = "50") int count) {
        String prefix = "test:load:";

        // Write all keys first
        for (int i = 0; i < count; i++) {
            redisTemplate.opsForValue().set(prefix + i, "value-" + i, Duration.ofMinutes(5));
        }

        // Analyse slot distribution without extra round-trips (slot calc is client-side)
        Map<String, Object> result = redisTemplate.execute((RedisCallback<Map<String, Object>>) conn -> {
            RedisClusterConnection cluster = (RedisClusterConnection) conn;
            Map<String, Integer> nodeCounts = new LinkedHashMap<>();
            List<Map<String, Object>> sampleKeys = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                String key = prefix + i;
                int slot = SlotHash.getSlot(key.getBytes(StandardCharsets.UTF_8));
                RedisClusterNode master = cluster.clusterGetNodeForSlot(slot);

                String nodeAddr = master != null ? master.getHost() + ":" + master.getPort() : "unknown";
                nodeCounts.merge(nodeAddr, 1, Integer::sum);

                if (i < 10) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("key",  key);
                    sample.put("slot", slot);
                    sample.put("node", nodeAddr);
                    sampleKeys.add(sample);
                }
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("keysWritten",        count);
            map.put("ttlMinutes",         5);
            map.put("distributionPerNode", nodeCounts);
            map.put("sampleKeys",         sampleKeys);
            map.put("note", "Keys distribute across nodes based on CRC16 hash slot (0–16383 split across 3 masters)");
            return map;
        });
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/cluster/replication
     * Shows the master → replica relationship for each master node.
     */
    @GetMapping("/replication")
    public ResponseEntity<Map<String, Object>> replication() {
        Map<String, Object> result = redisTemplate.execute((RedisCallback<Map<String, Object>>) conn -> {
            RedisClusterConnection cluster = (RedisClusterConnection) conn;

            // Build id → address map for all nodes
            Map<String, String> idToAddr = new LinkedHashMap<>();
            Map<String, List<String>> replicationMap = new LinkedHashMap<>();

            for (RedisClusterNode node : cluster.clusterGetNodes()) {
                idToAddr.put(node.getId(), node.getHost() + ":" + node.getPort());
                if (node.getType() == RedisClusterNode.NodeType.MASTER) {
                    replicationMap.put(node.getHost() + ":" + node.getPort(), new ArrayList<>());
                }
            }
            // Assign replicas to their master
            for (RedisClusterNode node : cluster.clusterGetNodes()) {
                if (node.getType() == RedisClusterNode.NodeType.REPLICA && node.getMasterId() != null) {
                    String masterAddr = idToAddr.get(node.getMasterId());
                    if (masterAddr != null && replicationMap.containsKey(masterAddr)) {
                        replicationMap.get(masterAddr).add(node.getHost() + ":" + node.getPort());
                    }
                }
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("masterReplicaMap", replicationMap);
            map.put("note", "Each master owns ~5461 hash slots; its replica is a hot standby for automatic failover");
            return map;
        });
        return ResponseEntity.ok(result);
    }
}
