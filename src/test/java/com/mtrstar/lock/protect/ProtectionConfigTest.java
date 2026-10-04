package com.mtrstar.lock.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProtectionConfig} 单元测试（1.3.0）。
 *
 * <p>覆盖：缺省值、完整读取、非法值回退、坏文件保护（loadFailed 后 save 不覆盖）。</p>
 */
class ProtectionConfigTest {

    @TempDir
    Path tempDir;

    private ProtectionConfig config() {
        return new ProtectionConfig(tempDir.resolve("protection.properties"));
    }

    private Path file() {
        return tempDir.resolve("protection.properties");
    }

    private void write(String content) throws Exception {
        Files.writeString(file(), content, StandardCharsets.UTF_8);
    }

    // =====================================================================
    // 缺省值 / 正常读取
    // =====================================================================

    @Test
    @DisplayName("文件不存在：全部默认值，且不算 loadFailed")
    void loadMissingFile() {
        final ProtectionConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertTrue(c.isEnabled());
        assertTrue(c.isProtectStations());
        assertTrue(c.isProtectDepots());
        assertEquals(0, c.getExpandBlocks());
        assertTrue(c.isNotifyPlayer());
    }

    @Test
    @DisplayName("空文件：全部默认值")
    void loadEmptyFile() throws Exception {
        write("");
        final ProtectionConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertTrue(c.isEnabled());
        assertEquals(0, c.getExpandBlocks());
    }

    @Test
    @DisplayName("完整合法文件：逐项读取")
    void loadFullValid() throws Exception {
        write("# comment\n"
                + "enabled=false\n"
                + "protectStations=false\n"
                + "protectDepots=true\n"
                + "expandBlocks=16\n"
                + "notifyPlayer=false\n");
        final ProtectionConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertFalse(c.isEnabled());
        assertFalse(c.isProtectStations());
        assertTrue(c.isProtectDepots());
        assertEquals(16, c.getExpandBlocks());
        assertFalse(c.isNotifyPlayer());
    }

    @Test
    @DisplayName("未知键被忽略；大小写不敏感的 true/false 也接受")
    void unknownKeysIgnoredAndCaseInsensitive() throws Exception {
        write("unknownKey=1\nenabled=TRUE\nnotifyPlayer=False\n");
        final ProtectionConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertTrue(c.isEnabled());
        assertFalse(c.isNotifyPlayer());
    }

    // =====================================================================
    // 非法值回退（不算坏文件）
    // =====================================================================

    @Test
    @DisplayName("布尔值非法：该键回退默认，其余键照常，不标记 loadFailed")
    void invalidBooleanFallsBack() throws Exception {
        write("enabled=maybe\nprotectDepots=false\n");
        final ProtectionConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertTrue(c.isEnabled(), "非法布尔值应回退默认 true");
        assertFalse(c.isProtectDepots(), "其它键不受影响");
    }

    @Test
    @DisplayName("整数非法：回退默认，不标记 loadFailed")
    void invalidIntFallsBack() throws Exception {
        write("expandBlocks=abc\n");
        final ProtectionConfig c = config();
        c.load();
        assertFalse(c.hasLoadFailed());
        assertEquals(ProtectionConfig.DEFAULT_EXPAND_BLOCKS, c.getExpandBlocks());
    }

    @Test
    @DisplayName("负数扩张收敛为 0；超过上限收敛为上限")
    void expandBlocksClamped() {
        final ProtectionConfig c = config();
        c.setExpandBlocks(-5);
        assertEquals(0, c.getExpandBlocks());
        c.setExpandBlocks(ProtectionConfig.MAX_EXPAND_BLOCKS + 1000);
        assertEquals(ProtectionConfig.MAX_EXPAND_BLOCKS, c.getExpandBlocks());
    }

