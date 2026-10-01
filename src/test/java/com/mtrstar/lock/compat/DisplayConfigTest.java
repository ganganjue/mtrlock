package com.mtrstar.lock.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DisplayConfig} 单元测试（1.2.4）。 */
class DisplayConfigTest {

    @TempDir
    Path tempDir;

    private DisplayConfig config() {
        return new DisplayConfig(tempDir.resolve("display.json"));
    }

    private Path file() {
        return tempDir.resolve("display.json");
    }

    @Test
    @DisplayName("Format.parse：大小写 / 别名；非法 → null")
    void parseFormat() {
        assertEquals(DisplayConfig.Format.MINIMESSAGE, DisplayConfig.Format.parse("minimessage"));
        assertEquals(DisplayConfig.Format.MINIMESSAGE, DisplayConfig.Format.parse(" MiniMessage "));
        assertEquals(DisplayConfig.Format.MINIMESSAGE, DisplayConfig.Format.parse("mm"));
        assertEquals(DisplayConfig.Format.LEGACY, DisplayConfig.Format.parse("legacy"));
        assertEquals(DisplayConfig.Format.LEGACY, DisplayConfig.Format.parse("SECTION"));
        assertNull(DisplayConfig.Format.parse("nope"));
        assertNull(DisplayConfig.Format.parse(null));
        assertEquals("minimessage", DisplayConfig.Format.MINIMESSAGE.id());
        assertEquals("legacy", DisplayConfig.Format.LEGACY.id());
    }

    @Test
    @DisplayName("文件不存在：默认 Minimessage，且不算 loadFailed")
    void loadMissing() {
        final DisplayConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertEquals(DisplayConfig.Format.MINIMESSAGE, c.getFormat());
    }

    @Test
    @DisplayName("往返：save → load 保留格式")
    void roundTrip() {
        final DisplayConfig a = config();
        a.setFormat(DisplayConfig.Format.LEGACY);
        a.save();

        final DisplayConfig b = config();
        b.load();
        assertFalse(b.hasLoadFailed());
        assertEquals(DisplayConfig.Format.LEGACY, b.getFormat());
    }

    @Test
    @DisplayName("文件里格式非法 / 类型不对 → 回退默认，不算 loadFailed")
    void invalidValueFallsBack() throws Exception {
        Files.writeString(file(), "{\"placeholderFormat\":\"nope\"}", StandardCharsets.UTF_8);
        final DisplayConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertEquals(DisplayConfig.Format.MINIMESSAGE, c.getFormat());

        Files.writeString(file(), "{\"placeholderFormat\":123}", StandardCharsets.UTF_8);
        final DisplayConfig d = config();
        d.load();
        assertFalse(d.hasLoadFailed());
        assertEquals(DisplayConfig.Format.MINIMESSAGE, d.getFormat());
    }

    @Test
    @DisplayName("坏文件保护：load 失败 → loadFailed、保留内存设置、save 不覆盖")
    void badFileProtection() throws Exception {
        final DisplayConfig c = config();
        c.setFormat(DisplayConfig.Format.LEGACY);
        Files.writeString(file(), "{ not json", StandardCharsets.UTF_8);
        c.load();

        assertTrue(c.hasLoadFailed());
        assertEquals(DisplayConfig.Format.LEGACY, c.getFormat(), "内存设置应保留");
        c.save();
        assertEquals("{ not json", Files.readString(file(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空文件 → 默认格式")
    void emptyFile() throws Exception {
        Files.writeString(file(), "", StandardCharsets.UTF_8);
        final DisplayConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertEquals(DisplayConfig.Format.MINIMESSAGE, c.getFormat());
    }
}
