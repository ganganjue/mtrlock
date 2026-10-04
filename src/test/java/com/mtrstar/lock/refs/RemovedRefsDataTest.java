package com.mtrstar.lock.refs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemovedRefsData} 单元测试（1.4.0）。
 *
 * <p>用包内构造注入临时文件 + 假时钟，完全避开 FabricLoader，纯 JVM 运行。
 * 覆盖：持久化往返、坏文件保护、loadFailed 语义、30 天清理、节流落盘、覆盖不追加。</p>
 */
class RemovedRefsDataTest {

    private static final String ROUTE_A = "route:000000000000000A";
    private static final String ROUTE_B = "route:000000000000000B";
    private static final String STATION_1 = "station:0000000000000001";
    private static final String STATION_2 = "station:0000000000000002";

    @TempDir
    Path tempDir;

    /** 可控假时钟（毫秒）。 */
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private Path file() {
        return tempDir.resolve("removed_refs.json");
    }

    private RemovedRefsData data() {
        return new RemovedRefsData(file(), now::get);
    }

    // =====================================================================
    // 内存语义
    // =====================================================================

    @Test
    @DisplayName("recordRemoved：新增返回 true，完全重复返回 false")
    void recordAndDuplicate() {
        final RemovedRefsData data = data();

        assertTrue(data.recordRemoved(ROUTE_A, 11L, STATION_1));
        assertFalse(data.recordRemoved(ROUTE_A, 11L, STATION_1), "同样内容重复记录应返回 false");
        assertEquals(1, data.size());
        assertEquals(1, data.routeCount());
        assertTrue(data.hasRemoved(ROUTE_A, 11L));
        assertFalse(data.hasRemoved(ROUTE_A, 12L));
        assertFalse(data.hasRemoved(ROUTE_B, 11L));
    }

    @Test
    @DisplayName("recordRemoved：父车站变化时覆盖更新（不追加），removedAt 刷新")
    void recordOverwritesOnStationChange() {
        final RemovedRefsData data = data();
        assertTrue(data.recordRemoved(ROUTE_A, 11L, STATION_1));
        final long firstAt = data.getRemoved(ROUTE_A).get(0).removedAt;

        now.addAndGet(1_000L);
        assertTrue(data.recordRemoved(ROUTE_A, 11L, STATION_2), "父车站变化应视为内容变化");

        final List<RemovedRefsData.RemovedRefEntry> refs = data.getRemoved(ROUTE_A);
        assertEquals(1, refs.size(), "覆盖式记录，列表长度不变");
        assertEquals(STATION_2, refs.get(0).stationObjectId);
        assertTrue(refs.get(0).removedAt > firstAt, "removedAt 应刷新");
        assertEquals(1, data.size());
    }

    @Test
    @DisplayName("往复失权/恢复：记录不膨胀（覆盖不追加）")
    void repeatedFlappingDoesNotGrow() {
        final RemovedRefsData data = data();
        for (int i = 0; i < 50; i++) {
            data.recordRemoved(ROUTE_A, 11L, STATION_1);
            data.removeRemoved(ROUTE_A, 11L);
        }
        data.recordRemoved(ROUTE_A, 11L, STATION_1);
        assertEquals(1, data.size(), "50 次横跳后仍只有 1 条记录");
        assertEquals(1, data.routeCount());
    }

    @Test
    @DisplayName("removeRemoved / removeRoute：删空后条目被清理，返回删除条数")
    void removeSemantics() {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);
        data.recordRemoved(ROUTE_A, 12L, STATION_1);
        data.recordRemoved(ROUTE_B, 21L, STATION_2);

        assertTrue(data.removeRemoved(ROUTE_A, 11L));
        assertFalse(data.removeRemoved(ROUTE_A, 11L), "重复删除返回 false");
        assertEquals(2, data.size());
        assertEquals(2, data.routeCount());

