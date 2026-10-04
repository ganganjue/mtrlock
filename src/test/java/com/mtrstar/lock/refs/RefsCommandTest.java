package com.mtrstar.lock.refs;

import com.mtrstar.lock.command.RefsCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mtrlock refs ...} 命令层的纯逻辑测试（1.4.0）。
 *
 * <p>命令本体依赖 Minecraft 的 {@code CommandContext}，不做纯 JVM 测试；
 * 这里（放在 {@code refs} 包内，便于用包内构造注入临时文件与假时钟）覆盖它调用的两块逻辑：
 * {@link RefsCommand#statusLines} 文案，以及 {@link RouteRefReconciler#forgetRemoved}
 * 的手动恢复语义（删记录 + 立即落盘）。</p>
 */
class RefsCommandTest {

    private static final String ROUTE = "route:000000000000000A";

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private Path file() {
        return tempDir.resolve("removed_refs.json");
    }

    private RemovedRefsData data() {
        return new RemovedRefsData(file(), now::get);
    }

    // =====================================================================
    // status 文案
    // =====================================================================

    @Test
    @DisplayName("status：空账本 → 显示 0 条 + 正常 + 文件路径")
    void statusEmptyLedger() {
        final RemovedRefsData data = data();
        data.load();

        final List<String> lines = RefsCommand.statusLines(data);
        assertTrue(lines.get(0).contains("线路引用账本"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("记录条数：0 条")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("加载状态：正常")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("保留期限：30 天")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains(file().toString())), lines.toString());
    }

    @Test
    @DisplayName("status：有记录 + 坏文件 → 条数与失败状态都正确")
    void statusWithRecordsAndFailure() throws IOException {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE, 11L, "station:0000000000000001");
        data.recordRemoved(ROUTE, 12L, "station:0000000000000001");
        data.save();

        final RemovedRefsData loaded = data();
        loaded.load();
        assertTrue(RefsCommand.statusLines(loaded).stream()
                .anyMatch(line -> line.contains("记录条数：2 条，涉及 1 条线路")), RefsCommand.statusLines(loaded).toString());

        Files.writeString(file(), "{ broken", StandardCharsets.UTF_8);
        loaded.load();
        assertTrue(loaded.isLoadFailed());
        assertTrue(RefsCommand.statusLines(loaded).stream()
                .anyMatch(line -> line.contains("加载状态：上次加载失败")), RefsCommand.statusLines(loaded).toString());
    }

    @Test
    @DisplayName("status：null 账本 → 不 NPE，提示尚未初始化")
    void statusNullLedger() {
        final List<String> lines = RefsCommand.statusLines(null);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("尚未初始化"), lines.toString());
    }

    // =====================================================================
    // 手动 restore 的底层语义
    // =====================================================================

    @Test
    @DisplayName("forgetRemoved：记录存在 → 删除并立即落盘")
    void forgetRemovedDeletesAndFlushes() throws IOException {
        final RemovedRefsData data = data();
        data.recordRemoved(ROUTE, 11L, "station:0000000000000001");
        data.recordRemoved(ROUTE, 12L, "station:0000000000000001");
        data.save();

        assertTrue(RouteRefReconciler.forgetRemoved(data, ROUTE, 11L));
        assertEquals(1, data.size(), "只删指定的那条");
        assertFalse(data.hasRemoved(ROUTE, 11L));
        assertTrue(data.hasRemoved(ROUTE, 12L));

        // flush 让「手动恢复」立刻可见，不必等 debounce
        final RemovedRefsData reloaded = data();
        reloaded.load();
        assertEquals(1, reloaded.size());
        assertFalse(reloaded.hasRemoved(ROUTE, 11L));
    }

    @Test
    @DisplayName("forgetRemoved：记录不存在 → false，不写盘")
    void forgetRemovedMissingRecord() {
        final RemovedRefsData data = data();
        assertFalse(RouteRefReconciler.forgetRemoved(data, ROUTE, 99L));
        assertFalse(Files.exists(file()), "没有变更就不该产生写盘");
        assertFalse(RouteRefReconciler.forgetRemoved(null, ROUTE, 99L));
    }

    @Test
    @DisplayName("forgetRemoved：坏文件账本 → 删除内存记录但 flush 被 loadFailed 拦下")
    void forgetRemovedRespectsLoadFailed() throws IOException {
        Files.writeString(file(), "not json", StandardCharsets.UTF_8);
        final RemovedRefsData data = data();
        data.load();
        assertTrue(data.isLoadFailed());

        // loadFailed 时账本内存为空（解析失败），因此这里没有可删记录
        assertFalse(RouteRefReconciler.forgetRemoved(data, ROUTE, 11L));
        assertEquals("not json", Files.readString(file(), StandardCharsets.UTF_8), "坏文件不能被覆盖");
    }
}
