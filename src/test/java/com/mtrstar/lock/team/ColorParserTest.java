package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ColorParser} 单元测试（1.2.4）：全输入形式 + 非法输入。 */
class ColorParserTest {

    @ParameterizedTest
    @CsvSource({
            "&0,#000000", "&1,#0000aa", "&2,#00aa00", "&3,#00aaaa",
            "&4,#aa0000", "&5,#aa00aa", "&6,#ffaa00", "&7,#aaaaaa",
            "&8,#555555", "&9,#5555ff", "&a,#55ff55", "&b,#55ffff",
            "&c,#ff5555", "&d,#ff55ff", "&e,#ffff55", "&f,#ffffff"
    })
    @DisplayName("16 原版色代码 &0-&f")
    void legacyCodes(String input, String expected) {
        assertEquals(expected, ColorParser.parse(input).color());
        assertTrue(ColorParser.parse(input).valid());
    }

    @Test
    @DisplayName("§ 前缀与裸代码等价；大小写不敏感")
    void legacyVariants() {
        assertEquals("#55ff55", ColorParser.parse("§a").color());
        assertEquals("#55ff55", ColorParser.parse("a").color());
        assertEquals("#ff5555", ColorParser.parse("&C").color());
        assertEquals("#ff5555", ColorParser.parse("§C").color());
    }

    @ParameterizedTest
    @CsvSource({
            "#ff5555,#ff5555", "#ff5555,#ff5555", "#aAbBcC,#aabbcc",
            "red,#ff5555", "gold,#ffaa00", "aqua,#55ffff",
            "dark_blue,#0000aa", "darkblue,#0000aa", "light_purple,#ff55ff",
            "lightpurple,#ff55ff", "gray,#aaaaaa", "dark_gray,#555555",
            "light_gray,#aaaaaa", "white,#ffffff", "black,#000000"
    })
    @DisplayName("HEX 与颜色名（大小写 / 下划线变体）")
    void hexAndNames(String input, String expected) {
        assertEquals(expected, ColorParser.parse(input).color());
    }

    @Test
    @DisplayName("&x&r&r&g&g&b&b 与 §x 形式")
    void legacyHexSequence() {
        assertEquals("#ff5555", ColorParser.parse("&x&f&f&5&5&5&5").color());
        assertEquals("#ff5555", ColorParser.parse("§x§f§f§5§5§5§5").color());
        assertEquals("#aabbcc", ColorParser.parse("&x&A&A&B&B&C&C").color());
    }

    @Test
    @DisplayName("空 / null / 空白 → 合法且无色")
    void emptyIsNone() {
        for (String input : new String[]{null, "", "   ", "\t"}) {
            final ColorParser.Result r = ColorParser.parse(input);
            assertTrue(r.valid(), String.valueOf(input));
            assertNull(r.color());
            assertTrue(r.isNone());
        }
    }

    @Test
    @DisplayName("非法输入 → valid=false / color=null")
    void invalid() {
        for (String input : new String[]{
                "#GGGGGG", "#FFF", "#ffffffF", "##ffffff",
                "&z", "&", "§", "&x&r&r&g&g&b", "&x&r&r&g&g&b&z",
                "notacolor", "0xFFFFFF", "#12345", "&10", "红色"
        }) {
            final ColorParser.Result r = ColorParser.parse(input);
            assertFalse(r.valid(), "应非法: " + input);
            assertNull(r.color(), "应无色: " + input);
        }
    }

    @Test
    @DisplayName("normalize / isValid / rgb / nameOf")
    void helpers() {
        assertEquals("#ff5555", ColorParser.normalize("red"));
        assertNull(ColorParser.normalize("bad"));
        assertNull(ColorParser.normalize(null));
        assertTrue(ColorParser.isValid(""));
        assertTrue(ColorParser.isValid("#00FF00"));
        assertFalse(ColorParser.isValid("nope"));

        assertEquals(0xFF5555, ColorParser.rgb("#ff5555"));
        assertEquals(0x000000, ColorParser.rgb("&0"));
        assertEquals(-1, ColorParser.rgb("bad"));
        assertEquals(-1, ColorParser.rgb(null));

        assertEquals("red", ColorParser.nameOf("#ff5555"));
        assertEquals("gray", ColorParser.nameOf("gray"));
        assertNull(ColorParser.nameOf("#123456"));
    }

    @Test
    @DisplayName("16 个颜色名与 HEX 表长度一致")
    void tableSizes() {
        assertEquals(16, ColorParser.COLOR_NAMES.length);
        assertEquals(16, ColorParser.COLOR_HEX.length);
        assertEquals(ColorParser.COLOR_COUNT, ColorParser.COLOR_NAMES.length);
    }
}
