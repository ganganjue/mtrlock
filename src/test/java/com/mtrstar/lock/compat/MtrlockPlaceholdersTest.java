package com.mtrstar.lock.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MtrlockPlaceholders} 纯逻辑 seam 的纯 JVM 单元测试。
 *
 * <p>三个 {@code *Value} seam 方法接收注入的 lookup，不触碰 {@code TitleData} /
 * {@code TeamData} 单例，也不需要 placeholder-api 运行时（{@code register()} 不会被调用）。</p>
 */
class MtrlockPlaceholdersTest {

    private static final String ALICE = "22222222-2222-2222-2222-222222222222";

    // =====================================================================
    // %mtrlock_prefix%
    // =====================================================================

    @Test
    @DisplayName("prefix：有称呼 → [称呼]（完整、不截断）")
    void prefixWithTitle() {
        assertEquals("[红石局长]",
                MtrlockPlaceholders.prefixValue(ALICE, uuid -> "红石局长", uuid -> "红石铁路局"));
    }

    @Test
    @DisplayName("prefix：有团队 → [团队名前两字]")
    void prefixWithTeam() {
        assertEquals("[红石]",
                MtrlockPlaceholders.prefixValue(ALICE, uuid -> null, uuid -> "红石铁路局"));
    }

    @Test
    @DisplayName("prefix：称呼与团队都没有 → 空串")
    void prefixEmpty() {
        assertEquals("", MtrlockPlaceholders.prefixValue(ALICE, uuid -> null, uuid -> null));
    }

    @Test
    @DisplayName("prefix：玩家 null / 空 uuid / lookup null → 空串（null 安全）")
    void prefixNullSafe() {
        assertEquals("", MtrlockPlaceholders.prefixValue(null, uuid -> "局长", uuid -> "红石"));
        assertEquals("", MtrlockPlaceholders.prefixValue("", uuid -> "局长", uuid -> "红石"));
        assertEquals("", MtrlockPlaceholders.prefixValue(ALICE, null, null));
    }

    // =====================================================================
    // %mtrlock_title%
    // =====================================================================

    @Test
    @DisplayName("title：返回原始称呼（不含方括号）")
    void titleValue() {
        assertEquals("服主", MtrlockPlaceholders.titleValue(ALICE, uuid -> "服主"));
    }

    @Test
    @DisplayName("title：无称呼 / 空称呼 / null 来源 / null uuid → 空串")
    void titleEmpty() {
        assertEquals("", MtrlockPlaceholders.titleValue(ALICE, uuid -> null));
        assertEquals("", MtrlockPlaceholders.titleValue(ALICE, uuid -> ""));
        assertEquals("", MtrlockPlaceholders.titleValue(ALICE, null));
        assertEquals("", MtrlockPlaceholders.titleValue(null, uuid -> "服主"));
    }

    // =====================================================================
    // %mtrlock_team%
    // =====================================================================

    @Test
    @DisplayName("team：返回团队名前两字（不含方括号，按 code point）")
    void teamValue() {
        assertEquals("红石", MtrlockPlaceholders.teamValue(ALICE, uuid -> "红石铁路局"));
        assertEquals("队", MtrlockPlaceholders.teamValue(ALICE, uuid -> "队"));
        assertEquals("😀红", MtrlockPlaceholders.teamValue(ALICE, uuid -> "😀红石"));
    }

    @Test
    @DisplayName("team：无团队 / 空团队名 / null 来源 / null uuid → 空串")
    void teamEmpty() {
        assertEquals("", MtrlockPlaceholders.teamValue(ALICE, uuid -> null));
        assertEquals("", MtrlockPlaceholders.teamValue(ALICE, uuid -> ""));
        assertEquals("", MtrlockPlaceholders.teamValue(ALICE, null));
        assertEquals("", MtrlockPlaceholders.teamValue(null, uuid -> "红石"));
    }
}
