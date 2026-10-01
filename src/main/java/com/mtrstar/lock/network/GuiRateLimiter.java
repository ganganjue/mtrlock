package com.mtrstar.lock.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI 操作限流（1.2.3，服务端）。
 *
 * <p>令牌桶：容量 {@link #BURST}，每 {@link #MIN_INTERVAL_MS} 毫秒回 1 个令牌。
 * 正常点按不会被限；连点 / 刷包的客户端会拿到 {@code RATE_LIMITED} 而不是把包灌进数据层。</p>
 *
 * <p>纯逻辑：只依赖毫秒时间戳，可纯 JVM 单测。</p>
 */
public final class GuiRateLimiter {

    /** 回一个令牌所需的最小间隔（毫秒）。 */
    public static final long MIN_INTERVAL_MS = 120L;

    /** 桶容量（允许的突发连点次数）。 */
    public static final int BURST = 5;

    private static final class Bucket {

        private double tokens;
        private long lastRefillMillis;

        Bucket(long nowMillis) {
            this.tokens = BURST;
            this.lastRefillMillis = nowMillis;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 是否放行一次操作。
     *
     * @param playerUuid 玩家 UUID；null / 空 → false（无法归属的请求一律拒绝）
     * @param nowMillis  当前毫秒时间戳
     * @return 放行返回 true，并扣掉 1 个令牌
     */
    public boolean allow(String playerUuid, long nowMillis) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final Bucket bucket = buckets.computeIfAbsent(playerUuid, key -> new Bucket(nowMillis));
        synchronized (bucket) {
            refill(bucket, nowMillis);
            if (bucket.tokens >= 1.0D) {
                bucket.tokens -= 1.0D;
                return true;
            }
            return false;
        }
    }

    /** 清掉某玩家的桶（退服时调用）。 */
    public void clear(String playerUuid) {
        if (playerUuid != null) {
            buckets.remove(playerUuid);
        }
    }

    /** 清空全部（测试 / 服务器停止）。 */
    public void clearAll() {
        buckets.clear();
    }

    /** 当前跟踪的玩家数（测试用）。 */
    public int size() {
        return buckets.size();
    }

    private static void refill(Bucket bucket, long nowMillis) {
        long elapsed = nowMillis - bucket.lastRefillMillis;
        if (elapsed <= 0L) {
            bucket.lastRefillMillis = nowMillis;
            return;
        }
        if (elapsed >= MIN_INTERVAL_MS) {
            final double gained = (double) elapsed / (double) MIN_INTERVAL_MS;
            bucket.tokens = Math.min((double) BURST, bucket.tokens + gained);
            // 保留不足一个间隔的余量，避免每次调用都重置进度
            bucket.lastRefillMillis = nowMillis - (elapsed % MIN_INTERVAL_MS);
        }
    }
}
