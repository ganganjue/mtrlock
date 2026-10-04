package com.mtrstar.lock.protect;

import com.mtrstar.lock.perm.PermissionChecker;
import org.mtr.core.data.AreaBase;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Station;
import org.mtr.core.tool.Utilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * 从 MTR 的 {@link Data} 里收集「受保护范围」列表（1.3.0）。
 *
 * <p>只处理有自己矩形的顶层对象：{@link Station} 与 {@link Depot}。
 * 站台（platform）/ 侧线（siding）在数据里没有独立坐标，位置天然被父对象矩形覆盖，
 * 所以不单独生成范围——与「站台、侧线随父对象自动覆盖，不单独处理」的要求一致。
 * 线路（route）没有坐标，不处理。</p>
 *
 * <p>坐标直接取 MTR 的 {@link AreaBase#getMinX()}/{@link AreaBase#getMaxX()}/
 * {@link AreaBase#getMinZ()}/{@link AreaBase#getMaxZ()}：这些方法内部已经是
 * {@code Math.min/max} 归一化结果，且返回 {@code long}。y 维度被刻意忽略
 * （车站 / 车厂的 y 恒为 {@code Long.MIN_VALUE / Long.MAX_VALUE}）。</p>
 *
 * <p>纯函数，不读配置、不碰单例，便于单元测试：归属判定用 {@link Predicate} 注入
 * （生产上是 {@code OwnershipData::hasCreator}，测试里是普通 lambda）。
 * 没有归属记录的对象会被跳过——即「无归属 → 不保护 → fail-open」。</p>
 */
public final class ProtectionRanges {

    private ProtectionRanges() {
    }

    /**
     * 收集全部受保护范围。
     *
     * @param data         MTR 服务端数据（{@code Simulator}）；null 返回空列表
     * @param hasCreator   某 objectId 是否已有归属记录；null 视为无归属（返回空列表）
     * @param expandBlocks 向外扩张方块数（配置 {@code expandBlocks}）；&lt;= 0 不扩张
     * @return 不可变范围列表（可能为空）
     */
    public static List<ObjectRange> collect(Data data, Predicate<String> hasCreator, int expandBlocks) {
        if (data == null || hasCreator == null) {
            return Collections.emptyList();
        }
        final List<ObjectRange> ranges = new ArrayList<>();
        for (Station station : data.stations) {
            addRange(ranges, PermissionChecker.PREFIX_STATION, station, hasCreator, expandBlocks);
        }
        for (Depot depot : data.depots) {
            addRange(ranges, PermissionChecker.PREFIX_DEPOT, depot, hasCreator, expandBlocks);
        }
        return Collections.unmodifiableList(ranges);
    }

    /** 单个对象：拼 objectId → 过滤归属 → 取矩形 → 扩张。 */
    private static void addRange(List<ObjectRange> out, String prefix, AreaBase<?, ?> area,
                                 Predicate<String> hasCreator, int expandBlocks) {
        if (area == null) {
            return;
        }
        final String objectId = objectIdOf(prefix, area.getId());
        if (!hasCreator.test(objectId)) {
            return; // 无归属记录（模组安装前创建 / 网页创建）→ 不保护
        }
        final ObjectRange range = ObjectRange.ofBounds(objectId,
                area.getMinX(), area.getMaxX(), area.getMinZ(), area.getMaxZ());
        if (range != null) {
            out.add(range.expanded(expandBlocks));
        }
    }

    /**
     * MTR 的 signed long id → mtrlock 的 objectId（{@code <prefix>:<16 位大写 HEX>}）。
     *
     * <p>与 MTR 的 {@code NameColorDataBase.getHexId()} 完全一致：
     * {@code Utilities.numberToPaddedHexString(id)}。不能 lowercase、不能截断。</p>
     */
    public static String objectIdOf(String prefix, long id) {
        return prefix + ":" + Utilities.numberToPaddedHexString(id);
    }
}
