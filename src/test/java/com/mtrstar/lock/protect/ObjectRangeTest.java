package com.mtrstar.lock.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ObjectRange} 单元测试（1.3.0）。 */
class ObjectRangeTest {

    private static final String ID = "station:0B0829457F350DE9";

    // =====================================================================
    // 构造 / 归一化
    // =====================================================================

    @Test
    @DisplayName("ofCorners：正常顺序")
    void cornersNormalOrder() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        assertEquals(-10, r.minX());
        assertEquals(30, r.maxX());
        assertEquals(-20, r.minZ());
        assertEquals(40, r.maxZ());
        assertEquals(ID, r.objectId());
    }

    @Test
    @DisplayName("ofCorners：对角点顺序颠倒也归一化")
    void cornersReverseOrder() {
        final ObjectRange r = ObjectRange.ofCorners(ID, 30, 40, -10, -20);
        assertEquals(-10, r.minX());
        assertEquals(30, r.maxX());
        assertEquals(-20, r.minZ());
        assertEquals(40, r.maxZ());
    }

    @Test
    @DisplayName("ofCorners：只颠倒一个轴也要分别归一化")
    void cornersMixedOrder() {
        final ObjectRange r = ObjectRange.ofCorners(ID, 30, -20, -10, 40);
        assertEquals(-10, r.minX());
        assertEquals(30, r.maxX());
        assertEquals(-20, r.minZ());
        assertEquals(40, r.maxZ());
    }

    @Test
    @DisplayName("ofBounds：即便传反也归一化，不会产生 min>max 的坏范围")
    void boundsDefensivelyNormalized() {
        final ObjectRange r = ObjectRange.ofBounds(ID, 30, -10, 40, -20);
        assertEquals(-10, r.minX());
        assertEquals(30, r.maxX());
        assertEquals(-20, r.minZ());
        assertEquals(40, r.maxZ());
    }

    @Test
    @DisplayName("objectId 为 null / 空串 → null")
    void invalidObjectId() {
        assertNull(ObjectRange.ofCorners(null, 0, 0, 1, 1));
        assertNull(ObjectRange.ofCorners("", 0, 0, 1, 1));
        assertNull(ObjectRange.ofBounds(null, 0, 1, 0, 1));
        assertNull(ObjectRange.ofBounds("", 0, 1, 0, 1));
    }

    @Test
    @DisplayName("退化范围：两个角相同 → 单方块")
    void degenerateRange() {
        final ObjectRange r = ObjectRange.ofCorners(ID, 5, 7, 5, 7);
        assertEquals(5, r.minX());
        assertEquals(5, r.maxX());
        assertTrue(r.contains(5, 7));
        assertFalse(r.contains(6, 7));
    }

    // =====================================================================
    // contains 边界
    // =====================================================================

    @Test
    @DisplayName("contains：四个边界点含端点均为 true")
    void containsInclusiveBoundaries() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        assertTrue(r.contains(-10, -20)); // min/min
        assertTrue(r.contains(30, 40));   // max/max
        assertTrue(r.contains(-10, 40));  // minX/maxZ
        assertTrue(r.contains(30, -20));  // maxX/minZ
        assertTrue(r.contains(0, 0));     // 内部
    }

    @Test
    @DisplayName("contains：刚好越界一个方块为 false")
    void containsJustOutside() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        assertFalse(r.contains(-11, 0));
        assertFalse(r.contains(31, 0));
        assertFalse(r.contains(0, -21));
        assertFalse(r.contains(0, 41));
    }

    @Test
    @DisplayName("contains：X 在范围内但 Z 不在 → false（反过来也一样）")
    void containsSingleAxisMiss() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        assertFalse(r.contains(0, 100));
        assertFalse(r.contains(100, 0));
    }

    @Test
    @DisplayName("y 被忽略：范围只由 X/Z 决定，与高度无关")
    void containsIgnoresY() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        // 同一个 (x,z) 无论“多高多低”结论都一样；ObjectRange 里根本没有 y 字段。
        assertTrue(r.contains(0, 0));
        assertFalse(r.contains(100, 0));
        assertEquals(-10, r.minX());
        assertEquals(30, r.maxX());
        assertEquals(-20, r.minZ());
        assertEquals(40, r.maxZ());
    }

    // =====================================================================
    // expanded
    // =====================================================================

    @Test
    @DisplayName("expanded(0) / 负数：返回自身（不产生新对象）")
    void expandedZeroReturnsSelf() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        assertSame(r, r.expanded(0));
        assertSame(r, r.expanded(-5));
    }

    @Test
    @DisplayName("expanded(n)：四边各向外扩张 n，objectId 不变")
    void expandedGrows() {
        final ObjectRange r = ObjectRange.ofCorners(ID, -10, -20, 30, 40).expanded(3);
        assertEquals(-13, r.minX());
        assertEquals(33, r.maxX());
        assertEquals(-23, r.minZ());
        assertEquals(43, r.maxZ());
        assertEquals(ID, r.objectId());
        assertTrue(r.contains(-13, -23));
        assertFalse(r.contains(-14, -23));
    }

    @Test
    @DisplayName("expanded 不改原对象（不可变）")
    void expandedDoesNotMutate() {
        final ObjectRange r = ObjectRange.ofCorners(ID, 0, 0, 0, 0);
        r.expanded(10);
        assertEquals(0, r.minX());
        assertEquals(0, r.maxX());
    }

    // =====================================================================
    // equals / hashCode
    // =====================================================================

    @Test
    @DisplayName("equals / hashCode：值相同即相等，objectId 或任一坐标不同即不等")
    void equalsAndHashCode() {
        final ObjectRange a = ObjectRange.ofCorners(ID, -10, -20, 30, 40);
        final ObjectRange b = ObjectRange.ofCorners(ID, 30, 40, -10, -20);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, ObjectRange.ofCorners(ID, -10, -20, 30, 41));
        assertNotEquals(a, ObjectRange.ofCorners("depot:0B0829457F350DE9", -10, -20, 30, 40));
        assertNotEquals(a, null);
        assertNotEquals(a, "not a range");
    }

    @Test
    @DisplayName("toString 含 objectId 与四个边界")
    void toStringContainsBounds() {
        final String s = ObjectRange.ofCorners(ID, -10, -20, 30, 40).toString();
        assertTrue(s.contains(ID));
        assertTrue(s.contains("-10"));
        assertTrue(s.contains("40"));
    }
}