        assertEquals(1, data.removeRoute(ROUTE_A), "ROUTE_A 只剩 12 这一条");
        assertEquals(0, data.removeRoute(ROUTE_A), "线路已被清理，再删返回 0");
        assertEquals(1, data.routeCount());
        assertTrue(data.getRemoved(ROUTE_A).isEmpty());
    }

    @Test
    @DisplayName("参数安全：null / 空 routeId 一律无副作用，不 NPE")
    void nullSafe() {
        final RemovedRefsData data = data();
        assertFalse(data.recordRemoved(null, 1L, STATION_1));
        assertFalse(data.recordRemoved("", 1L, STATION_1));
        assertFalse(data.removeRemoved(null, 1L));
        assertEquals(0, data.removeRoute(null));
        assertEquals(0, data.removeRoute(""));
        assertTrue(data.getRemoved(null).isEmpty());
        assertTrue(data.getRemoved("").isEmpty());
        assertFalse(data.hasRemoved(null, 1L));
        assertFalse(data.hasRemoved("", 1L));
        assertEquals(0, data.size());
    }

    @Test
    @DisplayName("getAll：不可变快照，外部改不动内部数据")
    void getAllIsImmutableSnapshot() {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);
        final Map<String, List<RemovedRefsData.RemovedRefEntry>> snapshot = data.getAll();
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.containsKey(ROUTE_A));
        try {
            snapshot.put(ROUTE_B, List.of());
            org.junit.jupiter.api.Assertions.fail("快照应为不可变");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
    }

    // =====================================================================
    // 持久化往返
    // =====================================================================

    @Test
    @DisplayName("持久化往返：save → load 后内容与时间戳完全一致")
    void saveLoadRoundTrip() throws IOException {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);
        data.recordRemoved(ROUTE_A, 12L, STATION_1);
        now.addAndGet(500L);
        data.recordRemoved(ROUTE_B, 21L, null);
        final long expectedAt = now.get();
        data.save();

        assertTrue(Files.exists(file()), "save 应创建文件");
        final String json = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(json.contains(ROUTE_A) && json.contains(ROUTE_B));
        assertTrue(json.contains("platformId"));
        assertTrue(json.contains("stationObjectId"));
        assertTrue(json.contains("removedAt"));

        final RemovedRefsData loaded = data();
        loaded.load();
        assertFalse(loaded.isLoadFailed());
        assertEquals(2, loaded.routeCount());
        assertEquals(3, loaded.size());
        assertEquals(List.of(11L, 12L), loaded.getRemoved(ROUTE_A).stream()
                .map(ref -> ref.platformId).toList());
        assertEquals(expectedAt, loaded.getRemoved(ROUTE_B).get(0).removedAt);
        assertEquals(null, loaded.getRemoved(ROUTE_B).get(0).stationObjectId,
                "父车站已删（null）应能原样往返");
    }

    @Test
    @DisplayName("load：文件不存在 → 空账本 + loadFailed=false（首次启动）")
    void loadMissingFileIsEmptyLedger() {
        final RemovedRefsData data = data();
        data.load();
        assertFalse(data.isLoadFailed());
        assertEquals(0, data.size());
        assertFalse(Files.exists(file()), "只 load 不应创建文件");
    }

    @Test
    @DisplayName("坏文件保护：语法错误 → loadFailed=true，保留原内存")
    void brokenFileKeepsMemoryAndMarksFailed() throws IOException {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);
        Files.writeString(file(), "{ this is not json", StandardCharsets.UTF_8);

        data.load();

        assertTrue(data.isLoadFailed(), "坏文件必须置 loadFailed");
        assertEquals(1, data.size(), "坏文件不得破坏原内存");
        assertTrue(data.hasRemoved(ROUTE_A, 11L));

        data.save();
        assertEquals("{ this is not json", Files.readString(file(), StandardCharsets.UTF_8),
                "loadFailed 后 save 必须跳过，不能覆盖坏文件");
    }

    @Test
    @DisplayName("坏文件保护：根不是对象（数组/字符串）→ loadFailed=true")
    void nonObjectRootIsFailure() throws IOException {
        final RemovedRefsData data = data();
        Files.writeString(file(), "[1, 2, 3]", StandardCharsets.UTF_8);
        data.load();
        assertTrue(data.isLoadFailed());
    }

    @Test
    @DisplayName("load：单条坏记录只丢那一条，其余保留")
    void badEntryIsSkipped() throws IOException {
        Files.writeString(file(), "{\n"
                + "  \"route:000000000000000A\": {\n"
                + "    \"routeId\": \"route:000000000000000A\",\n"
                + "    \"refs\": [\n"
                + "      {\"platformId\": 11, \"stationObjectId\": \"station:0000000000000001\", \"removedAt\": 5},\n"
                + "      {\"stationObjectId\": \"station:0000000000000002\"},\n"
                + "      {\"platformId\": \"abc\"},\n"
                + "      {\"platformId\": 12, \"stationObjectId\": null, \"removedAt\": 7}\n"
                + "    ]\n"
                + "  },\n"
                + "  \"\": {\"refs\": [{\"platformId\": 99}]},\n"
                + "  \"route:000000000000000B\": {\"refs\": []}\n"
                + "}", StandardCharsets.UTF_8);

        final RemovedRefsData data = data();
        data.load();

        assertFalse(data.isLoadFailed());
        assertEquals(1, data.routeCount(), "空 routeId / 空 refs 的线路不应入库");
        assertEquals(2, data.size());
        assertEquals(11L, data.getRemoved(ROUTE_A).get(0).platformId);
        assertEquals(12L, data.getRemoved(ROUTE_A).get(1).platformId);
    }

    @Test
    @DisplayName("load：重复 (route, platform) 去重（保留最后一条）")
    void loadDeduplicates() throws IOException {
        Files.writeString(file(), "{\n"
                + "  \"route:000000000000000A\": {\n"
                + "    \"refs\": [\n"
                + "      {\"platformId\": 11, \"stationObjectId\": \"station:0000000000000001\", \"removedAt\": 5},\n"
                + "      {\"platformId\": 11, \"stationObjectId\": \"station:0000000000000002\", \"removedAt\": 9}\n"
                + "    ]\n"
                + "  }\n"
                + "}", StandardCharsets.UTF_8);

        final RemovedRefsData data = data();
        data.load();
        assertEquals(1, data.size());
        assertEquals(STATION_2, data.getRemoved(ROUTE_A).get(0).stationObjectId);
        assertEquals(9L, data.getRemoved(ROUTE_A).get(0).removedAt);
    }

    // =====================================================================
    // 30 天清理
    // =====================================================================

    @Test
    @DisplayName("30 天清理：removedAt 未知（<=0）的记录不清理")
    void cleanupIgnoresUnknownTimestamps() throws IOException {
        Files.writeString(file(), "{\n"
                + "  \"route:000000000000000A\": {\"refs\": ["
                + "{\"platformId\": 11, \"stationObjectId\": \"station:0000000000000001\"}]}\n"
                + "}", StandardCharsets.UTF_8);

        final RemovedRefsData data = data();
        data.load();
        assertEquals(1, data.size());
        assertEquals(0, data.cleanupExpired(), "removedAt 缺失（0）时按「未知」处理，不清理");
        assertEquals(1, data.size());
    }

    @Test
    @DisplayName("30 天清理：31 天前的记录被清，29 天前的保留")
    void cleanupExpiredBoundary() throws IOException {
        final long nowMillis = now.get();
        Files.writeString(file(), "{\n"
                + "  \"route:000000000000000A\": {\"refs\": ["
                + "{\"platformId\": 11, \"stationObjectId\": \"station:0000000000000001\", \"removedAt\": "
                + (nowMillis - RemovedRefsData.RETENTION_MILLIS - 1) + "}]},\n"
                + "  \"route:000000000000000B\": {\"refs\": ["
                + "{\"platformId\": 21, \"stationObjectId\": \"station:0000000000000002\", \"removedAt\": "
                + (nowMillis - RemovedRefsData.RETENTION_MILLIS + 3_600_000L) + "}]}\n"
                + "}", StandardCharsets.UTF_8);

        final RemovedRefsData data = data();
        data.load();
        assertEquals(2, data.size());

        assertEquals(1, data.cleanupExpired(), "只清 31 天前那条");
        assertEquals(1, data.routeCount());
        assertEquals(0, data.getRemoved(ROUTE_A).size());
        assertEquals(1, data.getRemoved(ROUTE_B).size());

        // 清理结果能落盘（空账本也可以写）
        data.save();
        final RemovedRefsData reloaded = data();
        reloaded.load();
        assertFalse(reloaded.isLoadFailed());
        assertEquals(1, reloaded.size());
        assertEquals(21L, reloaded.getRemoved(ROUTE_B).get(0).platformId);
    }

    @Test
    @DisplayName("落盘：全部记录清空后 save 写空对象（不做「非空文件守卫」）")
    void saveEmptyLedgerIsAllowed() throws IOException {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);
        data.save();
        assertTrue(Files.size(file()) > 2L);

        data.removeRemoved(ROUTE_A, 11L);
        data.save();
        assertFalse(data.isLoadFailed());
        assertEquals("{}", Files.readString(file(), StandardCharsets.UTF_8).trim(),
                "账本为空是合法状态，必须能落盘清空");
    }

    // =====================================================================
    // 节流落盘
    // =====================================================================

    @Test
    @DisplayName("节流：debounce 未到时 afterReconcile 不写盘，到点后写一次")
    void debounceSavesAfterWindow() throws IOException {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);

        data.afterReconcile();
        assertFalse(Files.exists(file()), "刚变更就写盘说明 debounce 失效");

        now.addAndGet(RemovedRefsData.SAVE_DEBOUNCE_MILLIS - 1);
        data.afterReconcile();
        assertFalse(Files.exists(file()), "debounce 窗口还没到");

        now.addAndGet(2L);
        data.afterReconcile();
        assertTrue(Files.exists(file()), "debounce 到点应落盘");

        // 落盘后计数复位：再变更 → 窗口重新计时
        data.recordRemoved(ROUTE_A, 12L, STATION_1);
        data.afterReconcile();
        final RemovedRefsData reloaded = data();
        reloaded.load();
        assertEquals(1, reloaded.getRemoved(ROUTE_A).size(), "刚重新计时时磁盘上还是旧快照");
    }

    @Test
    @DisplayName("节流：即使 debounce 没到，累计 10 次对账也落一次盘")
    void reconcileCountTriggersSave() {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE_A, 11L, STATION_1);

        for (int i = 1; i < RemovedRefsData.SAVE_EVERY_N_RECONCILES; i++) {
            data.afterReconcile();
            assertFalse(Files.exists(file()), "第 " + i + " 次对账不应落盘");
        }
        data.afterReconcile();
        assertTrue(Files.exists(file()), "第 10 次对账应强制落盘");
    }

    @Test
    @DisplayName("节流：无变更时对账多少次都不写盘")
    void noChangeNeverSaves() {
        final RemovedRefsData data = data();
        for (int i = 0; i < RemovedRefsData.SAVE_EVERY_N_RECONCILES * 3; i++) {
            data.afterReconcile();
            now.addAndGet(RemovedRefsData.SAVE_DEBOUNCE_MILLIS);
        }
        assertFalse(Files.exists(file()), "没有变更就不该产生 IO");
    }

    @Test
    @DisplayName("loadFailed 时：afterReconcile 也不得写盘")
    void loadFailedBlocksThrottledSave() throws IOException {
        final RemovedRefsData data = data();
        Files.writeString(file(), "}{", StandardCharsets.UTF_8);
        data.load();
        assertTrue(data.isLoadFailed());

        for (int i = 0; i < RemovedRefsData.SAVE_EVERY_N_RECONCILES; i++) {
            data.afterReconcile();
            now.addAndGet(RemovedRefsData.SAVE_DEBOUNCE_MILLIS);
        }
        assertEquals("}{", Files.readString(file(), StandardCharsets.UTF_8));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    @Test
    @DisplayName("objectIdOf：与 MTR getHexId 一致（16 位大写 HEX）")
    void objectIds() {
        assertNotNull(RemovedRefsData.stationObjectId(1L));
        assertEquals("station:0000000000000001", RemovedRefsData.stationObjectId(1L));
        assertEquals("platform:00000000000000FF", RemovedRefsData.platformObjectId(255L));
        assertEquals("route:000000000000000A", RemovedRefsData.objectIdOf("route", 10L));
    }

    @Test
    @DisplayName("readFile 工具在文件不存在时返回 null")
    void readFileMissing() throws IOException {
        assertEquals(null, RemovedRefsData.readFile(tempDir.resolve("nope.json")));
    }
}
