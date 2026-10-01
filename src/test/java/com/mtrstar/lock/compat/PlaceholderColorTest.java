package com.mtrstar.lock.compat;

import com.mtrstar.lock.team.TeamPrefix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code %mtrlock:title_colored%} 输出与向后兼容测试（1.2.4）。 */
class PlaceholderColorTest {

    private static final String PLAYER = "aaaaaaaa-1111-1111-1111-111111111111";

    private static TeamPrefix.TitleLookup lookup(String text, String color) {
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

    @Test
    @DisplayName("title_colored：MiniMessage 输出 <#rrggbb>文本")
    void miniMessage() {
        assertEquals("<#ff5555>红石局长",
                MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("红石局长", "#ff5555"),
                        DisplayConfig.Format.MINIMESSAGE));
    }

    @Test
    @DisplayName("title_colored：legacy 输出 §x§r§r§g§g§b§b 文本")
    void legacy() {
        assertEquals("§x§f§f§5§5§5§5红石局长",
                MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("红石局长", "#ff5555"),
                        DisplayConfig.Format.LEGACY));
    }

    @Test
    @DisplayName("title_colored：无色时两种格式都是纯文本")
    void noColor() {
        assertEquals("调度员", MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("调度员", null),
                DisplayConfig.Format.MINIMESSAGE));
        assertEquals("调度员", MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("调度员", null),
                DisplayConfig.Format.LEGACY));
    }

    @Test
    @DisplayName("title_colored：无称呼 / 非法参数 → 空串（不输出悬空颜色标签）")
    void empty() {
        assertEquals("", MtrlockPlaceholders.coloredTitleValue(PLAYER, null, DisplayConfig.Format.MINIMESSAGE));
        assertEquals("", MtrlockPlaceholders.coloredTitleValue(null, lookup("x", "#ff5555"),
                DisplayConfig.Format.MINIMESSAGE));
        assertEquals("", MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup(null, "#ff5555"),
                DisplayConfig.Format.MINIMESSAGE));
        assertEquals("", MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("", "#ff5555"),
                DisplayConfig.Format.LEGACY));
    }

    @Test
    @DisplayName("title_colored：format 为 null → 用默认 MiniMessage")
    void nullFormatUsesDefault() {
        assertEquals("<#ff5555>x", MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("x", "#ff5555"), null));
    }

    @Test
    @DisplayName("title_colored：转义 MiniMessage 标签")
    void escapes() {
        assertEquals("<#ff5555>\\<red>",
                MtrlockPlaceholders.coloredTitleValue(PLAYER, lookup("<red>", "#ff5555"),
                        DisplayConfig.Format.MINIMESSAGE));
    }

    @Test
    @DisplayName("%mtrlock:title% 仍是纯文本（向后兼容）")
    void plainTitleUnchanged() {
        assertEquals("红石局长", MtrlockPlaceholders.titleValue(PLAYER, lookup("红石局长", "#ff5555")));
        assertEquals("", MtrlockPlaceholders.titleValue(PLAYER, lookup(null, "#ff5555")));
    }

    @Test
    @DisplayName("%mtrlock:prefix% 优先级不变，颜色不干扰团队前缀选择")
    void prefixUnaffected() {
        assertEquals("[红石局长]", MtrlockPlaceholders.prefixValue(PLAYER, lookup("红石局长", "#ff5555"),
                uuid -> "红石铁路局"));
        assertEquals("[红石]", MtrlockPlaceholders.prefixValue(PLAYER, lookup(null, "#ff5555"),
                uuid -> "红石铁路局"));
    }
}
