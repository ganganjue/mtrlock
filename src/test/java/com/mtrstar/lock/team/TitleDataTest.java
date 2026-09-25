package com.mtrstar.lock.team;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleData} 的纯 JVM 单元测试。
 *
 * <p>用包内可见构造 {@code new TitleData(Path)} 注入临时文件，不触碰 {@code FabricLoader}
 * （单例走 lazy holder，测试不会触发它）。</p>
 */
class TitleDataTest {

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
        // 变更监听器是静态的，避免污染其它测试
        TitleData.setChangeListener(null);
    }

    // =====================================================================
    // 校验（纯函数）
    // =====================================================================

    @Test
    @DisplayName("validateTitle：16 code point 合法，17 拒绝")
    void lengthBoundary() {
        assertEquals("a".repeat(16), TitleData.validateTitle("a".repeat(16)));
        assertNull(TitleData.validateTitle("a".repeat(17)));
    }

    @Test
    @DisplayName("validateTitle：中文按 code point 计数（16 合法 / 17 拒绝）")
    void chineseCodePointCount() {
        final String sixteen = "红".repeat(16);
        final String seventeen = "红".repeat(17);
        assertEquals(sixteen, TitleData.validateTitle(sixteen));
        assertNull(TitleData.validateTitle(seventeen));
    }

    @Test
    @DisplayName("validateTitle：emoji 按 code point 计数（16 个 emoji 合法）")
    void emojiCodePointCount() {
        final String emoji16 = "😀".repeat(16);
        // 每个 emoji 占 2 个 UTF-16 char，但只有 16 个 code point → 合法
        assertEquals(32, emoji16.length());
        assertEquals(emoji16, TitleData.validateTitle(emoji16));
        assertNull(TitleData.validateTitle("😀".repeat(17)));
    }

    @Test
    @DisplayName("validateTitle：null / 空 / 全空白 → null")
    void blankRejected() {
        assertNull(TitleData.validateTitle(null));
        assertNull(TitleData.validateTitle(""));
        assertNull(TitleData.validateTitle("   "));
        assertNull(TitleData.validateTitle("\t\n"));
        assertFalse(TitleData.isValidTitle(""));
    }

    @Test
    @DisplayName("validateTitle：控制字符 → null（前后空白会被 trim，用内部控制字符测）")
    void controlCharRejected() {
        assertNull(TitleData.validateTitle("a\u0007b"));   // BEL
        assertNull(TitleData.validateTitle("红\u0000石"));  // NUL
        assertNull(TitleData.validateTitle("x\ny"));       // LF（内部）
    }

    @Test
    @DisplayName("validateTitle：去掉首尾空白")
    void trimsSurroundingWhitespace() {
        assertEquals("红石局长", TitleData.validateTitle("  红石局长  "));
    }

    // =====================================================================
    // set / get / clear
    // =====================================================================

    @Test
    @DisplayName("set → get → clear 基本流程")
    void setGetClear() {
        final TitleData d = data();
        assertNull(d.getTitle(ALICE));

        assertTrue(d.setTitle(ALICE, "红石局长"));
        assertEquals("红石局长", d.getTitle(ALICE));
        assertEquals(1, d.size());

        assertTrue(d.clearTitle(ALICE));
        assertNull(d.getTitle(ALICE));
        assertEquals(0, d.size());
    }

    @Test
    @DisplayName("set：非法参数全部拒绝并返回 false")
    void setRejectsInvalid() {
        final TitleData d = data();
        assertFalse(d.setTitle(null, "x"));
        assertFalse(d.setTitle("", "x"));
        assertFalse(d.setTitle(ALICE, null));
        assertFalse(d.setTitle(ALICE, ""));
        assertFalse(d.setTitle(ALICE, "   "));
        assertFalse(d.setTitle(ALICE, "a".repeat(17)));
        assertFalse(d.setTitle(ALICE, "a\u0007b"));
        assertNull(d.getTitle(ALICE));
        assertEquals(0, d.size());
    }

    @Test
    @DisplayName("set：写入时 trim；覆盖旧值")
    void setTrimsAndOverwrites() {
        final TitleData d = data();
        assertTrue(d.setTitle(ALICE, "  局长  "));
        assertEquals("局长", d.getTitle(ALICE));
        assertTrue(d.setTitle(ALICE, "总局长"));
        assertEquals("总局长", d.getTitle(ALICE));
        assertEquals(1, d.size());
    }

    @Test
    @DisplayName("clear：非法 uuid / 不存在的称呼 → false")
    void clearEdges() {
        final TitleData d = data();
        assertFalse(d.clearTitle(null));
        assertFalse(d.clearTitle(""));
        assertFalse(d.clearTitle(BOB));
    }

    @Test
    @DisplayName("getTitle / getAll：getAll 是不可变快照")
    void getAllSnapshot() {
        final TitleData d = data();
        d.setTitle(ALICE, "A");
        d.setTitle(BOB, "B");

        final Map<String, String> snap = d.getAll();
        assertEquals(2, snap.size());
        assertEquals("A", snap.get(ALICE));
        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));
        // 快照独立于后续修改
        d.clearTitle(ALICE);
        assertEquals(2, snap.size());
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    @Test
    @DisplayName("持久化往返：save → load 保留称呼")
    void roundTrip() {
        final TitleData a = data();
        assertTrue(a.setTitle(ALICE, "红石局长"));
        assertTrue(a.setTitle(BOB, "调度员"));
        a.save();

        final TitleData b = data();
        b.load();
        assertFalse(b.hasLoadFailed());
        assertEquals("红石局长", b.getTitle(ALICE));
        assertEquals("调度员", b.getTitle(BOB));
        assertEquals(2, b.size());
    }

    @Test
    @DisplayName("文件不存在：load 重置失败标志；之后 save 正常写盘")
    void loadMissingFile() {
        final TitleData d = data();
        d.load();
        assertFalse(d.hasLoadFailed());
        d.setTitle(ALICE, "A");
        d.save();
        assertTrue(Files.exists(file()));
    }

    @Test
    @DisplayName("坏文件保护：load 失败置 loadFailed、保留内存；save 不覆盖坏文件")
    void badFileProtection() throws Exception {
        final TitleData d = data();
        assertTrue(d.setTitle(ALICE, "保留值"));

        Files.writeString(file(), "{ this is not json", StandardCharsets.UTF_8);
        d.load();

        assertTrue(d.hasLoadFailed());
        assertEquals("保留值", d.getTitle(ALICE)); // 内存未被坏文件清空
        assertEquals(1, d.getAll().size());

        d.save(); // loadFailed → 跳过写盘
        assertEquals("{ this is not json", Files.readString(file(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("加载清理：过滤空 / 超长 / 控制字符条目")
    void loadFiltersInvalidEntries() throws Exception {
        Files.writeString(file(),
                "{\"ok\":\"局长\",\"blank\":\"   \",\"long\":\"" + "a".repeat(17) + "\",\"ctl\":\"a\\u0007b\"}",
                StandardCharsets.UTF_8);

        final TitleData d = data();
        d.load();
        assertFalse(d.hasLoadFailed());
        assertEquals("局长", d.getTitle("ok"));
        assertNull(d.getTitle("blank"));
        assertNull(d.getTitle("long"));
        assertNull(d.getTitle("ctl"));
        assertEquals(1, d.size());
    }

    @Test
    @DisplayName("空内存保护：内存为空但文件非空 → save 跳过，不清空文件")
    void emptyMemoryProtection() throws Exception {
        Files.writeString(file(), "{\"x\":\"y\"}", StandardCharsets.UTF_8);
        final TitleData d = data();
        d.save();
        assertEquals("{\"x\":\"y\"}", Files.readString(file(), StandardCharsets.UTF_8));
    }

    // =====================================================================
    // 变更监听器
    // =====================================================================

    @Test
    @DisplayName("监听器：set / clear 触发，未命中的 clear 不触发")
    void changeListener() {
        final AtomicInteger fired = new AtomicInteger();
        TitleData.setChangeListener(fired::incrementAndGet);
        final TitleData d = data();

        d.setTitle(ALICE, "A");
        assertEquals(1, fired.get());

        d.clearTitle(ALICE);
        assertEquals(2, fired.get());

        d.clearTitle(ALICE); // 已不存在 → 不触发
        assertEquals(2, fired.get());
    }

    @Test
    @DisplayName("常量：MAX_TITLE_LENGTH = 16")
    void constant() {
        assertEquals(16, TitleData.MAX_TITLE_LENGTH);
    }
}
