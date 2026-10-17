package com.mtrstar.lock.client.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaeroWaypointFilterTest {

    @Test
    @DisplayName("合法 Station 被保留并转换坐标 / 名称")
    void keepsValidStation() {
        Optional<XaeroWaypointSpec> result = XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "中央站", true, 12L, -34L);

        assertTrue(result.isPresent());
        XaeroWaypointSpec spec = result.orElseThrow();
        assertEquals("station:ABCD", spec.id());
        assertEquals("中央站", spec.name());
        assertEquals(12, spec.x());
        assertEquals(-34, spec.z());
        assertEquals(XaeroWaypointSpec.Kind.STATION, spec.kind());
    }

    @Test
    @DisplayName("合法 Depot 被保留")
    void keepsValidDepot() {
        Optional<XaeroWaypointSpec> result = XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.DEPOT, "EF01", "车厂", true, 1L, 2L);
        assertTrue(result.isPresent());
        assertEquals("depot:EF01", result.orElseThrow().id());
    }

    @Test
    @DisplayName("isValid=false 跳过")
    void skipsInvalidObject() {
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "中央站", false, 1L, 2L).isPresent());
    }

    @Test
    @DisplayName("x 超出 int 范围跳过")
    void skipsXOutOfIntRange() {
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                (long) Integer.MIN_VALUE - 1L, 0L).isPresent());
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                (long) Integer.MAX_VALUE + 1L, 0L).isPresent());
    }

    @Test
    @DisplayName("z 超出 int 范围跳过")
    void skipsZOutOfIntRange() {
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.DEPOT, "ABCD", "厂", true,
                0L, (long) Integer.MIN_VALUE - 1L).isPresent());
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.DEPOT, "ABCD", "厂", true,
                0L, (long) Integer.MAX_VALUE + 1L).isPresent());
    }

    @Test
    @DisplayName("Long.MIN_VALUE / Long.MAX_VALUE 坐标跳过")
    void skipsLongExtremes() {
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                Long.MIN_VALUE, 0L).isPresent());
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                Long.MAX_VALUE, 0L).isPresent());
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                0L, Long.MIN_VALUE).isPresent());
        assertFalse(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                0L, Long.MAX_VALUE).isPresent());
    }

    @Test
    @DisplayName("int 边界值本身合法")
    void intBoundsAreKept() {
        assertTrue(XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", "站", true,
                Integer.MIN_VALUE, Integer.MAX_VALUE).isPresent());
    }

    @Test
    @DisplayName("null 名称归一化为空串")
    void nullNameNormalized() {
        XaeroWaypointSpec spec = XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "ABCD", null, true, 1L, 2L).orElseThrow();
        assertEquals("", spec.name());
    }

    @Test
    @DisplayName("无 hexId 时使用内容哈希兜底 ID")
    void fallbackIdWithoutHexId() {
        XaeroWaypointSpec first = XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "站", true, 1L, 2L).orElseThrow();
        XaeroWaypointSpec second = XaeroWaypointSpec.tryOf(
                XaeroWaypointSpec.Kind.STATION, "站", true, 1L, 2L).orElseThrow();
        assertTrue(first.id().startsWith("station:h"));
        assertEquals(first.id(), second.id());
    }
}
