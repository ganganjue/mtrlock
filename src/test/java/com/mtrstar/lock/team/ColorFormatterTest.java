package com.mtrstar.lock.team;

import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link ColorFormatter} 单元测试（1.2.4）：Component / MiniMessage / legacy 三种出口。 */
class ColorFormatterTest {

    @Test
    @DisplayName("component：按 RGB 上色，文本不变")
    void componentColored() {
        final Text text = ColorFormatter.component("红石局长", "red");
        assertEquals("红石局长", text.getString());
        final TextColor color = text.getStyle().getColor();
        assertNotNull(color);
        assertEquals(0xFF5555, color.getRgb());
    }

    @Test
    @DisplayName("component：HEX 输入与 &a 输入等价；无色时无样式颜色")
    void componentVariants() {
        assertEquals(0xFF5555, ColorFormatter.component("x", "#ff5555").getStyle().getColor().getRgb());
        assertEquals(0x55FF55, ColorFormatter.component("x", "&a").getStyle().getColor().getRgb());
        assertEquals(0x55FF55, ColorFormatter.component("x", "§a").getStyle().getColor().getRgb());

        assertNull(ColorFormatter.component("x", null).getStyle().getColor());
        assertNull(ColorFormatter.component("x", "").getStyle().getColor());
        assertNull(ColorFormatter.component("x", "bad").getStyle().getColor());
    }

    @Test
    @DisplayName("toMiniMessage：<#RRGGBB>文本；无色时纯文本")
    void miniMessage() {
        assertEquals("<#ff5555>称号", ColorFormatter.toMiniMessage("称号", "red"));
        assertEquals("<#55ff55>称号", ColorFormatter.toMiniMessage("称号", "&a"));
        assertEquals("称号", ColorFormatter.toMiniMessage("称号", null));
        assertEquals("称号", ColorFormatter.toMiniMessage("称号", "bad"));
    }

    @Test
    @DisplayName("toMiniMessage：转义 < 与 \\，防标签注入")
    void miniMessageEscaping() {
        assertEquals("<#ff5555>\\<red>", ColorFormatter.toMiniMessage("<red>", "red"));
        assertEquals("a\\\\b", ColorFormatter.escapeMiniMessage("a\\b"));
        assertEquals("\\<x>", ColorFormatter.escapeMiniMessage("<x>"));
    }

    @Test
    @DisplayName("toLegacy：§x§r§r§g§g§b§b 前缀；无色时纯文本")
    void legacy() {
        assertEquals("§x§f§f§5§5§5§5称号", ColorFormatter.toLegacy("称号", "red"));
        assertEquals("§x§5§5§f§f§5§5称号", ColorFormatter.toLegacy("称号", "#55ff55"));
        assertEquals("称号", ColorFormatter.toLegacy("称号", null));
        assertEquals("§x§f§f§f§f§5§5", ColorFormatter.legacyPrefix("yellow"));
        assertEquals("", ColorFormatter.legacyPrefix("bad"));
    }

    @Test
    @DisplayName("null 文本安全")
    void nullText() {
        assertEquals("", ColorFormatter.component(null, "red").getString());
        assertEquals("", ColorFormatter.toMiniMessage(null, "red"));
        assertEquals("", ColorFormatter.toLegacy(null, "red"));
        assertEquals("", ColorFormatter.escapeMiniMessage(null));
    }
}
