package com.mtrstar.lock.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleActions} 的单元测试（1.2.3）。
 *
 * <p>覆盖验收点：</p>
 * <ul>
 *   <li>无 OP 3+ 时<b>不可打开</b>称号 GUI（{@link TitleActions#canOpen(boolean)}）；</li>
 *   <li>无 OP 3+ 时<b>不可操作</b>（设置 / 清除都返回 {@link ResultCode#NEED_ADMIN} 且不写入数据）；</li>
 *   <li>OP 3+ 的设置 / 清除 / 中文 / 16 字符上限 / 非法文本。</li>
 * </ul>
 */
class TitleActionsTest {

    private static final String ADMIN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TARGET = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @TempDir
    Path tempDir;

    private TitleData titles;
    private TitleActions actions;

    @BeforeEach
    void setUp() {
        titles = new TitleData(tempDir.resolve("titles.json"));
        actions = new TitleActions(titles);
    }

    // =====================================================================
    // 打开权限
    // =====================================================================

    @Test
    @DisplayName("非 OP 3+ 不可打开称号 GUI")
    void cannotOpenWithoutAdmin() {
        assertFalse(TitleActions.canOpen(false));
        assertTrue(TitleActions.canOpen(true));
    }

    @Test
    @DisplayName("非 OP 3+ 设置 / 清除都失败且不写入数据")
    void cannotOperateWithoutAdmin() {
        assertEquals(ResultCode.NEED_ADMIN, actions.setTitle(ADMIN, false, TARGET, "服主").code());
        assertEquals(ResultCode.NEED_ADMIN, actions.clearTitle(ADMIN, false, TARGET).code());
        assertNull(titles.getTitle(TARGET));
        assertEquals(0, titles.size());
    }

    @Test
    @DisplayName("管理员设置：写入成功；再清除成功；重复清除 → NO_TITLE")
    void adminSetAndClear() {
        assertEquals(ResultCode.TITLE_SET, actions.setTitle(ADMIN, true, TARGET, "红石局长").code());
        assertEquals("红石局长", titles.getTitle(TARGET));

        assertEquals(ResultCode.TITLE_CLEARED, actions.clearTitle(ADMIN, true, TARGET).code());
        assertNull(titles.getTitle(TARGET));
        assertEquals(ResultCode.NO_TITLE, actions.clearTitle(ADMIN, true, TARGET).code());
    }

    // =====================================================================
    // 文本校验
    // =====================================================================

    @Test
    @DisplayName("中文 / emoji 按 code point 计：16 个可用，17 个失败")
    void lengthLimit() {
        final String ok16 = "字".repeat(TitleData.MAX_TITLE_LENGTH);
        assertEquals(ResultCode.TITLE_SET, actions.setTitle(ADMIN, true, TARGET, ok16).code());
        assertEquals(ok16, titles.getTitle(TARGET));

        final String bad17 = "字".repeat(TitleData.MAX_TITLE_LENGTH + 1);
        assertEquals(ResultCode.TITLE_INVALID, actions.setTitle(ADMIN, true, TARGET, bad17).code());
        assertEquals(ok16, titles.getTitle(TARGET), "非法输入不应覆盖旧值");
    }

    @Test
    @DisplayName("非法称号：空 / 全空白 / 控制字符 → TITLE_INVALID")
    void invalidTitles() {
        assertEquals(ResultCode.TITLE_INVALID, actions.setTitle(ADMIN, true, TARGET, null).code());
        assertEquals(ResultCode.TITLE_INVALID, actions.setTitle(ADMIN, true, TARGET, "").code());
        assertEquals(ResultCode.TITLE_INVALID, actions.setTitle(ADMIN, true, TARGET, "   ").code());
        assertEquals(ResultCode.TITLE_INVALID, actions.setTitle(ADMIN, true, TARGET, "bad\u0000title").code());
        assertEquals(ResultCode.TITLE_INVALID, actions.setTitle(ADMIN, true, TARGET, "bad\ntitle").code());
        assertNull(titles.getTitle(TARGET));
    }

    @Test
    @DisplayName("称号去首尾空白后存储")
    void trimTitle() {
        assertEquals(ResultCode.TITLE_SET, actions.setTitle(ADMIN, true, TARGET, "  局长  ").code());
        assertEquals("局长", titles.getTitle(TARGET));
    }

    // =====================================================================
    // 目标校验
    // =====================================================================

    @Test
    @DisplayName("目标 UUID 为空 → TARGET_NOT_ONLINE（不区分管理员与否）")
    void emptyTarget() {
        assertEquals(ResultCode.TARGET_NOT_ONLINE, actions.setTitle(ADMIN, true, null, "局长").code());
        assertEquals(ResultCode.TARGET_NOT_ONLINE, actions.setTitle(ADMIN, true, "", "局长").code());
        assertEquals(ResultCode.TARGET_NOT_ONLINE, actions.clearTitle(ADMIN, true, null).code());
        assertEquals(ResultCode.TARGET_NOT_ONLINE, actions.clearTitle(ADMIN, true, "").code());
    }

    @Test
    @DisplayName("权限矩阵：非管理员 + 空目标仍先报 NEED_ADMIN（打开权限优先）")
    void adminCheckedFirst() {
        assertEquals(ResultCode.NEED_ADMIN, actions.setTitle(ADMIN, false, null, "局长").code());
        assertEquals(ResultCode.NEED_ADMIN, actions.clearTitle(ADMIN, false, null).code());
    }

    @Test
    @DisplayName("设置后再设置会覆盖旧称号")
    void overwrite() {
        assertTrue(actions.setTitle(ADMIN, true, TARGET, "甲").ok());
        assertTrue(actions.setTitle(ADMIN, true, TARGET, "乙").ok());
        assertEquals("乙", titles.getTitle(TARGET));
        assertEquals(1, titles.size());
    }
}
