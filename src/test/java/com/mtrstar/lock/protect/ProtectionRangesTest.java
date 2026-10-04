package com.mtrstar.lock.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Utilities;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProtectionRanges} 单元测试（1.3.0）。
 *
 * <p>用真实的 MTR {@link Station} / {@link Depot} 对象验证 {@code AreaBase} 的
 * {@code getMinX()/getMaxX()/getMinZ()/getMaxZ()} → {@link ObjectRange} 转换，
 * 以及 id → objectId 的 16 位大写 HEX 转换。测试是纯 JVM 的，不加载 Minecraft。</p>
 */
class ProtectionRangesTest {

    private static final Predicate<String> ALL_OWNED = objectId -> true;
    private static final Predicate<String> NONE_OWNED = objectId -> false;

    private static Station addStation(Data data, long x1, long z1, long x2, long z2) {
        final Station station = new Station(data);
        station.setCorners(new Position(x1, Long.MIN_VALUE, z1), new Position(x2, Long.MAX_VALUE, z2));
        data.stations.add(station);
        return station;
    }

    private static Depot addDepot(Data data, long x1, long z1, long x2, long z2) {
        final Depot depot = new Depot(TransportMode.TRAIN, data);
        depot.setCorners(new Position(x1, Long.MIN_VALUE, z1), new Position(x2, Long.MAX_VALUE, z2));
        data.depots.add(depot);
        return depot;
    }

    // =====================================================================
    // id → objectId
    // =====================================================================

    @Test
    @DisplayName("objectIdOf：与 MTR 的 getHexId() 完全一致（16 位大写 HEX）")
    void objectIdMatchesHexId() {
        final Station station = new Station(new ClientData());
        final String objectId = ProtectionRanges.objectIdOf("station", station.getId());

        assertEquals("station:" + station.getHexId(), objectId);
        assertEquals("station:" + Utilities.numberToPaddedHexString(station.getId()), objectId);
        assertEquals(16, station.getHexId().length());
        assertEquals(station.getHexId().toUpperCase(Locale.ENGLISH), station.getHexId());
    }

    @Test
    @DisplayName("objectIdOf：depot 前缀 + 负数 id 也走同一转换")
    void objectIdPrefixAndNegativeId() {
        assertEquals("depot:" + Utilities.numberToPaddedHexString(-1L),
                ProtectionRanges.objectIdOf("depot", -1L));
    }

    // =====================================================================
    // 车站 / 车厂矩形提取
    // =====================================================================

    @Test
    @DisplayName("车站：矩形 → ObjectRange，objectId / 边界正确")
    void collectStationRange() {
        final Data data = new ClientData();
        final Station station = addStation(data, -106, -1107, -48, -1065);

        final List<ObjectRange> ranges = ProtectionRanges.collect(data, ALL_OWNED, 0);

        assertEquals(1, ranges.size());
        final ObjectRange range = ranges.get(0);
        assertEquals("station:" + station.getHexId(), range.objectId());
        assertEquals(-106, range.minX());
        assertEquals(-48, range.maxX());
        assertEquals(-1107, range.minZ());
        assertEquals(-1065, range.maxZ());
    }

    @Test
    @DisplayName("车厂：矩形 → ObjectRange，使用 depot 前缀")
    void collectDepotRange() {
        final Data data = new ClientData();
        final Depot depot = addDepot(data, -58, -1084, -8, -1043);

        final List<ObjectRange> ranges = ProtectionRanges.collect(data, ALL_OWNED, 0);

        assertEquals(1, ranges.size());
        assertEquals("depot:" + depot.getHexId(), ranges.get(0).objectId());
        assertEquals(-58, ranges.get(0).minX());
        assertEquals(-8, ranges.get(0).maxX());
        assertEquals(-1084, ranges.get(0).minZ());
        assertEquals(-1043, ranges.get(0).maxZ());
    }

    @Test
    @DisplayName("车站 + 车厂：同时收集，前缀不混淆")
    void collectStationAndDepot() {
        final Data data = new ClientData();
        final Station station = addStation(data, 0, 0, 10, 10);
        final Depot depot = addDepot(data, 20, 20, 30, 30);

        final Set<String> ids = ProtectionRanges.collect(data, ALL_OWNED, 0).stream()
                .map(ObjectRange::objectId).collect(Collectors.toSet());

        assertEquals(Set.of("station:" + station.getHexId(), "depot:" + depot.getHexId()), ids);
    }

