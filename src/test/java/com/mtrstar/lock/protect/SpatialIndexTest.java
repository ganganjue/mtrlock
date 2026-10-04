package com.mtrstar.lock.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SpatialIndex} 单元测试（1.3.0）。 */
class SpatialIndexTest {

    private static final String STATION_A = "station:0B0829457F350DE9";
    private static final String STATION_B = "station:1111111111111111";
    private static final String DEPOT_A = "depot:2222222222222222";

    private static List<String> idsAt(SpatialIndex index, long x, long z) {
        return index.lookup(x, z).stream().map(ObjectRange::objectId).collect(Collectors.toList());
    }

    // =====================================================================
    // chunk 坐标与打包键
    // =====================================================================

    @Test
    @DisplayName("chunkOf：负数向负无穷取整，不向 0 截断")
    void chunkOfNegativeFloors() {
        assertEquals(0, SpatialIndex.chunkOf(0));
        assertEquals(0, SpatialIndex.chunkOf(15));
        assertEquals(1, SpatialIndex.chunkOf(16));
        assertEquals(-1, SpatialIndex.chunkOf(-1));
        assertEquals(-1, SpatialIndex.chunkOf(-16));
        assertEquals(-2, SpatialIndex.chunkOf(-17));
        assertEquals(-2, SpatialIndex.chunkOf(-32));
        assertEquals(-3, SpatialIndex.chunkOf(-33));
    }

    @Test
    @DisplayName("chunkKey：负数 chunk 不因符号扩展碰撞（-1/-1 与 -1/0、0/-1 必须不同）")
    void chunkKeyNegativeNoCollision() {
        assertNotEquals(SpatialIndex.chunkKey(-1, -1), SpatialIndex.chunkKey(-1, 0));
        assertNotEquals(SpatialIndex.chunkKey(-1, -1), SpatialIndex.chunkKey(0, -1));
        assertNotEquals(SpatialIndex.chunkKey(-1, 0), SpatialIndex.chunkKey(0, -1));
        assertNotEquals(SpatialIndex.chunkKey(-1, 0), SpatialIndex.chunkKey(0, 0));
    }

    @Test
    @DisplayName("chunkKey：正负混合网格内所有 (chunkX,chunkZ) 键两两不碰撞")
    void chunkKeyGridIsInjective() {
        final Set<Long> keys = new HashSet<>();
        for (int cx = -8; cx <= 8; cx++) {
            for (int cz = -8; cz <= 8; cz++) {
                final long key = SpatialIndex.chunkKey(cx, cz);
                assertTrue(keys.add(key), "键碰撞: (" + cx + "," + cz + ") -> " + key);
            }
        }
        assertEquals(17 * 17, keys.size());
    }

    @Test
    @DisplayName("chunkKeyOfBlock：与 chunkOf + chunkKey 一致")
    void chunkKeyOfBlockMatches() {
        assertEquals(SpatialIndex.chunkKey(0, 0), SpatialIndex.chunkKeyOfBlock(0, 0));
        assertEquals(SpatialIndex.chunkKey(-1, -1), SpatialIndex.chunkKeyOfBlock(-1, -1));
        assertEquals(SpatialIndex.chunkKey(-1, -1), SpatialIndex.chunkKeyOfBlock(-16, -16));
        assertEquals(SpatialIndex.chunkKey(-2, 3), SpatialIndex.chunkKeyOfBlock(-20, 50));
    }

    // =====================================================================
    // 写入 / 查询
    // =====================================================================

    @Test
    @DisplayName("空索引：查询返回空列表而不是 null")
    void emptyIndexLookup() {
        final SpatialIndex index = new SpatialIndex();
        assertTrue(index.isEmpty());
        assertEquals(0, index.size());
        final List<ObjectRange> hits = index.lookup(0, 0);
        assertNotNull(hits);
        assertTrue(hits.isEmpty());
    }

