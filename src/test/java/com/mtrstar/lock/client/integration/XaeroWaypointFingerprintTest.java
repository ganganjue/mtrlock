package com.mtrstar.lock.client.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaeroWaypointFingerprintTest {

    private static XaeroWaypointSpec station(String id, String name, int x, int z) {
        return new XaeroWaypointSpec(id, name, x, z, XaeroWaypointSpec.Kind.STATION);
    }

    @Test
    @DisplayName("null / 空集合指纹为空串")
    void emptyFingerprint() {
        assertEquals("", XaeroWaypointSpec.fingerprint(null));
        assertEquals("", XaeroWaypointSpec.fingerprint(List.of()));
    }

    @Test
    @DisplayName("单条 spec 产生非空指纹")
    void singleFingerprint() {
        String fp = XaeroWaypointSpec.fingerprint(List.of(station("station:A", "甲", 1, 2)));
        assertTrue(!fp.isEmpty());
    }

    @Test
    @DisplayName("多条 spec 的指纹与单条不同")
    void multipleDiffersFromSingle() {
        XaeroWaypointSpec a = station("station:A", "甲", 1, 2);
        XaeroWaypointSpec b = station("station:B", "乙", 3, 4);
        assertNotEquals(XaeroWaypointSpec.fingerprint(List.of(a)),
                XaeroWaypointSpec.fingerprint(List.of(a, b)));
    }

    @Test
    @DisplayName("输入顺序不影响指纹")
    void orderIndependent() {
        XaeroWaypointSpec a = station("station:A", "甲", 1, 2);
        XaeroWaypointSpec b = station("station:B", "乙", 3, 4);
        XaeroWaypointSpec c = station("station:C", "丙", 5, 6);

        List<XaeroWaypointSpec> ordered = List.of(a, b, c);
        List<XaeroWaypointSpec> shuffled = new ArrayList<>(ordered);
        Collections.reverse(shuffled);

        assertEquals(XaeroWaypointSpec.fingerprint(ordered),
                XaeroWaypointSpec.fingerprint(shuffled));
    }

    @Test
    @DisplayName("重复输入仍保持稳定顺序")
    void duplicatesStable() {
        XaeroWaypointSpec a = station("station:A", "甲", 1, 2);
        assertEquals(XaeroWaypointSpec.fingerprint(List.of(a, a, a)),
                XaeroWaypointSpec.fingerprint(List.of(a, a, a)));
    }

    @Test
    @DisplayName("id / name / x / z / kind 变化均改变指纹")
    void everyDisplayFieldAffectsFingerprint() {
        XaeroWaypointSpec base = station("station:A", "甲", 1, 2);
        String fp = XaeroWaypointSpec.fingerprint(List.of(base));

        assertNotEquals(fp, XaeroWaypointSpec.fingerprint(List.of(
                station("station:B", "甲", 1, 2))));
        assertNotEquals(fp, XaeroWaypointSpec.fingerprint(List.of(
                station("station:A", "乙", 1, 2))));
        assertNotEquals(fp, XaeroWaypointSpec.fingerprint(List.of(
                station("station:A", "甲", 9, 2))));
        assertNotEquals(fp, XaeroWaypointSpec.fingerprint(List.of(
                station("station:A", "甲", 1, 9))));
        assertNotEquals(fp, XaeroWaypointSpec.fingerprint(List.of(
                new XaeroWaypointSpec("station:A", "甲", 1, 2, XaeroWaypointSpec.Kind.DEPOT))));
    }

    @Test
    @DisplayName("名称中的分隔符 / 长度前缀内容不会造成普通碰撞")
    void delimiterLikeNamesDoNotCollide() {
        XaeroWaypointSpec first = station("station:A", "1:b|2:c", 7, 8);
        XaeroWaypointSpec second = station("station:A", "b|2:c", 7, 8);
        assertNotEquals(XaeroWaypointSpec.fingerprint(List.of(first)),
                XaeroWaypointSpec.fingerprint(List.of(second)));
    }

    @Test
    @DisplayName("空名称与 null 归一化后的空名称指纹一致")
    void nullAndEmptyNameEquivalent() {
        XaeroWaypointSpec nullName = station("station:A", null, 1, 2);
        XaeroWaypointSpec emptyName = station("station:A", "", 1, 2);
        assertEquals(XaeroWaypointSpec.fingerprint(List.of(nullName)),
                XaeroWaypointSpec.fingerprint(List.of(emptyName)));
    }
}