    @Test
    @DisplayName("文件里扩张值为负数 / 超限也会收敛")
    void expandBlocksFromFileClamped() throws Exception {
        write("expandBlocks=-3\n");
        final ProtectionConfig c = config();
        c.load();
        assertEquals(0, c.getExpandBlocks());

        write("expandBlocks=999999\n");
        final ProtectionConfig c2 = config();
        c2.load();
        assertEquals(ProtectionConfig.MAX_EXPAND_BLOCKS, c2.getExpandBlocks());
    }

    // =====================================================================
    // 坏文件保护
    // =====================================================================

    @Test
    @DisplayName("坏文件：loadFailed=true，保留当前内存值，且 save 不覆盖坏文件")
    void corruptFileKeepsValuesAndBlocksSave() throws Exception {
        final ProtectionConfig c = config();
        // 先设一组与默认不同的值，load 坏文件后必须保持不变
        c.setEnabled(false);
        c.setNotifyPlayer(false);

        // 运行期字符串是 enable + 反斜杠 + uZZZZ（非法转义），故用拼接避免源码里的 unicode 逃逸
        final String corrupt = "enabled=" + "\\" + "uZZZZ";
        write(corrupt);

        c.load();
        assertTrue(c.hasLoadFailed());
        assertFalse(c.isEnabled(), "坏文件不应改写内存值");
        assertFalse(c.isNotifyPlayer());

        c.save();
        assertEquals(corrupt, Files.readString(file(), StandardCharsets.UTF_8),
                "loadFailed 后 save 必须跳过，避免覆盖坏文件");
    }

    @Test
    @DisplayName("坏文件恢复：改成合法内容后重新 load，loadFailed 清掉并可继续保存")
    void corruptFileRecovers() throws Exception {
        write("enabled=" + "\\" + "uZZZZ");
        final ProtectionConfig c = config();
        c.load();
        assertTrue(c.hasLoadFailed());

        write("enabled=true\nexpandBlocks=8\n");
        c.load();
        assertFalse(c.hasLoadFailed());
        assertEquals(8, c.getExpandBlocks());

        c.setExpandBlocks(4);
        c.save();
        final ProtectionConfig reloaded = config();
        reloaded.load();
        assertFalse(reloaded.hasLoadFailed());
        assertEquals(4, reloaded.getExpandBlocks());
    }

    // =====================================================================
    // 往返 / 状态
    // =====================================================================

    @Test
    @DisplayName("save → load 往返保留全部设置")
    void roundTrip() {
        final ProtectionConfig a = config();
        a.setEnabled(false);
        a.setProtectStations(false);
        a.setProtectDepots(false);
        a.setExpandBlocks(12);
        a.setNotifyPlayer(false);
        a.save();

        final ProtectionConfig b = config();
        b.load();
        assertFalse(b.hasLoadFailed());
        assertFalse(b.isEnabled());
        assertFalse(b.isProtectStations());
        assertFalse(b.isProtectDepots());
        assertEquals(12, b.getExpandBlocks());
        assertFalse(b.isNotifyPlayer());
    }

    @Test
    @DisplayName("resetToDefaults 恢复全部默认值")
    void resetToDefaults() {
        final ProtectionConfig c = config();
        c.setEnabled(false);
        c.setProtectStations(false);
        c.setProtectDepots(false);
        c.setExpandBlocks(9);
        c.setNotifyPlayer(false);
        c.resetToDefaults();
        assertTrue(c.isEnabled());
        assertTrue(c.isProtectStations());
        assertTrue(c.isProtectDepots());
        assertEquals(0, c.getExpandBlocks());
        assertTrue(c.isNotifyPlayer());
    }

    @Test
    @DisplayName("statusLines 含各项当前状态")
    void statusLines() {
        final ProtectionConfig c = config();
        c.setEnabled(false);
        c.setExpandBlocks(5);
        final String text = String.join("\n", c.statusLines());
        assertTrue(text.contains("已禁用"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("protection.properties"));
    }
}