    @Test
    @DisplayName("put + lookup：范围内命中，范围外不命中")
    void putAndLookup() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));

        assertEquals(1, index.size());
        assertEquals(List.of(STATION_A), idsAt(index, 5, 5));
        assertEquals(List.of(STATION_A), idsAt(index, 0, 0));
        assertEquals(List.of(STATION_A), idsAt(index, 10, 10));
        assertTrue(index.lookup(11, 5).isEmpty());
        assertTrue(index.lookup(-1, 5).isEmpty());
    }

    @Test
    @DisplayName("put：range 为 null 忽略")
    void putNullIgnored() {
        final SpatialIndex index = new SpatialIndex();
        index.put(null);
        assertTrue(index.isEmpty());
    }

    @Test
    @DisplayName("跨 chunk 边界：范围覆盖的每个 chunk 都能命中，相邻不覆盖的 chunk 不命中")
    void crossChunkBoundary() {
        final SpatialIndex index = new SpatialIndex();
        // 15..16 横跨 chunk 0 与 chunk 1；z 固定在 0（chunk 0）
        index.put(ObjectRange.ofCorners(STATION_A, 15, 0, 16, 0));

        assertEquals(List.of(STATION_A), idsAt(index, 15, 0));
        assertEquals(List.of(STATION_A), idsAt(index, 16, 0));
        assertTrue(index.lookup(14, 0).isEmpty());
        assertTrue(index.lookup(17, 0).isEmpty());
        // chunk 0 与 chunk 1 两列都有登记
        assertEquals(2, index.chunkCount());
    }

    @Test
    @DisplayName("跨负 chunk 边界：-1 与 0 都命中")
    void crossNegativeChunkBoundary() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, -1, -1, 0, 0));

        assertEquals(List.of(STATION_A), idsAt(index, -1, -1));
        assertEquals(List.of(STATION_A), idsAt(index, 0, 0));
        assertEquals(List.of(STATION_A), idsAt(index, -1, 0));
        assertTrue(index.lookup(-2, -1).isEmpty());
        assertTrue(index.lookup(1, 1).isEmpty());
        // (-1,-1) 与 (0,0) 两列 → 4 个 chunk
        assertEquals(4, index.chunkCount());
    }

    @Test
    @DisplayName("重叠对象：同一点返回全部覆盖它的范围")
    void overlappingRangesReturned() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 20, 20));
        index.put(ObjectRange.ofCorners(DEPOT_A, 10, 10, 30, 30));

        assertEquals(Set.of(STATION_A, DEPOT_A), new HashSet<>(idsAt(index, 15, 15)));
        assertEquals(Set.of(STATION_A), new HashSet<>(idsAt(index, 5, 5)));
        assertEquals(Set.of(DEPOT_A), new HashSet<>(idsAt(index, 25, 25)));
        assertTrue(index.lookup(40, 40).isEmpty());
    }

    // =====================================================================
    // 幂等 / 覆盖 / 移除
    // =====================================================================

    @Test
    @DisplayName("幂等：同一 objectId 改范围只保留新范围，旧区域立刻失效，不累加")
    void putOverwritesSameObject() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        assertEquals(List.of(STATION_A), idsAt(index, 5, 5));

        index.put(ObjectRange.ofCorners(STATION_A, 100, 100, 110, 110));

        assertEquals(1, index.size());
        assertTrue(index.lookup(5, 5).isEmpty(), "旧范围必须失效");
        assertEquals(List.of(STATION_A), idsAt(index, 105, 105));
    }

    @Test
    @DisplayName("幂等：范围缩小后旧 chunk 登记被清掉，chunkCount 跟着降")
    void overwriteShrinksChunks() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 100, 100));
        assertEquals(49, index.chunkCount()); // chunk 0..6 各轴 → 7*7

        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 0, 0));
        assertEquals(1, index.chunkCount());
        assertTrue(index.lookup(100, 100).isEmpty());
    }

    @Test
    @DisplayName("幂等：反复写入同一范围不产生重复命中")
    void repeatedPutNoDuplicates() {
        final SpatialIndex index = new SpatialIndex();
        for (int i = 0; i < 5; i++) {
            index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        }
        assertEquals(1, index.size());
        assertEquals(List.of(STATION_A), idsAt(index, 5, 5));
    }

    @Test
    @DisplayName("remove：移除后立即失效；未知 id / null 无副作用")
    void removeRange() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        index.put(ObjectRange.ofCorners(DEPOT_A, 0, 0, 10, 10));

        index.remove(STATION_A);
        assertEquals(List.of(DEPOT_A), idsAt(index, 5, 5));
        assertEquals(1, index.size());

        index.remove("station:FFFFFFFFFFFFFFFF");
        index.remove((String) null);
        assertEquals(1, index.size());

        index.remove(DEPOT_A);
        assertTrue(index.lookup(5, 5).isEmpty());
        assertTrue(index.isEmpty());
    }

    // =====================================================================
    // rebuild
    // =====================================================================

    @Test
    @DisplayName("rebuild：整体替换，旧对象不在新快照里就消失")
    void rebuildReplacesEverything() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));

        index.rebuild(List.of(ObjectRange.ofCorners(DEPOT_A, 100, 100, 110, 110)));

        assertTrue(index.lookup(5, 5).isEmpty());
        assertEquals(List.of(DEPOT_A), idsAt(index, 105, 105));
        assertEquals(1, index.size());
    }

    @Test
    @DisplayName("rebuild：忽略 null、按 objectId 去重（后者胜）")
    void rebuildIgnoresNullAndDedupes() {
        final SpatialIndex index = new SpatialIndex();
        final List<ObjectRange> ranges = new ArrayList<>(Arrays.asList(
                ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10),
                null,
                ObjectRange.ofCorners(STATION_A, 50, 50, 60, 60)));
        index.rebuild(ranges);

        assertEquals(1, index.size());
        assertTrue(index.lookup(5, 5).isEmpty());
        assertEquals(List.of(STATION_A), idsAt(index, 55, 55));
    }

    @Test
    @DisplayName("rebuild(null) / rebuild(空)：清空索引，不 NPE")
    void rebuildNullClears() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        index.rebuild(null);
        assertTrue(index.isEmpty());
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        index.rebuild(List.of());
        assertTrue(index.isEmpty());
    }

    @Test
    @DisplayName("clear：清空对象与 chunk 表")
    void clearIndex() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        index.put(ObjectRange.ofCorners(STATION_B, -50, -50, -40, -40));
        index.clear();
        assertTrue(index.isEmpty());
        assertEquals(0, index.chunkCount());
        assertTrue(index.lookup(0, 0).isEmpty());
    }

    // =====================================================================
    // 快照不可变
    // =====================================================================

    @Test
    @DisplayName("lookup 返回不可变列表，调用方修改会抛 UnsupportedOperationException")
    void lookupReturnsImmutableList() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, 0, 0, 10, 10));
        final List<ObjectRange> hits = index.lookup(5, 5);
        assertEquals(1, hits.size());
        assertThrows(UnsupportedOperationException.class,
                () -> hits.add(ObjectRange.ofCorners(STATION_B, 0, 0, 1, 1)));
    }

    @Test
    @DisplayName("大范围：铺满安全阀以内的范围可正常查询")
    void largeRangeIndexed() {
        final SpatialIndex index = new SpatialIndex();
        index.put(ObjectRange.ofCorners(STATION_A, -1024, -1024, 1024, 1024));
        assertFalse(index.isEmpty());
        assertEquals(List.of(STATION_A), idsAt(index, 0, 0));
        assertEquals(List.of(STATION_A), idsAt(index, -1024, 1024));
        assertEquals(List.of(STATION_A), idsAt(index, 1024, -1024));
        assertTrue(index.lookup(1025, 0).isEmpty());
    }
}
