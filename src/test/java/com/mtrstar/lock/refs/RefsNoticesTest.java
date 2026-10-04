package com.mtrstar.lock.refs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RefsNotices} 单元测试（1.4.0）+ 语言键覆盖断言。
 *
 * <p>用 classloader 读 {@code /assets/mtrlock/lang/*.json}（{@code processResources} 的产物），
 * 保证两种语言都带上了提示文案的占位符。</p>
 */
class RefsNoticesTest {

    private static final String R1 = "route:0000000000000001";
    private static final String R2 = "route:0000000000000002";
    private static final String R3 = "route:0000000000000003";
    private static final String R4 = "route:0000000000000004";
    private static final String R5 = "route:0000000000000005";
    private static final String R6 = "route:0000000000000006";

    private static RouteRefReconciler.Ref ref(String routeId, long platformId) {
        return new RouteRefReconciler.Ref(routeId, platformId);
    }

    @Test
    @DisplayName("summarize：同一线路多个站台只列一次，顺序按出现顺序")
    void summarizeDeduplicates() {
        assertEquals(R1 + ", " + R2,
                RefsNotices.summarize(List.of(ref(R1, 1), ref(R2, 2), ref(R1, 3))));
    }

    @Test
    @DisplayName("summarize：超过 5 条线路时截断并标注总数")
    void summarizeTruncates() {
        final List<RouteRefReconciler.Ref> refs = new ArrayList<>();
        for (String routeId : List.of(R1, R2, R3, R4, R5, R6)) {
            refs.add(ref(routeId, 1));
        }
        final String summary = RefsNotices.summarize(refs);
        assertTrue(summary.startsWith(R1 + ", " + R2 + ", " + R3 + ", " + R4 + ", " + R5), summary);
        assertFalse(summary.contains(R6), "第 6 条线路不应逐条列出");
        assertTrue(summary.endsWith("等 6 条"), summary);
    }

    @Test
    @DisplayName("summarize：空 / null 输入返回空串，不 NPE")
    void summarizeEmpty() {
        assertEquals("", RefsNotices.summarize(null));
        assertEquals("", RefsNotices.summarize(List.of()));
    }

    @Test
    @DisplayName("noticesFor：只有移除 / 只有恢复 / 两者都有")
    void noticesForCombinations() {
        final RouteRefReconciler.Result removedOnly = new RouteRefReconciler.Result(
                List.of(ref(R1, 1)), List.of(), List.of());
        final List<RefsNotices.Notice> onlyRemoved = RefsNotices.noticesFor(removedOnly);
        assertEquals(1, onlyRemoved.size());
        assertEquals(RefsNotices.KEY_REMOVED, onlyRemoved.get(0).key());
        assertEquals(R1, onlyRemoved.get(0).routesSummary());
        assertEquals(1, onlyRemoved.get(0).routeCount());

        final RouteRefReconciler.Result restoredOnly = new RouteRefReconciler.Result(
                List.of(), List.of(ref(R2, 2)), List.of());
        final List<RefsNotices.Notice> onlyRestored = RefsNotices.noticesFor(restoredOnly);
        assertEquals(1, onlyRestored.size());
        assertEquals(RefsNotices.KEY_RESTORED, onlyRestored.get(0).key());

        final RouteRefReconciler.Result both = new RouteRefReconciler.Result(
                List.of(ref(R1, 1)), List.of(ref(R2, 2)), List.of(ref(R3, 3)));
        final List<RefsNotices.Notice> bothNotices = RefsNotices.noticesFor(both);
        assertEquals(2, bothNotices.size(), "清理幽灵记录只写日志、不发聊天");
        assertEquals(RefsNotices.KEY_REMOVED, bothNotices.get(0).key());
        assertEquals(RefsNotices.KEY_RESTORED, bothNotices.get(1).key());
    }

    @Test
    @DisplayName("noticesFor：无改动 / null → 空列表")
    void noticesForNoChange() {
        assertTrue(RefsNotices.noticesFor(null).isEmpty());
        assertTrue(RefsNotices.noticesFor(new RouteRefReconciler.Result(
                List.of(), List.of(), List.of())).isEmpty());
        assertTrue(RefsNotices.noticesFor(new RouteRefReconciler.Result(
                List.of(), List.of(), List.of(ref(R1, 1)))).isEmpty(), "只有幽灵清理不发聊天");
    }

    // =====================================================================
    // lang 覆盖
    // =====================================================================

    @Test
    @DisplayName("lang：中英文都有移除 / 恢复提示键，且带 %s 占位符")
    void langKeysPresent() throws IOException {
        for (String path : List.of("/assets/mtrlock/lang/zh_cn.json", "/assets/mtrlock/lang/en_us.json")) {
            final String json = readResource(path);
            for (String key : List.of(RefsNotices.KEY_REMOVED, RefsNotices.KEY_RESTORED)) {
                assertTrue(json.contains("\"" + key + "\""), path + " 缺少键 " + key);
            }
            assertTrue(json.contains("%s"), path + " 的提示文案应带 %s 占位符");
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = RefsNoticesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
