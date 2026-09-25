package com.mtrstar.lock.team;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TeamPrefix} 的纯 JVM 单元测试。
 *
 * <p>只测纯函数与常量：{@link TeamPrefix#firstChars(String, int)}、{@link TeamPrefix#NO_TEAM}、
 * {@link TeamPrefix#of(String)} 在 null / 空 uuid 时的短路。</p>
 *
 * <p>{@code of(真实uuid)} 会走 {@code TeamData.getInstance()} → {@code FabricLoader}（纯 JVM 下 NPE），
 * 所以这里通过包内可见的 {@link TeamPrefix#setLookup(TeamPrefix.TeamNameLookup)} seam 注入桩，
 * 验证 of 的<b>拼装逻辑</b>（取第一个团队名 + 截前两个 code point + 方括号），
 * 不触碰 {@code TeamData} 单例。每个测试后恢复生产 lookup。</p>
 */
class TeamPrefixTest {

    private static final String ALICE = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() {
        // 默认不注入称呼，避免 of() 的称呼查找触碰 TitleData 单例（纯 JVM 会 NPE）
        TeamPrefix.setTitleLookup(uuid -> null);
    }

    @AfterEach
    void tearDown() {
        TeamPrefix.resetLookup();
    }

    // =====================================================================
    // 常量
    // =====================================================================

    @Test
    @DisplayName("常量：NO_TEAM / PREFIX_CHARS 与约定一致")
    void constants() {
        assertEquals("", TeamPrefix.NO_TEAM);
        assertEquals(2, TeamPrefix.PREFIX_CHARS);
    }

    // =====================================================================
    // firstChars —— 纯函数
    // =====================================================================

    @Test
    @DisplayName("firstChars：中文按字算（红石铁路局 → 红石）")
    void firstCharsChinese() {
        assertEquals("红石", TeamPrefix.firstChars("红石铁路局", 2));
        assertEquals("红", TeamPrefix.firstChars("红石铁路局", 1));
        assertEquals("红石铁路局", TeamPrefix.firstChars("红石铁路局", 5));
        assertEquals("红石铁路局", TeamPrefix.firstChars("红石铁路局", 100));
    }

    @Test
    @DisplayName("firstChars：英文按字符算")
    void firstCharsAscii() {
        assertEquals("ab", TeamPrefix.firstChars("abcdef", 2));
        assertEquals("a", TeamPrefix.firstChars("abcdef", 1));
        assertEquals("abcdef", TeamPrefix.firstChars("abcdef", 6));
        assertEquals("abcdef", TeamPrefix.firstChars("abcdef", 7));
    }

    @Test
    @DisplayName("firstChars：emoji 是代理对，按 code point 取、不截断")
    void firstCharsEmoji() {
        // 😀 = U+1F600，占 2 个 char；取 1 个 code point 必须拿到完整 emoji（4 个 char？不，2 个 char）
        final String one = TeamPrefix.firstChars("😀abc", 1);
        assertEquals("😀", one);
        assertEquals(2, one.length(), "一个 emoji 应恰好是 2 个 UTF-16 char（完整代理对）");

        // 第 2 个 code point 是 'a'，不能切在代理对中间
        final String two = TeamPrefix.firstChars("😀abc", 2);
        assertEquals("😀a", two);
        assertEquals(3, two.length(), "😀 占 2 char + 'a' 占 1 char = 3");
        assertTrue(Character.isLowSurrogate(two.charAt(1)), "代理对应完整，第 2 个 char 必须是低代理");

        assertEquals("😀😀", TeamPrefix.firstChars("😀😀x", 2));
        assertEquals("😀😀x", TeamPrefix.firstChars("😀😀x", 3));
    }

    @Test
    @DisplayName("firstChars：emoji + 中文混合（😀红石 → 前 3 个 code point）")
    void firstCharsMixed() {
        assertEquals("😀红", TeamPrefix.firstChars("😀红石", 2));
        assertEquals("😀红石", TeamPrefix.firstChars("😀红石", 3));
    }

    @Test
    @DisplayName("firstChars：null / 空串 / n<=0 → 空串（永不 null）")
    void firstCharsEdge() {
        assertEquals("", TeamPrefix.firstChars(null, 2));
        assertEquals("", TeamPrefix.firstChars("", 2));
        assertEquals("", TeamPrefix.firstChars("abc", 0));
        assertEquals("", TeamPrefix.firstChars("abc", -1));
        assertEquals("", TeamPrefix.firstChars(null, 0));
        assertNotNull(TeamPrefix.firstChars(null, -5));
    }

    // =====================================================================
    // of —— null / 空 uuid 短路（不触碰 TeamData）
    // =====================================================================

    @Test
    @DisplayName("of：null / 空 uuid → NO_TEAM（永不 null）")
    void ofInvalidUuid() {
        assertSame(TeamPrefix.NO_TEAM, TeamPrefix.of(null));
        assertSame(TeamPrefix.NO_TEAM, TeamPrefix.of(""));
        assertNotNull(TeamPrefix.of(null));
    }

    // =====================================================================
    // of —— seam 注入（不触碰 TeamData）
    // =====================================================================

    @Test
    @DisplayName("of：有团队 → 取第一个（最早加入）团队名截 2 个 code point")
    void ofWithTeam() {
        TeamPrefix.setLookup(uuid -> "红石铁路局");
        assertEquals("[红石]", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("of：团队名不足 2 字 → 全取，不报错")
    void ofShortTeamName() {
        TeamPrefix.setLookup(uuid -> "队");
        assertEquals("[队]", TeamPrefix.of(ALICE));

        TeamPrefix.setLookup(uuid -> "A队");
        assertEquals("[A队]", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("of：无团队无称呼 → 空串（1.2.0 起不再显示 [独立建造者]）")
    void ofNoTeamReturnsEmptyString() {
        TeamPrefix.setTitleLookup(uuid -> null);
        TeamPrefix.setLookup(uuid -> null);
        assertEquals("", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("of(uuid, titles, teams) seam：注入来源直接决定前缀")
    void ofWithInjectedLookups() {
        assertEquals("[局长]", TeamPrefix.of(ALICE, uuid -> "局长", uuid -> "红石铁路局"));
        assertEquals("[红石]", TeamPrefix.of(ALICE, uuid -> null, uuid -> "红石铁路局"));
        assertEquals("", TeamPrefix.of(ALICE, uuid -> null, uuid -> null));
        assertEquals("", TeamPrefix.of(ALICE, null, null));
        assertEquals("", TeamPrefix.of(null, uuid -> "局长", uuid -> "红石"));
    }

    @Test
    @DisplayName("of：lookup 返回 null / 空 → NO_TEAM（空串）")
    void ofNoTeam() {
        TeamPrefix.setLookup(uuid -> null);
        assertEquals(TeamPrefix.NO_TEAM, TeamPrefix.of(ALICE));

        TeamPrefix.setLookup(uuid -> "");
        assertEquals(TeamPrefix.NO_TEAM, TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("of：lookup 收到的就是调用方传的 uuid")
    void ofPassesUuidThrough() {
        final String[] seen = new String[1];
        TeamPrefix.setLookup(uuid -> {
            seen[0] = uuid;
            return "红石";
        });
        assertEquals("[红石]", TeamPrefix.of(ALICE));
        assertEquals(ALICE, seen[0]);
    }

    @Test
    @DisplayName("of：前缀永远带方括号，且不是 NO_TEAM")
    void ofFormat() {
        TeamPrefix.setLookup(uuid -> "红石铁路局");
        final String prefix = TeamPrefix.of(ALICE);
        assertEquals('[', prefix.charAt(0));
        assertEquals(']', prefix.charAt(prefix.length() - 1));
        assertFalse(prefix.equals(TeamPrefix.NO_TEAM));
    }

    // =====================================================================
    // 自定义称呼优先（1.2.0）
    // =====================================================================

    @Test
    @DisplayName("有自定义称呼 → 优先用称呼，完整显示不截断")
    void titleOverridesTeam() {
        TeamPrefix.setTitleLookup(uuid -> "红石局长");
        TeamPrefix.setLookup(uuid -> "红石铁路局");
        assertEquals("[红石局长]", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("只有称呼、没有团队 → 用称呼")
    void titleOnly() {
        TeamPrefix.setTitleLookup(uuid -> "管理员");
        TeamPrefix.setLookup(uuid -> null);
        assertEquals("[管理员]", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("称呼为空 → 回退团队前缀（仍截两字）")
    void emptyTitleFallsBackToTeam() {
        TeamPrefix.setTitleLookup(uuid -> "");
        TeamPrefix.setLookup(uuid -> "红石铁路局");
        assertEquals("[红石]", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("称呼 16 个 code point 完整显示（不截断）")
    void longTitleNotTruncated() {
        final String title = "abcdefghijklmnop"; // 16
        TeamPrefix.setTitleLookup(uuid -> title);
        TeamPrefix.setLookup(uuid -> "红石铁路局");
        assertEquals("[" + title + "]", TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("称呼与团队都没有 → NO_TEAM")
    void neitherTitleNorTeam() {
        TeamPrefix.setTitleLookup(uuid -> null);
        TeamPrefix.setLookup(uuid -> null);
        assertSame(TeamPrefix.NO_TEAM, TeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("称呼命中时不再查团队 lookup")
    void titleHitSkipsTeamLookup() {
        final boolean[] teamCalled = new boolean[1];
        TeamPrefix.setTitleLookup(uuid -> "局长");
        TeamPrefix.setLookup(uuid -> {
            teamCalled[0] = true;
            return "红石铁路局";
        });
        assertEquals("[局长]", TeamPrefix.of(ALICE));
        assertFalse(teamCalled[0]);
    }
}
