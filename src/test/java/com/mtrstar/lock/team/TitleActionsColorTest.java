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

/** {@link TitleActions} 颜色操作与权限矩阵单元测试（1.2.4）。 */
class TitleActionsColorTest {

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
    // 权限矩阵
    // =====================================================================

    @Test
    @DisplayName("非 OP 3+：设置文本+颜色 / 设色 / 重置色 全部 NEED_ADMIN 且不写数据")
    void nonAdminCannotTouchColor() {
        assertEquals(ResultCode.NEED_ADMIN,
                actions.setTitle(ADMIN, false, TARGET, "局长", "red").code());
        assertEquals(ResultCode.NEED_ADMIN,
                actions.setColor(ADMIN, false, TARGET, "red").code());
        assertEquals(ResultCode.NEED_ADMIN,
                actions.resetColor(ADMIN, false, TARGET).code());
        assertEquals(0, titles.size());
    }

    @Test
    @DisplayName("空目标：先于颜色校验返回 TARGET_NOT_ONLINE")
    void emptyTarget() {
        assertEquals(ResultCode.TARGET_NOT_ONLINE,
                actions.setTitle(ADMIN, true, null, "局长", "red").code());
        assertEquals(ResultCode.TARGET_NOT_ONLINE,
                actions.setColor(ADMIN, true, "", "red").code());
        assertEquals(ResultCode.TARGET_NOT_ONLINE,
                actions.resetColor(ADMIN, true, null).code());
    }

    // =====================================================================
    // 设置文本 + 颜色
    // =====================================================================

    @Test
    @DisplayName("setTitle(text,color)：OP 3+ 成功，颜色规范化")
    void setTitleWithColor() {
        assertEquals(ResultCode.TITLE_SET, actions.setTitle(ADMIN, true, TARGET, "局长", "&a").code());
        assertEquals("局长", titles.getTitle(TARGET));
        assertEquals("#55ff55", titles.getColor(TARGET));
    }

    @Test
    @DisplayName("setTitle(text,color)：颜色非法 → COLOR_INVALID，数据不变")
    void setTitleInvalidColor() {
        actions.setTitle(ADMIN, true, TARGET, "局长", "red");
        assertEquals(ResultCode.COLOR_INVALID,
                actions.setTitle(ADMIN, true, TARGET, "新局长", "notacolor").code());
        assertEquals("局长", titles.getTitle(TARGET));
        assertEquals("#ff5555", titles.getColor(TARGET));
    }

    @Test
    @DisplayName("setTitle(text,color)：reset/none/无 视为清除颜色")
    void setTitleResetTokens() {
        actions.setTitle(ADMIN, true, TARGET, "局长", "gold");
        for (String token : new String[]{"reset", "none", "clear", "off", "无", "-"}) {
            assertEquals(ResultCode.TITLE_SET,
                    actions.setTitle(ADMIN, true, TARGET, "局长", token).code(), token);
            assertNull(titles.getColor(TARGET), token);
        }
    }

    @Test
    @DisplayName("4 参 setTitle 保留颜色")
    void plainSetTitleKeepsColor() {
        actions.setTitle(ADMIN, true, TARGET, "甲", "red");
        assertEquals(ResultCode.TITLE_SET, actions.setTitle(ADMIN, true, TARGET, "乙").code());
        assertEquals("乙", titles.getTitle(TARGET));
        assertEquals("#ff5555", titles.getColor(TARGET));
    }

    // =====================================================================
    // 只设颜色 / 重置
    // =====================================================================

    @Test
    @DisplayName("setColor：目标无称号 → TITLE_REQUIRED")
    void setColorRequiresTitle() {
        assertEquals(ResultCode.TITLE_REQUIRED, actions.setColor(ADMIN, true, TARGET, "red").code());
        assertEquals(0, titles.size());
    }

    @Test
    @DisplayName("setColor：成功 → COLOR_SET；reset/none → COLOR_RESET")
    void setColorAndResetToken() {
        actions.setTitle(ADMIN, true, TARGET, "局长");
        assertEquals(ResultCode.COLOR_SET, actions.setColor(ADMIN, true, TARGET, "aqua").code());
        assertEquals("#55ffff", titles.getColor(TARGET));

        assertEquals(ResultCode.COLOR_RESET, actions.setColor(ADMIN, true, TARGET, "none").code());
        assertNull(titles.getColor(TARGET));
        assertEquals("局长", titles.getTitle(TARGET));
    }

    @Test
    @DisplayName("setColor：非法颜色 → COLOR_INVALID，保留原色")
    void setColorInvalid() {
        actions.setTitle(ADMIN, true, TARGET, "局长", "red");
        assertEquals(ResultCode.COLOR_INVALID, actions.setColor(ADMIN, true, TARGET, "#GGGGGG").code());
        assertEquals("#ff5555", titles.getColor(TARGET));
    }

    @Test
    @DisplayName("resetColor：成功 → COLOR_RESET；无称号 → TITLE_REQUIRED；幂等")
    void resetColor() {
        assertEquals(ResultCode.TITLE_REQUIRED, actions.resetColor(ADMIN, true, TARGET).code());

        actions.setTitle(ADMIN, true, TARGET, "局长", "red");
        assertEquals(ResultCode.COLOR_RESET, actions.resetColor(ADMIN, true, TARGET).code());
        assertNull(titles.getColor(TARGET));
        assertEquals(ResultCode.COLOR_RESET, actions.resetColor(ADMIN, true, TARGET).code());
    }

    @Test
    @DisplayName("clearTitle：文本与颜色一起删除")
    void clearRemovesBoth() {
        actions.setTitle(ADMIN, true, TARGET, "局长", "red");
        assertEquals(ResultCode.TITLE_CLEARED, actions.clearTitle(ADMIN, true, TARGET).code());
        assertNull(titles.getTitle(TARGET));
        assertNull(titles.getColor(TARGET));
        assertFalse(titles.hasTitle(TARGET));
    }

    @Test
    @DisplayName("isSuccess：颜色结果码归类正确")
    void successFlags() {
        assertTrue(ResultCode.COLOR_SET.isSuccess());
        assertTrue(ResultCode.COLOR_RESET.isSuccess());
        assertFalse(ResultCode.COLOR_INVALID.isSuccess());
        assertFalse(ResultCode.TITLE_REQUIRED.isSuccess());
    }
}
