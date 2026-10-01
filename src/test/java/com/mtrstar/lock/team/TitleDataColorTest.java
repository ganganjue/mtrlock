package com.mtrstar.lock.team;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TitleData} 颜色升级与新旧格式往返测试（1.2.4）。 */
class TitleDataColorTest {

    private static final String ALICE = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String BOB = "bbbbbbbb-2222-2222-2222-222222222222";

    @TempDir
    Path tempDir;

    private TitleData data() {
        return new TitleData(tempDir.resolve("titles.json"));
    }

    private Path file() {
        return tempDir.resolve("titles.json");
    }

    @AfterEach
    void tearDown() {
        TitleData.setChangeListener(null);
    }

    // =====================================================================
    // 文本 + 颜色
    // =====================================================================

    @Test
    @DisplayName("setTitle(text,color)：文本 + 颜色都写入并规范化")
    void setTextAndColor() {
        final TitleData d = data();
        assertTrue(d.setTitle(ALICE, "红石局长", "red"));
        assertEquals("红石局长", d.getTitle(ALICE));
        assertEquals("#ff5555", d.getColor(ALICE));
        assertEquals(new TitleEntry("红石局长", "#ff5555"), d.getEntry(ALICE));
    }

    @Test
    @DisplayName("setTitle(text) 保留原颜色；setTitle(text,null) 清除颜色")
    void preserveAndClearColor() {
        final TitleData d = data();
        d.setTitle(ALICE, "甲", "gold");
        assertTrue(d.setTitle(ALICE, "乙"));
        assertEquals("#ffaa00", d.getColor(ALICE), "4 参重载应保留颜色");

        assertTrue(d.setTitle(ALICE, "丙", null));
        assertNull(d.getColor(ALICE), "显式无色应清除颜色");
        assertEquals("丙", d.getTitle(ALICE));
    }

    @Test
    @DisplayName("setTitle：颜色非法 → false，且不覆盖原值")
    void invalidColorRejected() {
        final TitleData d = data();
        d.setTitle(ALICE, "甲", "red");
        assertFalse(d.setTitle(ALICE, "乙", "notacolor"));
        assertEquals("甲", d.getTitle(ALICE));
        assertEquals("#ff5555", d.getColor(ALICE));
    }

    @Test
    @DisplayName("setColor / resetColor：需已有称呼，空值表示清除")
    void setAndResetColor() {
        final TitleData d = data();
        assertFalse(d.setColor(ALICE, "red"), "无称呼时不能只设颜色");

        d.setTitle(ALICE, "局长");
        assertTrue(d.setColor(ALICE, "aqua"));
        assertEquals("#55ffff", d.getColor(ALICE));

        assertTrue(d.setColor(ALICE, null));
        assertNull(d.getColor(ALICE));
        assertFalse(d.resetColor(ALICE), "已是无色 → resetColor 返回 false");

        assertTrue(d.setColor(ALICE, "&c"));
        assertTrue(d.resetColor(ALICE));
        assertNull(d.getColor(ALICE));
        assertEquals("局长", d.getTitle(ALICE));
    }

    @Test
    @DisplayName("clearTitle 连同颜色一起清除")
    void clearRemovesColor() {
        final TitleData d = data();
        d.setTitle(ALICE, "局长", "red");
        assertTrue(d.clearTitle(ALICE));
        assertNull(d.getTitle(ALICE));
        assertNull(d.getColor(ALICE));
        assertEquals(0, d.getAllColors().size());
    }

    @Test
    @DisplayName("getAll / getAllEntries / getAllColors")
    void snapshots() {
        final TitleData d = data();
        d.setTitle(ALICE, "甲", "red");
        d.setTitle(BOB, "乙");

        final Map<String, String> texts = d.getAll();
        assertEquals(2, texts.size());
        assertEquals("甲", texts.get(ALICE));
        assertNull(texts.get("missing"));

        assertEquals(2, d.getAllEntries().size());
        assertEquals("#ff5555", d.getAllEntries().get(ALICE).color());

        final Map<String, String> colors = d.getAllColors();
        assertEquals(1, colors.size());
        assertEquals("#ff5555", colors.get(ALICE));
        assertNull(colors.get(BOB));
    }

    // =====================================================================
    // 新旧格式往返
    // =====================================================================

