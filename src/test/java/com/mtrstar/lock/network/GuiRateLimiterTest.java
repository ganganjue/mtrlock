package com.mtrstar.lock.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GuiRateLimiter} 单元测试（1.2.3）：防连点刷包。 */
class GuiRateLimiterTest {

    private static final String PLAYER = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("突发额度内放行，超出后拒绝")
    void burstThenThrottle() {
        final GuiRateLimiter limiter = new GuiRateLimiter();
        long now = 1_000_000L;
        for (int i = 0; i < GuiRateLimiter.BURST; i++) {
            assertTrue(limiter.allow(PLAYER, now), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(limiter.allow(PLAYER, now), "超出突发额度应被拒绝");
        assertFalse(limiter.allow(PLAYER, now + 10L), "间隔不足应继续拒绝");
    }

    @Test
    @DisplayName("按时间回填令牌：一个间隔回 1 个")
    void refill() {
        final GuiRateLimiter limiter = new GuiRateLimiter();
        long now = 5_000L;
        for (int i = 0; i < GuiRateLimiter.BURST; i++) {
            assertTrue(limiter.allow(PLAYER, now));
        }
        assertFalse(limiter.allow(PLAYER, now));
        assertTrue(limiter.allow(PLAYER, now + GuiRateLimiter.MIN_INTERVAL_MS));
        assertFalse(limiter.allow(PLAYER, now + GuiRateLimiter.MIN_INTERVAL_MS));
        assertTrue(limiter.allow(PLAYER, now + GuiRateLimiter.MIN_INTERVAL_MS * 2));
    }

    @Test
    @DisplayName("长时间不操作后回满，但不超过桶容量")
    void cappedAtBurst() {
        final GuiRateLimiter limiter = new GuiRateLimiter();
        long now = 100L;
        for (int i = 0; i < GuiRateLimiter.BURST; i++) {
            assertTrue(limiter.allow(PLAYER, now));
        }
        // 过很久
        now += GuiRateLimiter.MIN_INTERVAL_MS * 1000;
        for (int i = 0; i < GuiRateLimiter.BURST; i++) {
            assertTrue(limiter.allow(PLAYER, now), "回满后应放行 " + (i + 1) + " 次");
        }
        assertFalse(limiter.allow(PLAYER, now), "不应超过桶容量");
    }

    @Test
    @DisplayName("不同玩家互不影响；null / 空 UUID 一律拒绝")
    void perPlayerAndInvalid() {
        final GuiRateLimiter limiter = new GuiRateLimiter();
        long now = 42L;
        assertFalse(limiter.allow(null, now));
        assertFalse(limiter.allow("", now));

        for (int i = 0; i < GuiRateLimiter.BURST; i++) {
            assertTrue(limiter.allow("a", now));
        }
        assertFalse(limiter.allow("a", now));
        assertTrue(limiter.allow("b", now), "另一个玩家不受影响");
        assertEquals(2, limiter.size());
    }

    @Test
    @DisplayName("clear 后重新放行")
    void clear() {
        final GuiRateLimiter limiter = new GuiRateLimiter();
        long now = 7L;
        for (int i = 0; i < GuiRateLimiter.BURST; i++) {
            assertTrue(limiter.allow(PLAYER, now));
        }
        assertFalse(limiter.allow(PLAYER, now));
        limiter.clear(PLAYER);
        assertTrue(limiter.allow(PLAYER, now));
        assertEquals(1, limiter.size());
        limiter.clearAll();
        assertEquals(0, limiter.size());
    }
}
