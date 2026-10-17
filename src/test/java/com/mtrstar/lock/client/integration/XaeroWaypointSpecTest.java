package com.mtrstar.lock.client.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaeroWaypointSpecTest {

    @Test
    @DisplayName("Station 的符号 / 颜色名 / origin path")
    void stationKindHasExpectedConstants() {
        assertEquals("站", XaeroWaypointSpec.Kind.STATION.symbol);
        assertEquals("GOLD", XaeroWaypointSpec.Kind.STATION.colorName);
        assertEquals("xaero_stations", XaeroWaypointSpec.Kind.STATION.originPath);
    }

    @Test
    @DisplayName("Depot 的符号 / 颜色名 / origin path")
    void depotKindHasExpectedConstants() {
        assertEquals("厂", XaeroWaypointSpec.Kind.DEPOT.symbol);
        assertEquals("AQUA", XaeroWaypointSpec.Kind.DEPOT.colorName);
        assertEquals("xaero_depots", XaeroWaypointSpec.Kind.DEPOT.originPath);
    }

    @Test
    @DisplayName("MTR waypoint y 固定为 64")
    void waypointYIs64() {
        assertEquals(64, XaeroWaypointSpec.WAYPOINT_Y);
    }

    @Test
    @DisplayName("record 保留合法字段")
    void keepsFields() {
        XaeroWaypointSpec spec = new XaeroWaypointSpec("station:ABC", "中央站", 12, -34,
                XaeroWaypointSpec.Kind.STATION);
        assertEquals("station:ABC", spec.id());
        assertEquals("中央站", spec.name());
        assertEquals(12, spec.x());
        assertEquals(-34, spec.z());
        assertEquals(XaeroWaypointSpec.Kind.STATION, spec.kind());
    }

    @Test
    @DisplayName("null 名称归一化为空串")
    void nullNameBecomesEmpty() {
        XaeroWaypointSpec spec = new XaeroWaypointSpec("station:ABC", null, 0, 0,
                XaeroWaypointSpec.Kind.STATION);
        assertEquals("", spec.name());
    }

    @Test
    @DisplayName("id / kind 为 null 时拒绝构造")
    void rejectsNullIdAndKind() {
        assertThrows(NullPointerException.class,
                () -> new XaeroWaypointSpec(null, "站", 0, 0, XaeroWaypointSpec.Kind.STATION));
        assertThrows(NullPointerException.class,
                () -> new XaeroWaypointSpec("station:ABC", "站", 0, 0, null));
    }

    @Test
    @DisplayName("stableId 优先使用 hexId 并带 kind 前缀")
    void stableIdUsesHexId() {
        assertEquals("station:ABC123",
                XaeroWaypointSpec.stableId(XaeroWaypointSpec.Kind.STATION, "ABC123", "旧名", 1, 2));
        assertEquals("depot:DEF456",
                XaeroWaypointSpec.stableId(XaeroWaypointSpec.Kind.DEPOT, " DEF456 ", "旧名", 1, 2));
    }

    @Test
    @DisplayName("stableId 无 hexId 时按内容哈希兜底且确定")
    void stableIdFallsBackToContentHash() {
        String first = XaeroWaypointSpec.stableId(
                XaeroWaypointSpec.Kind.STATION, " ", "中央站", 10, 20);
        String second = XaeroWaypointSpec.stableId(
                XaeroWaypointSpec.Kind.STATION, null, "中央站", 10, 20);

        assertTrue(first.startsWith("station:h"));
        assertEquals(first, second);
    }

    @Test
    @DisplayName("stableId 内容哈希随名称 / 坐标变化")
    void stableIdFallbackChangesWithContent() {
        String base = XaeroWaypointSpec.stableId(
                XaeroWaypointSpec.Kind.STATION, null, "中央站", 10, 20);
        assertNotEquals(base, XaeroWaypointSpec.stableId(
                XaeroWaypointSpec.Kind.STATION, null, "东站", 10, 20));
        assertNotEquals(base, XaeroWaypointSpec.stableId(
                XaeroWaypointSpec.Kind.STATION, null, "中央站", 11, 20));
    }

    @Test
    @DisplayName("shouldRebuild：manager 未就绪不重建")
    void shouldRebuildFalseWhenManagerNull() {
        assertFalse(XaeroWaypointSpec.shouldRebuild("fp", null, null, null));
        assertFalse(XaeroWaypointSpec.shouldRebuild("fp", null, "fp", new Object()));
    }

    @Test
    @DisplayName("shouldRebuild：相同指纹和相同 manager 不重建")
    void shouldRebuildFalseWhenUnchanged() {
        Object manager = new Object();
        assertFalse(XaeroWaypointSpec.shouldRebuild("fp", manager, "fp", manager));
    }

    @Test
    @DisplayName("shouldRebuild：指纹变化或 manager 实例变化时重建")
    void shouldRebuildTrueWhenChanged() {
        Object oldManager = new Object();
        assertTrue(XaeroWaypointSpec.shouldRebuild("new", oldManager, "old", oldManager));
        assertTrue(XaeroWaypointSpec.shouldRebuild("fp", new Object(), "fp", oldManager));
    }

    @Test
    @DisplayName("fingerprint 对相同内容稳定，且与 List.of 顺序无关")
    void fingerprintStableAndOrderIndependent() {
        XaeroWaypointSpec a = new XaeroWaypointSpec("station:A", "甲", 1, 2,
                XaeroWaypointSpec.Kind.STATION);
        XaeroWaypointSpec b = new XaeroWaypointSpec("depot:B", "乙", 3, 4,
                XaeroWaypointSpec.Kind.DEPOT);

        String ab = XaeroWaypointSpec.fingerprint(List.of(a, b));
        String ba = XaeroWaypointSpec.fingerprint(List.of(b, a));

        assertNotNull(ab);
        assertFalse(ab.isEmpty());
        assertEquals(ab, ba);
    }
}
