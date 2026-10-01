package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TeamPrefix} 称号颜色与团队前缀共存测试（1.2.4）。 */
class TeamPrefixColorTest {

    private static final String PLAYER = "aaaaaaaa-1111-1111-1111-111111111111";

    private static TeamPrefix.TitleLookup titles(String text, String color) {
        return new TeamPrefix.TitleLookup() {
            @Override
            public String titleOf(String uuid) {
                return text;
            }

            @Override
            public String colorOf(String uuid) {
                return color;
            }
        };
    }

    private static TeamPrefix.TeamNameLookup team(String name) {
        return uuid -> name;
    }

    @Test
    @DisplayName("resolve：有称呼时返回文本 + 颜色，type=title")
    void titleWithColor() {
        final TeamPrefix.Resolved r = TeamPrefix.resolve(PLAYER, titles("局长", "#ff5555"), team("红石铁路局"));
        assertEquals("局长", r.text());
        assertEquals("#ff5555", r.color());
        assertTrue(r.title());
    }

    @Test
    @DisplayName("resolve：无颜色称呼 → color=null（团队前缀不参与上色）")
    void titleWithoutColor() {
        final TeamPrefix.Resolved r = TeamPrefix.resolve(PLAYER, titles("局长", null), team("红石铁路局"));
        assertEquals("局长", r.text());
        assertNull(r.color());
        assertTrue(r.title());
    }

    @Test
    @DisplayName("resolve：无称呼回退团队前缀，颜色为 null")
    void fallbackToTeam() {
        final TeamPrefix.Resolved r = TeamPrefix.resolve(PLAYER, titles(null, null), team("红石铁路局"));
        assertEquals("红石", r.text());
        assertNull(r.color());
        assertFalse(r.title());
    }

    @Test
    @DisplayName("优先级不变：颜色不改变“称呼 > 团队 > 无”")
    void colorDoesNotChangePriority() {
        assertEquals("局长", TeamPrefix.resolve(PLAYER, titles("局长", "#000000"), team("红石铁路局")).text());
        assertNull(TeamPrefix.resolve(PLAYER, titles(null, "#000000"), null));
        assertNull(TeamPrefix.resolve(PLAYER, titles("", null), team("")));
    }

    @Test
    @DisplayName("of() 仍是纯文本（向后兼容，不带颜色标签）")
    void ofStaysPlain() {
        assertEquals("[局长]", TeamPrefix.of(PLAYER, titles("局长", "#ff5555"), team("红石铁路局")));
        assertEquals("[红石]", TeamPrefix.of(PLAYER, titles(null, null), team("红石铁路局")));
        assertEquals(TeamPrefix.NO_TEAM, TeamPrefix.of(null, titles("局长", "#ff5555"), team("x")));
    }

    @Test
    @DisplayName("1.2.3 的 TitleLookup 桩（只实现 titleOf）仍可编译，颜色默认 null")
    void legacyLambdaStillWorks() {
        final TeamPrefix.TitleLookup legacy = uuid -> "老桩";
        final TeamPrefix.Resolved r = TeamPrefix.resolve(PLAYER, legacy, null);
        assertEquals("老桩", r.text());
        assertNull(r.color());
    }
}
