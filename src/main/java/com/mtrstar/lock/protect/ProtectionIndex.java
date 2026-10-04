package com.mtrstar.lock.protect;

import com.mtrstar.lock.perm.OwnershipData;
import org.mtr.core.data.Data;

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
    public static void rebuildFrom(Data data, int expandBlocks) {
        get().rebuild(ProtectionRanges.collect(data,
                OwnershipData.getInstance()::hasCreator, expandBlocks));
    }
}