    @Test
    @DisplayName("新格式往返：save → load 保留文本与颜色")
    void newFormatRoundTrip() {
        final TitleData a = data();
        a.setTitle(ALICE, "红石局长", "red");
        a.setTitle(BOB, "调度员", "#00aa00");
        a.save();

        final TitleData b = data();
        b.load();
        assertFalse(b.hasLoadFailed());
        assertEquals("红石局长", b.getTitle(ALICE));
        assertEquals("#ff5555", b.getColor(ALICE));
        assertEquals("#00aa00", b.getColor(BOB));
    }

    @Test
    @DisplayName("旧格式（1.2.3 字符串）读取为 text + color=null，升级无感")
    void oldFormatMigrates() throws Exception {
        Files.writeString(file(),
                "{\"alice\":\"红石局长\",\"bob\":\"调度员\"}", StandardCharsets.UTF_8);

        final TitleData d = data();
        d.load();
        assertFalse(d.hasLoadFailed());
        assertEquals("红石局长", d.getTitle("alice"));
        assertNull(d.getColor("alice"));
        assertEquals(2, d.size());

        // 再 save 会写成新格式（对象），旧数据不丢
        d.save();
        final String saved = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"text\""));
        final TitleData reloaded = data();
        reloaded.load();
        assertEquals("红石局长", reloaded.getTitle("alice"));
        assertNull(reloaded.getColor("alice"));
    }

    @Test
    @DisplayName("新旧混合格式：逐条兼容")
    void mixedFormat() throws Exception {
        Files.writeString(file(),
                "{\"old\":\"纯文本\",\"new\":{\"text\":\"带色\",\"color\":\"#ff5555\"},"
                        + "\"badcolor\":{\"text\":\"坏色\",\"color\":\"nope\"}}",
                StandardCharsets.UTF_8);

        final TitleData d = data();
        d.load();
        assertFalse(d.hasLoadFailed());
        assertEquals("纯文本", d.getTitle("old"));
        assertNull(d.getColor("old"));
        assertEquals("带色", d.getTitle("new"));
        assertEquals("#ff5555", d.getColor("new"));
        assertEquals("坏色", d.getTitle("badcolor"));
        assertNull(d.getColor("badcolor"), "非法颜色按无色处理，不漏掉称呼");
    }

    @Test
    @DisplayName("非法条目过滤（新旧格式都适用）")
    void filtersInvalid() throws Exception {
        Files.writeString(file(),
                "{\"ok\":\"局长\",\"blank\":\"   \",\"long\":\"" + "a".repeat(17) + "\","
                        + "\"obj\":{\"text\":\"  \"},\"num\":123}",
                StandardCharsets.UTF_8);

        final TitleData d = data();
        d.load();
        assertFalse(d.hasLoadFailed());
        assertEquals("局长", d.getTitle("ok"));
        assertNull(d.getTitle("blank"));
        assertNull(d.getTitle("long"));
        assertNull(d.getTitle("obj"));
        assertNull(d.getTitle("num"));
        assertEquals(1, d.size());
    }

    @Test
    @DisplayName("坏文件保护：颜色升级后仍不被空数据覆盖")
    void badFileProtection() throws Exception {
        final TitleData d = data();
        d.setTitle(ALICE, "保留值", "red");

        Files.writeString(file(), "{ this is not json", StandardCharsets.UTF_8);
        d.load();

        assertTrue(d.hasLoadFailed());
        assertEquals("保留值", d.getTitle(ALICE));
        assertEquals("#ff5555", d.getColor(ALICE));

        d.save();
        assertEquals("{ this is not json", Files.readString(file(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空内存保护：内存为空但文件非空 → save 跳过")
    void emptyMemoryProtection() throws Exception {
        Files.writeString(file(), "{\"x\":{\"text\":\"y\",\"color\":\"#ffffff\"}}", StandardCharsets.UTF_8);
        final TitleData d = data();
        d.save();
        assertEquals("{\"x\":{\"text\":\"y\",\"color\":\"#ffffff\"}}",
                Files.readString(file(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空文件 → 空数据且不算 loadFailed")
    void emptyFile() throws Exception {
        Files.writeString(file(), "", StandardCharsets.UTF_8);
        final TitleData d = data();
        d.setTitle(ALICE, "旧内存", "red");
        d.load();
        assertFalse(d.hasLoadFailed());
        assertNull(d.getTitle(ALICE), "空文件按空数据加载（与 1.2.3 行为一致）");
    }
}
