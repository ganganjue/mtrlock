package com.mtrstar.lock.protect;

import com.mtrstar.lock.perm.OwnershipData;
import org.mtr.core.data.Data;
import org.mtr.core.simulation.Simulator;

/**
 * 区域方块保护的空间索引持有者（1.3.0）。
 *
 * <p>把 {@link SpatialIndex} 单例与「从 MTR 数据重建」的生产胶水放在一起，
 * 让 {@link SpatialIndex} / {@link ProtectionRanges} 保持纯数据结构与纯函数，
 * 单元测试完全不碰单例。</p>
 *
 * <p>重建只收有归属记录的车站 / 车厂（{@link OwnershipData#hasCreator(String)}），
 * 所以保护范围与 {@code ownership.json} 始终一致，不引入第二套归属数据。</p>
 */
public final class ProtectionIndex {

    private ProtectionIndex() {
    }

    private static final class InstanceHolder {
        private static final SpatialIndex INSTANCE = new SpatialIndex();
    }

    /** 最近一次见到的服务端 Simulator；只由服务端线程写、读。 */
    private static volatile Data serverData;

    /** 全局唯一索引。 */
    public static SpatialIndex get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 用当前 MTR 数据全量重建索引。
     *
     * <p>调用时机（三处，冗余是刻意的）：</p>
     * <ul>
     *   <li>{@code Data#sync()} RETURN：服务端加载完成（重启后不会保护失效）、
     *       以及 update / delete 内部触发的对账；</li>
     *   <li>{@code UpdateDataRequest#update()} RETURN：新建对象在 update() 内部的
     *       sync() 时还没有归属记录，必须在归属落库后再补一次；</li>
     *   <li>删除钩子：{@code remove(objectId)} 立即失效，不必等下一次 sync。</li>
     * </ul>
     *
     * @param data         MTR 服务端数据；null 时清空索引
     * @param expandBlocks 向外扩张方块数
     */
    public static void rebuildFrom(Data data) {
        final ProtectionConfig config = ProtectionConfig.getInstance();
        rebuildFrom(data, config.getExpandBlocks(), config.isProtectStations(), config.isProtectDepots());
    }

    /**
     * 记住当前服务端数据（{@code Simulator}），供 SERVER_STARTED 之后与
     * {@code /mtrlock protect rebuild} 使用。
     *
     * <p>为什么需要它：mtrlock 在 SERVER_STARTED 里才加载 {@code ownership.json}，
     * 而 MTR 的 {@code Simulator} 构造（内部会 {@code sync()}）可能更早发生。
     * 两个模组的 SERVER_STARTED 回调顺序不保证，所以「重启后保护失效」这个坑必须用
     * 两路兜底：</p>
     * <ol>
     *   <li>{@code Data#sync()} 钩子里记住 Simulator 并重建；</li>
     *   <li>SERVER_STARTED 读完归属数据后再显式重建一次（{@link #rebuildFromRememberedServerData()}）；
     *       若此时 Simulator 还没创建，则第 1 条会在它构造时（归属已加载）补上。</li>
     * </ol>
     *
     * @param data MTR 数据；非 {@link Simulator}（客户端 ClientData）忽略
     */
    public static void rememberServerData(Data data) {
        if (data instanceof Simulator) {
            serverData = data;
        }
    }

    /** 服务端停止时清掉引用（集成服务器会随存档反复启停）。 */
    public static void forgetServerData() {
        serverData = null;
    }

    /** 是否已记住服务端数据（{@code /mtrlock protect rebuild} 判断能否立即重建）。 */
    public static boolean hasRememberedServerData() {
        return serverData != null;
    }

    /**
     * 已记住的服务端 MTR 数据；没有则返回 null。
     *
     * <p>1.4.0 {@code /mtrlock refs restore} 需要按 routeId 找回线路对象，
     * 复用这里已在维护的「最近一次见过的 Simulator」引用，不重复追踪。</p>
     */
    public static Data getRememberedServerData() {
        return serverData;
    }

    /** 已记住的服务端数据里车站 + 车厂数量（{@code /mtrlock protect status} 展示用）。 */
    public static int rememberedObjectCount() {
        final Data data = serverData;
        return data == null ? 0 : data.stations.size() + data.depots.size();
    }

    /** 若有已记住的服务端数据则重建一次；没有则什么都不做（等 sync 钩子）。 */
    public static void rebuildFromRememberedServerData() {
        final Data data = serverData;
        if (data != null) {
            rebuildFrom(data);
        }
    }

    /**
     * 显式参数的重建（测试 / 特殊场景）。
     *
     * @param data            MTR 服务端数据；null 时清空索引
     * @param expandBlocks    向外扩张方块数
     * @param protectStations 是否索引车站
     * @param protectDepots   是否索引车厂
     */
    public static void rebuildFrom(Data data, int expandBlocks, boolean protectStations, boolean protectDepots) {
        get().rebuild(ProtectionRanges.collect(data,
                OwnershipData.getInstance()::hasCreator, expandBlocks, protectStations, protectDepots));
    }
}
