package com.mtrstar.lock.protect;

import com.mtrstar.lock.Mtrlock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 chunk 索引的空间索引（1.3.0 区域方块保护）。
 *
 * <p>数据结构：{@code Map<chunkKey, List<ObjectRange>>}，写入时把一个范围按 chunk 铺开，
 * 查询时只遍历该 chunk 内的对象，避免每次破坏 / 放置都全量扫描所有车站 / 车厂。</p>
 *
 * <p><b>为什么不直接用 Minecraft 的 {@code ChunkPos}</b>：本类要能在纯 JVM 单元测试里跑
 * （项目所有单测都不加载 Minecraft）。chunk 坐标打包成 {@code long} 键：
 * {@code ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL)}。注意 {@code chunkX} 是
 * {@code int}，<b>必须先转 {@code long} 再左移</b>，否则负数会因符号扩展吃掉高位、
 * 与其它 chunk 发生键碰撞（见 {@link #chunkKey(int, int)}）。</p>
 *
 * <p><b>幂等</b>：同一个 objectId 反复写入（改名 / 改色 / 改范围）只保留最后一次的范围。
 * {@link #put(ObjectRange)} 与 {@link #rebuild(Collection)} 都会从权威快照
 * （{@code byObject}）重新生成整张 chunk 表，因此不会累加旧范围。</p>
 *
 * <p><b>并发</b>：查询侧（方块事件）读 {@code volatile} 的 chunk 快照，无锁；
 * 写入侧（服务端 tick 线程的 update / delete / sync）用 {@code lock} 串行化，
 * 并且每次都是「构建新 map → 原子替换」，不存在 clear 之后、填回之前的空窗期。</p>
 */
public final class SpatialIndex {

    /** 1.20.1 的 chunk 边长。 */
    public static final int CHUNK_SIZE = 16;

    /**
     * 单个范围允许铺开的最大 chunk 数（安全阀）。
     *
     * <p>1&lt;&lt;20 个 chunk ≈ 16384×16384 方块，远超 MTR 车站 / 车厂的合法尺寸；
     * 一旦超过说明数据异常（例如损坏的存档坐标），此时跳过并告警，
     * 而不是把内存撑爆。</p>
     */
    static final int MAX_CHUNKS_PER_RANGE = 1 << 20;

    /** 保护写入侧（服务端线程）互斥；查询侧完全不碰它。 */
    private final Object lock = new Object();

    /** 权威快照：objectId -> 当前范围。只在持有 {@link #lock} 时改写。 */
    private final Map<String, ObjectRange> byObject = new HashMap<>();

    /** 查询用的 chunk 表；每次写入后整体替换，读侧无锁。 */
    private volatile Map<Long, List<ObjectRange>> chunkIndex = Collections.emptyMap();

    /** 方块坐标 → chunk 坐标（对负数向负无穷取整，不会向 0 截断）。 */
    public static int chunkOf(long blockCoord) {
        return (int) Math.floorDiv(blockCoord, (long) CHUNK_SIZE);
    }

    /**
     * 把 chunk 坐标打包成 {@code long} 键。
     *
     * <p>{@code chunkX} 为负时，<b>必须先把它转成 {@code long} 再左移 32 位</b>：
     * {@code ((long) chunkX << 32)}。若写成 {@code chunkX << 32}（int 运算），
     * Java 会先用 int 做移位再提升为 long，符号位被复制到高位，导致
     * {@code (-1, -1)} 与 {@code (-1, 0)} 等键碰撞。</p>
     */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** 方块坐标 (x,z) 对应的 chunk 键。 */
    public static long chunkKeyOfBlock(long x, long z) {
        return chunkKey(chunkOf(x), chunkOf(z));
    }

    // =====================================================================
    // 写入侧
    // =====================================================================

    /**
     * 写入 / 覆盖一个范围（幂等）。
     *
     * @param range 范围；null 忽略
     */
    public void put(ObjectRange range) {
        if (range == null) {
            return;
        }
        synchronized (lock) {
            byObject.put(range.objectId(), range);
            publishLocked();
        }
    }

    /**
     * 移除一个对象的范围（删除车站 / 车厂时用，只需要 objectId，不需要坐标）。
     *
     * @param objectId 对象 id；null / 不存在时无副作用
     */
    public void remove(String objectId) {
        if (objectId == null) {
            return;
        }
        synchronized (lock) {
            if (byObject.remove(objectId) != null) {
                publishLocked();
            }
        }
    }

    /**
     * 用一份权威快照整体重建索引（服务器加载完成 / 数据变更后对账）。
     *
     * <p>先构建新表再原子替换：查询侧要么看到旧表、要么看到新表，
     * 不会看到「已清空但还没填回」的中间状态。</p>
     *
     * @param ranges 全部受保护范围；null 视为空
     */
    public void rebuild(Collection<ObjectRange> ranges) {
        synchronized (lock) {
            byObject.clear();
            if (ranges != null) {
                for (ObjectRange range : ranges) {
                    if (range != null) {
                        byObject.put(range.objectId(), range);
                    }
                }
            }
            publishLocked();
        }
    }

    /** 清空索引（测试 / 服务端停止时用）。 */
    public void clear() {
        synchronized (lock) {
            byObject.clear();
            publishLocked();
        }
    }

    /** 由 {@code byObject} 重新生成 chunk 表并原子发布。必须在 {@link #lock} 内调用。 */
    private void publishLocked() {
        final Map<Long, List<ObjectRange>> next = new HashMap<>();
        for (ObjectRange range : byObject.values()) {
            addToChunks(next, range);
        }
        chunkIndex = next;
    }

    /** 把一个范围铺到它覆盖的每个 chunk 上；超出安全阀时跳过并告警。 */
    private static void addToChunks(Map<Long, List<ObjectRange>> map, ObjectRange range) {
        final long minChunkX = chunkOf(range.minX());
        final long maxChunkX = chunkOf(range.maxX());
        final long minChunkZ = chunkOf(range.minZ());
        final long maxChunkZ = chunkOf(range.maxZ());

        final long spanX = maxChunkX - minChunkX + 1L;
        final long spanZ = maxChunkZ - minChunkZ + 1L;
        // 先各自卡上限再相乘，避免 spanX * spanZ 溢出 long。
        if (spanX <= 0L || spanZ <= 0L
                || spanX > MAX_CHUNKS_PER_RANGE || spanZ > MAX_CHUNKS_PER_RANGE
                || spanX * spanZ > MAX_CHUNKS_PER_RANGE) {
            Mtrlock.LOGGER.warn("[mtrlock] 保护范围过大，跳过空间索引: {} ({}x{} chunks)",
                    range, spanX, spanZ);
            return;
        }

        // 用 long 计数器：即使 chunk 坐标贴近 Integer 边界也不会因 int 自增回绕而死循环。
        for (long cx = minChunkX; cx <= maxChunkX; cx++) {
            for (long cz = minChunkZ; cz <= maxChunkZ; cz++) {
                final long key = (cx << 32) | (cz & 0xFFFFFFFFL);
                map.computeIfAbsent(key, k -> new ArrayList<>(2)).add(range);
            }
        }
    }

    // =====================================================================
    // 查询侧
    // =====================================================================

    /**
     * 查询覆盖方块 (x,z) 的所有范围。
     *
     * <p>返回不可变列表；没有命中返回空列表（不返回 null）。重叠的车站 / 车厂会同时返回，
     * 权限判定侧再决定「任一拒绝即拒绝」。</p>
     */
    public List<ObjectRange> lookup(long x, long z) {
        final List<ObjectRange> candidates = chunkIndex.get(chunkKeyOfBlock(x, z));
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<ObjectRange> hits = null;
        for (ObjectRange range : candidates) {
            if (range.contains(x, z)) {
                if (hits == null) {
                    hits = new ArrayList<>(2);
                }
                hits.add(range);
            }
        }
        return hits == null ? Collections.emptyList() : Collections.unmodifiableList(hits);
    }

    /** 索引里的对象数量（测试 / {@code /mtrlock protect status} 用）。 */
    public int size() {
        return byObject.size();
    }

    /** 索引覆盖的 chunk 数量（测试 / 调试用）。 */
    public int chunkCount() {
        return chunkIndex.size();
    }

    public boolean isEmpty() {
        return byObject.isEmpty();
    }
}
