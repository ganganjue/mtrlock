package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ResultCode} / {@link ResultMessages} 单元测试（1.2.3）。 */
class ResultMessagesTest {

    @Test
    @DisplayName("每个结果码都有非空中文文案")
    void everyCodeHasChinese() {
        for (ResultCode code : ResultCode.values()) {
            final String zh = ResultMessages.zh(code);
            assertNotNull(zh, code.name());
            assertFalse(zh.isEmpty(), code.name());
        }
    }

    @Test
    @DisplayName("lang key 唯一且带前缀")
    void langKeysUnique() {
        final Set<String> keys = new HashSet<>();
        for (ResultCode code : ResultCode.values()) {
            final String key = ResultMessages.langKey(code);
            assertTrue(key.startsWith(ResultMessages.LANG_PREFIX), key);
            assertTrue(keys.add(key), "重复 lang key: " + key);
        }
        assertEquals(ResultCode.values().length, keys.size());
    }

    @Test
    @DisplayName("失败文案与旧版命令逐字一致（抽样）")
    void failureTextMatchesLegacy() {
        assertEquals("申请失败：你已是成员，或已经申请过", ResultMessages.zh(ResultCode.APPLY_FAILED));
        assertEquals("退出失败：你不是该团队成员", ResultMessages.zh(ResultCode.LEAVE_FAILED));
        assertEquals("分享失败：该对象已经分享给这个团队", ResultMessages.zh(ResultCode.ALREADY_SHARED));
        assertEquals("取消失败：该对象没有分享给这个团队", ResultMessages.zh(ResultCode.NOT_SHARED));
        assertEquals("解散失败：需要团队创建者或 OP 3+", ResultMessages.zh(ResultCode.DISBAND_NO_PERMISSION));
    }

    @Test
    @DisplayName("isSuccess 与成功 / 失败分组一致")
    void successFlags() {
        assertTrue(ResultCode.TEAM_CREATED.isSuccess());
        assertTrue(ResultCode.OBJECT_SHARED.isSuccess());
        assertTrue(ResultCode.TITLE_CLEARED.isSuccess());
        assertTrue(ResultCode.SYNCED.isSuccess());
        assertFalse(ResultCode.TEAM_CREATE_FAILED.isSuccess());
        assertFalse(ResultCode.NEED_ADMIN.isSuccess());
        assertFalse(ResultCode.PROTOCOL_MISMATCH.isSuccess());
        assertFalse(ResultCode.RATE_LIMITED.isSuccess());
    }

    @Test
    @DisplayName("ActionResult 工厂 / null code 校验")
    void actionResult() {
        assertTrue(ActionResult.ok(ResultCode.TEAM_CREATED).ok());
        assertFalse(ActionResult.fail(ResultCode.APPLY_FAILED).ok());
        assertEquals(ResultCode.APPLY_FAILED, ActionResult.fail(ResultCode.APPLY_FAILED).code());
        try {
            new ActionResult(true, null);
            throw new AssertionError("应当拒绝 null code");
        } catch (NullPointerException expected) {
            // ok
        }
    }
}