    @Test
    @DisplayName("角点顺序颠倒：AreaBase 已归一化，ObjectRange 仍是 min<=max")
    void reversedCornersNormalized() {
        final Data data = new ClientData();
        addStation(data, 30, 40, -10, -20); // 第一个角大于第二个角

        final ObjectRange range = ProtectionRanges.collect(data, ALL_OWNED, 0).get(0);

        assertEquals(-10, range.minX());
        assertEquals(30, range.maxX());
        assertEquals(-20, range.minZ());
        assertEquals(40, range.maxZ());
    }

    @Test
    @DisplayName("y 被忽略：y=Long.MIN/MAX 也不影响 X/Z 范围")
    void yIgnored() {
        final Data data = new ClientData();
        final Station station = new Station(data);
        station.setCorners(new Position(5, Long.MIN_VALUE, 6), new Position(7, Long.MAX_VALUE, 8));
        data.stations.add(station);

        final ObjectRange range = ProtectionRanges.collect(data, ALL_OWNED, 0).get(0);

        assertTrue(range.contains(5, 6));
        assertTrue(range.contains(7, 8));
        assertEquals(5, range.minX());
        assertEquals(7, range.maxX());
        assertEquals(6, range.minZ());
        assertEquals(8, range.maxZ());
    }

    // =====================================================================
    // 归属过滤 / 扩张 / 子对象
    // =====================================================================

    @Test
    @DisplayName("无归属记录的对象不产生范围（fail-open）")
    void unownedExcluded() {
        final Data data = new ClientData();
        addStation(data, 0, 0, 10, 10);

        assertTrue(ProtectionRanges.collect(data, NONE_OWNED, 0).isEmpty());
    }

    @Test
    @DisplayName("归属过滤按 objectId 精确匹配：只收命中的那个")
    void ownershipFilterIsPerObject() {
        final Data data = new ClientData();
        final Station owned = addStation(data, 0, 0, 10, 10);
        addStation(data, 100, 100, 110, 110);

        final List<ObjectRange> ranges = ProtectionRanges.collect(data,
                objectId -> objectId.equals("station:" + owned.getHexId()), 0);

        assertEquals(1, ranges.size());
        assertEquals("station:" + owned.getHexId(), ranges.get(0).objectId());
    }

    @Test
    @DisplayName("expandBlocks：四边各向外扩张")
    void expandBlocks() {
        final Data data = new ClientData();
        addStation(data, 0, 0, 10, 10);

        final ObjectRange range = ProtectionRanges.collect(data, ALL_OWNED, 3).get(0);

        assertEquals(-3, range.minX());
        assertEquals(13, range.maxX());
        assertEquals(-3, range.minZ());
        assertEquals(13, range.maxZ());
    }

    @Test
    @DisplayName("站台不单独产生范围（随父车站矩形覆盖）")
    void platformNotCollectedSeparately() {
        final Data data = new ClientData();
        final Station station = addStation(data, 0, 0, 100, 100);
        final Platform platform = new Platform(new Position(10, 0, 10), new Position(20, 0, 20),
                TransportMode.TRAIN, data);
        data.platforms.add(platform);

        final List<ObjectRange> ranges = ProtectionRanges.collect(data, ALL_OWNED, 0);

        assertEquals(1, ranges.size());
        assertEquals("station:" + station.getHexId(), ranges.get(0).objectId());
        assertNotEquals("platform:" + platform.getHexId(), ranges.get(0).objectId());
        assertTrue(ranges.get(0).contains(15, 15), "站台位置被父车站矩形覆盖");
    }

    // =====================================================================
    // 边界 / 空输入
    // =====================================================================

    @Test
    @DisplayName("data 为 null / hasCreator 为 null → 空列表，不 NPE")
    void nullInputs() {
        assertTrue(ProtectionRanges.collect(null, ALL_OWNED, 0).isEmpty());
        assertTrue(ProtectionRanges.collect(new ClientData(), null, 0).isEmpty());
    }

    @Test
    @DisplayName("没有对象 → 空列表")
    void emptyData() {
        assertTrue(ProtectionRanges.collect(new ClientData(), ALL_OWNED, 0).isEmpty());
    }

    @Test
    @DisplayName("返回列表不可变")
    void resultIsImmutable() {
        final Data data = new ClientData();
        addStation(data, 0, 0, 1, 1);
        final List<ObjectRange> ranges = ProtectionRanges.collect(data, ALL_OWNED, 0);
        assertEquals(1, ranges.size());
        assertFalse(ranges.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> ranges.add(ObjectRange.ofCorners("station:X", 0, 0, 1, 1)));
    }
}
