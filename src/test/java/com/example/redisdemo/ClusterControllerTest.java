package com.example.redisdemo;

import com.example.redisdemo.controller.ClusterController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.ClusterInfo;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClusterController.
 *
 * No Spring context or Redis instance needed — all Redis interactions are
 * intercepted via a mock RedisClusterConnection injected into the execute() callback.
 *
 * Important: always create mock nodes in a local variable BEFORE any when() call.
 * Calling masterNode() / replicaNode() inside a thenReturn() argument causes
 * UnfinishedStubbingException because Mockito is still in pending-stub state.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterControllerTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RedisClusterConnection clusterConn;
    @Mock private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private ClusterController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void routeExecuteCallbackThroughMockConnection() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            return cb.doInRedis(clusterConn);
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    // MUST be called before any when() to avoid UnfinishedStubbingException.

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

    private RedisClusterNode replicaNode(String id, String host, int port, String masterId) {
        RedisClusterNode node = mock(RedisClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getHost()).thenReturn(host);
        when(node.getPort()).thenReturn(port);
        when(node.getType()).thenReturn(RedisClusterNode.NodeType.REPLICA);
        when(node.isConnected()).thenReturn(true);
        when(node.getMasterId()).thenReturn(masterId);
        when(node.getSlotRange()).thenReturn(null);
        return node;
    }

    // ── /api/cluster/info ──────────────────────────────────────────────────────

    @Test
    void info_returnsClusterStateAndCorrectNodeCounts() {
        ClusterInfo clusterInfo = mock(ClusterInfo.class);
        when(clusterInfo.getState()).thenReturn("ok");
        when(clusterInfo.getSlotsAssigned()).thenReturn(16384L);
        when(clusterInfo.getSlotsFail()).thenReturn(0L);
        when(clusterInfo.getKnownNodes()).thenReturn(6L);
        when(clusterInfo.getClusterSize()).thenReturn(3L);

        // Create nodes before any when() to avoid UnfinishedStubbingException
        List<RedisClusterNode> nodes = List.of(
            masterNode("m1", "127.0.0.1", 7000),
            masterNode("m2", "127.0.0.1", 7001),
            masterNode("m3", "127.0.0.1", 7002),
            replicaNode("r1", "127.0.0.1", 7003, "m1"),
            replicaNode("r2", "127.0.0.1", 7004, "m2"),
            replicaNode("r3", "127.0.0.1", 7005, "m3")
        );

        when(clusterConn.clusterGetClusterInfo()).thenReturn(clusterInfo);
        when(clusterConn.clusterGetNodes()).thenReturn(nodes);

        ResponseEntity<Map<String, Object>> response = controller.info();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("clusterState")).isEqualTo("ok");
        assertThat(body.get("slotsAssigned")).isEqualTo(16384L);
        assertThat(body.get("clusterSize")).isEqualTo(3L);
        assertThat(body.get("mastersCount")).isEqualTo(3L);
        assertThat(body.get("replicasCount")).isEqualTo(3L);
    }

    @Test
    void info_distinguishesMastersFromReplicas() {
        ClusterInfo clusterInfo = mock(ClusterInfo.class);
        when(clusterInfo.getState()).thenReturn("ok");
        when(clusterInfo.getSlotsAssigned()).thenReturn(16384L);
        when(clusterInfo.getSlotsFail()).thenReturn(0L);
        when(clusterInfo.getKnownNodes()).thenReturn(2L);
        when(clusterInfo.getClusterSize()).thenReturn(1L);

        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        RedisClusterNode replica = replicaNode("r1", "127.0.0.1", 7003, "m1");

        when(clusterConn.clusterGetClusterInfo()).thenReturn(clusterInfo);
        when(clusterConn.clusterGetNodes()).thenReturn(List.of(master, replica));

        Map<String, Object> body = controller.info().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("mastersCount")).isEqualTo(1L);
        assertThat(body.get("replicasCount")).isEqualTo(1L);
    }

    // ── /api/cluster/nodes ─────────────────────────────────────────────────────

    @Test
    void nodes_returnsAllNodesWithAddressAndRole() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        RedisClusterNode replica = replicaNode("r1", "127.0.0.1", 7003, "m1");
        when(clusterConn.clusterGetNodes()).thenReturn(List.of(master, replica));

        ResponseEntity<List<Map<String, Object>>> response = controller.nodes();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).hasSize(2);

        Map<String, Object> masterEntry = body.stream()
            .filter(n -> "MASTER".equals(n.get("role"))).findFirst().orElseThrow();
        assertThat(masterEntry.get("address")).isEqualTo("127.0.0.1:7000");
        assertThat(masterEntry.get("connected")).isEqualTo(true);

        Map<String, Object> replicaEntry = body.stream()
            .filter(n -> "REPLICA".equals(n.get("role"))).findFirst().orElseThrow();
        assertThat(replicaEntry.get("address")).isEqualTo("127.0.0.1:7003");
        assertThat(replicaEntry.get("masterId")).isEqualTo("m1");
    }

    @Test
    void nodes_returnsAllSixClusterNodes() {
        List<RedisClusterNode> sixNodes = List.of(
            masterNode("m1", "127.0.0.1", 7000),
            masterNode("m2", "127.0.0.1", 7001),
            masterNode("m3", "127.0.0.1", 7002),
            replicaNode("r1", "127.0.0.1", 7003, "m1"),
            replicaNode("r2", "127.0.0.1", 7004, "m2"),
            replicaNode("r3", "127.0.0.1", 7005, "m3")
        );
        when(clusterConn.clusterGetNodes()).thenReturn(sixNodes);

        List<Map<String, Object>> body = controller.nodes().getBody();

        assertThat(body).hasSize(6);
        assertThat(body.stream().filter(n -> "MASTER".equals(n.get("role")))).hasSize(3);
        assertThat(body.stream().filter(n -> "REPLICA".equals(n.get("role")))).hasSize(3);
    }

    // ── /api/cluster/slot ──────────────────────────────────────────────────────

    @Test
    void slotForKey_returnsValidHashSlotBetween0And16383() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7001);
        when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);

        Map<String, Object> body = controller.slotForKey("foo").getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("key")).isEqualTo("foo");
        int slot = (int) body.get("hashSlot");
        assertThat(slot).isBetween(0, 16383);
        assertThat(body.get("totalSlots")).isEqualTo(16384);
    }

    @Test
    void slotForKey_returnsOwnerNodeAddress() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7001);
        when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);

        Map<String, Object> body = controller.slotForKey("myproduct").getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("ownerNode")).isEqualTo("127.0.0.1:7001");
        assertThat(body.get("ownerRole")).isEqualTo("MASTER");
    }

    @Test
    void slotForKey_differentKeysMayProduceDifferentSlots() {
        // Hash slots are deterministic — same key always maps to same slot
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);

        int slotFoo = (int) controller.slotForKey("foo").getBody().get("hashSlot");
        int slotBar = (int) controller.slotForKey("bar").getBody().get("hashSlot");

        // "foo" and "bar" hash to different CRC16 slots (deterministic, not random)
        assertThat(slotFoo).isNotEqualTo(slotBar);
    }

    // ── /api/cluster/load-test ─────────────────────────────────────────────────

    @Test
    void loadTest_writesExactNumberOfKeysToRedis() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);

        controller.loadTest(15);

        verify(valueOps, times(15)).set(anyString(), any(), any());
    }

    @Test
    void loadTest_returnsDistributionGroupedByNode() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);

        Map<String, Object> body = controller.loadTest(10).getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("keysWritten")).isEqualTo(10);

        @SuppressWarnings("unchecked")
        Map<String, Integer> dist = (Map<String, Integer>) body.get("distributionPerNode");
        assertThat(dist).containsKey("127.0.0.1:7000");
        assertThat(dist.get("127.0.0.1:7000")).isEqualTo(10);
    }

    @Test
    void loadTest_sampleKeysContainSlotAndNodeInfo() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(clusterConn.clusterGetNodeForSlot(anyInt())).thenReturn(master);

        Map<String, Object> body = controller.loadTest(5).getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) body.get("sampleKeys");
        assertThat(samples).hasSize(5);
        assertThat(samples.get(0)).containsKeys("key", "slot", "node");
    }

    // ── /api/cluster/replication ───────────────────────────────────────────────

    @Test
    void replication_mapsEachMasterToItsReplica() {
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        RedisClusterNode replica = replicaNode("r1", "127.0.0.1", 7003, "m1");
        when(clusterConn.clusterGetNodes()).thenReturn(List.of(master, replica));

        Map<String, Object> body = controller.replication().getBody();

        assertThat(body).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, List<String>> map = (Map<String, List<String>>) body.get("masterReplicaMap");
        assertThat(map).containsKey("127.0.0.1:7000");
        assertThat(map.get("127.0.0.1:7000")).containsExactly("127.0.0.1:7003");
    }

    @Test
    void replication_returnsAllThreeMasterReplicaPairs() {
        List<RedisClusterNode> nodes = List.of(
            masterNode("m1", "127.0.0.1", 7000),
            masterNode("m2", "127.0.0.1", 7001),
            masterNode("m3", "127.0.0.1", 7002),
            replicaNode("r1", "127.0.0.1", 7003, "m1"),
            replicaNode("r2", "127.0.0.1", 7004, "m2"),
            replicaNode("r3", "127.0.0.1", 7005, "m3")
        );
        when(clusterConn.clusterGetNodes()).thenReturn(nodes);

        @SuppressWarnings("unchecked")
        Map<String, List<String>> map = (Map<String, List<String>>)
            controller.replication().getBody().get("masterReplicaMap");

        assertThat(map).hasSize(3);
        assertThat(map.get("127.0.0.1:7000")).containsExactly("127.0.0.1:7003");
        assertThat(map.get("127.0.0.1:7001")).containsExactly("127.0.0.1:7004");
        assertThat(map.get("127.0.0.1:7002")).containsExactly("127.0.0.1:7005");
    }

    @Test
    void replication_masterWithNoReplicaHasEmptyList() {
        // Edge case: a master with no replica assigned yet
        RedisClusterNode master = masterNode("m1", "127.0.0.1", 7000);
        when(clusterConn.clusterGetNodes()).thenReturn(List.of(master));

        @SuppressWarnings("unchecked")
        Map<String, List<String>> map = (Map<String, List<String>>)
            controller.replication().getBody().get("masterReplicaMap");

        assertThat(map).containsKey("127.0.0.1:7000");
        assertThat(map.get("127.0.0.1:7000")).isEmpty();
    }
}
