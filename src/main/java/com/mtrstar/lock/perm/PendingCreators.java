package com.mtrstar.lock.perm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认创建者缓存（两段式记录的中转站）。
 *
 * <p>背景：MTR 4.0.0 里“创建线路 / 车站 / 车厂”统一走
 * {@code PacketUpdateData}（C2S）→ {@code UpdateDataRequest.update()}，两条时间线是分开的：</p>
 *
 * <ul>
 *   <li><b>收到包时</b>（{@code PacketRequestResponseBase.runServer}）：能拿到发请求的玩家，
 *       但对象还没创建；包里的 JSON 已经带了新对象的 {@code id}（客户端 {@code new Random().nextLong()} 生成）。</li>
 *   <li><b>真正创建时</b>（{@code UpdateDataRequest.update()}，在 {@code Simulator.tick()} 里被出队处理）：
 *       能通过前后 diff 知道哪些对象是新增的，但这时已经拿不到玩家了。</li>
 * </ul>
 *
 * <p>所以用本类做中转：收到包时 {@link #put(long, String)} 记下 {@code id -> playerUuid}，
 * 创建成功后在 {@code update()} 的 RETURN 用 {@link #poll(long)} 取回并写入 {@link OwnershipData}。</p>
 *
 * <p>线程安全：底层是 {@link ConcurrentHashMap}；服务端 tick 线程与网络线程可能不同，
 * 因此用并发容器 + TTL 过期来避免历史条目无限累积。</p>
 */
public final class PendingCreators {

    /** 默认生存时间：10 分钟。正常情况下包入队后下一个 tick 就会被处理，10 分钟远远足够。 */
    public static final long DEFAULT_TTL_MILLIS = 10L * 60L * 1000L;

    /** id（MTR 对象的 long id）→ 待确认的创建者信息。 */
    private static final Map<Long, Entry> PENDING = new ConcurrentHashMap<>();

    /** 纯静态工具类。 */
    private PendingCreators() {
    }

    /**
     * 记下一个“候选创建者”。
     *
     * @param id   被创建对象的 long id（来自 MTR 对象，等价于 {@code obj.getId()}）
     * @param uuid 创建者 UUID 字符串
     */
    public static void put(long id, String uuid) {
        put(id, uuid, DEFAULT_TTL_MILLIS);
    }

    /**
     * 记下一个“候选创建者”，并指定 TTL（主要方便测试）。
     *
     * @param id        被创建对象的 long id
     * @param uuid      创建者 UUID 字符串
     * @param ttlMillis 存活毫秒数
     */
    public static void put(long id, String uuid, long ttlMillis) {
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        PENDING.put(id, new Entry(uuid, System.currentTimeMillis() + ttlMillis));
    }

    /**
     * 取出并移除指定 id 的创建者。
     *
     * @param id 对象 long id
     * @return UUID 字符串；不存在或已过期返回 {@code null}
     */
    public static String poll(long id) {
        Entry entry = PENDING.remove(id);
        if (entry == null || entry.isExpired()) {
            return null;
        }
        return entry.uuid;
    }

    /** 清理所有已过期的条目（可以在每次 put 时顺手调用，防止长期运行累积）。 */
    public static void pruneExpired() {
        final long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> entry.getValue().expireAt <= now);
    }

    /** 当前挂起条目数量（调试 / 测试用）。 */
    public static int size() {
        return PENDING.size();
    }

    /** 清空（测试用）。 */
    public static void clear() {
        PENDING.clear();
    }

    /** 一条待确认记录：创建者 UUID + 过期时间戳。 */
    public static final class Entry {

        private final String uuid;
        private final long expireAt;

        Entry(String uuid, long expireAt) {
            this.uuid = uuid;
            this.expireAt = expireAt;
        }

        public String getUuid() {
            return uuid;
        }

        public long getExpireAt() {
            return expireAt;
        }

        boolean isExpired() {
            return expireAt <= System.currentTimeMillis();
        }
    }
}
