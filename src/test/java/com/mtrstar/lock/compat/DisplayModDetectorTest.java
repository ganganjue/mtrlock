package com.mtrstar.lock.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DisplayModDetector} 纯函数重载的纯 JVM 单元测试。
 *
 * <p>只调用 {@link DisplayModDetector#hasConflictingDisplayMod(Set)}，
 * 不触发 {@code FabricLoader}（无参版本才读模组列表）。</p>
 */
class DisplayModDetectorTest {

    @Test
    @DisplayName("空集合 → false")
    void emptySet() {
        assertFalse(DisplayModDetector.hasConflictingDisplayMod(Set.of()));
    }

    @Test
    @DisplayName("null 集合 → false")
    void nullSet() {
        assertFalse(DisplayModDetector.hasConflictingDisplayMod(null));
    }

    @Test
    @DisplayName("有 styledchat → true")
    void styledChat() {
        assertTrue(DisplayModDetector.hasConflictingDisplayMod(Set.of("styledchat")));
    }

    @Test
    @DisplayName("有 styledplayerlist → true")
    void styledPlayerList() {
        assertTrue(DisplayModDetector.hasConflictingDisplayMod(Set.of("styledplayerlist")));
    }

    @Test
    @DisplayName("两者都有 → true")
    void both() {
        assertTrue(DisplayModDetector.hasConflictingDisplayMod(
                Set.of("styledchat", "styledplayerlist")));
    }

    @Test
    @DisplayName("只有无关模组 → false")
    void unrelatedMods() {
        assertFalse(DisplayModDetector.hasConflictingDisplayMod(
                Set.of("minecraft", "fabric-api", "mtr", "mtrlock", "placeholder-api")));
    }
}
