package com.mtrstar.lock.perm;

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
 * {@link OwnershipData} 的“防覆盖”保护单元测试。
 *
 * <p>用包内可见构造 {@code new OwnershipData(Path)} 注入临时文件，
 * 不触碰 {@code FabricLoader}（单例走 lazy holder，测试不会触发它）。</p>
 */
class OwnershipDataSafetyTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("ownership.json");
    }

    // =====================================================================
    // 保护 1：load 失败 → save 跳过（不回写坏文件）
    // =====================================================================

    @Test
    @DisplayName("load 失败 → 置 loadFailed；之后 save 跳过，不覆盖坏文件")
    void loadFailureMakesSaveSkip() throws Exception {
        final Path f = file();
        final String corrupt = "{ this is not valid json";
        Files.writeString(f, corrupt, StandardCharsets.UTF_8);

        final OwnershipData data = new OwnershipData(f);
        data.load();
        assertTrue(data.hasLoadFailed(), "损坏文件后 loadFailed 应为 true");

        // 故意往内存塞数据，证明“跳过”不是因为内存为空
        data.setCreator("route:ABCDEF0123456789", "uuid-A");
        data.save();

        assertEquals(corrupt, Files.readString(f, StandardCharsets.UTF_8),
                "load 失败后 save 不得覆盖坏文件");
    }

    @Test
    @DisplayName("文件不存在时 load 重置 loadFailed，之后 save 正常")
    void missingFileResetsFlag() throws Exception {
        final Path f = file();
        Files.writeString(f, "{ bad json", StandardCharsets.UTF_8);

        final OwnershipData data = new OwnershipData(f);
        data.load();
        assertTrue(data.hasLoadFailed());

        Files.delete(f);
        data.load(); // 文件不存在 → 重置
        assertFalse(data.hasLoadFailed());

        data.setCreator("depot:0000000000000002", "uuid-C");
        data.save(); // 应正常写盘
        assertTrue(Files.exists(f));
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains("depot:0000000000000002"));
    }

    // =====================================================================
    // 保护 2：内存为空 + 文件非空 → save 跳过
    // =====================================================================

    @Test
    @DisplayName("内存为空 + 文件非空 → save 跳过，不清空文件")
    void emptyMemoryNonEmptyFileSkips() throws Exception {
        final Path f = file();
        final String original = "{\"route:ABCDEF0123456789\":\"uuid-A\"}";
        Files.writeString(f, original, StandardCharsets.UTF_8);

        final OwnershipData data = new OwnershipData(f); // 不 load，内存为空
        assertTrue(data.getAll().isEmpty());
        data.save();

        assertEquals(original, Files.readString(f, StandardCharsets.UTF_8),
                "空内存不得覆盖非空文件");
    }

    @Test
    @DisplayName("空文件（0 字节）不算非空，save 允许写入")
    void emptyFileIsAllowedToSave() throws Exception {
        final Path f = file();
        Files.createFile(f); // 0 字节

        final OwnershipData data = new OwnershipData(f);
        data.load();
        assertFalse(data.hasLoadFailed());
        data.save(); // 0 字节不触发保护 2

        assertTrue(Files.size(f) > 0L, "空文件应被正常写入");
    }

    // =====================================================================
    // 正常路径不受影响
    // =====================================================================

    @Test
    @DisplayName("正常路径：save → load → 再 save 都能正常写读")
    void normalPathStillWorks() throws Exception {
        final Path f = file();

        final OwnershipData first = new OwnershipData(f);
        first.setCreator("route:ABCDEF0123456789", "uuid-A");
        first.save(); // 文件不存在 → 正常写
        assertTrue(Files.exists(f));
        assertFalse(first.hasLoadFailed());

        final OwnershipData second = new OwnershipData(f);
        second.load();
        assertFalse(second.hasLoadFailed());
        assertEquals("uuid-A", second.getCreator("route:ABCDEF0123456789"));

        second.setCreator("station:0000000000000001", "uuid-B");
        second.save(); // 内存非空 → 正常写

        final OwnershipData third = new OwnershipData(f);
        third.load();
        assertEquals("uuid-A", third.getCreator("route:ABCDEF0123456789"));
        assertEquals("uuid-B", third.getCreator("station:0000000000000001"));
    }

    @Test
    @DisplayName("内存清空后再 save：文件非空时跳过（额外安全网的取舍）")
    void clearingAllThenSaveIsSkippedWhenFileNonEmpty() throws Exception {
        final Path f = file();

        final OwnershipData data = new OwnershipData(f);
        data.setCreator("route:ABCDEF0123456789", "uuid-A");
        data.save();
        assertTrue(Files.size(f) > 0L);

        // 合法地删掉所有记录后再 save —— 按“安全网”设计会被跳过
        data.removeCreator("route:ABCDEF0123456789");
        assertTrue(data.getAll().isEmpty());
        data.save();

        // 文件仍保留旧内容（这是有意的保护，避免空内存误清空）
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains("route:ABCDEF0123456789"));
    }
}
