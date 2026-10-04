package com.mtrstar.lock.refs;

import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.tool.Utilities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 线路引用对账器（1.4.0，纯逻辑 / 可单测）。
 *
 * <p>挂载点只有一处：{@code Data#sync()} 的 RETURN（见 {@code DataChildParentMixin}）。
 * 每次 sync 后做一次全量对账——不枚举失权路径，因此也不需要事件总线：</p>
 *
 * <ol>
 *   <li><b>移除</b>：线路 owner 对某站台所属车站失去编辑权限（或车站已被删除）→
 *       把该站台从线路的 {@code routePlatformData} 里临时移除，并在
 *       {@link RemovedRefsData} 里记一笔；</li>
 *   <li><b>加回</b>：权限恢复 → 用 {@code new RoutePlatformData(platformId)} 加回并删记录；</li>
 *   <li><b>幽灵清理</b>：记录对应的线路 / 站台已经不存在（或站台已换父车站）→ 删记录，
 *       避免账本长期堆积无意义条目。</li>
 * </ol>
 *
 * <p><b>owner 唯一来源</b>是 {@code ownership.json} 的创建者 UUID（{@link OwnerLookup}）：
 * MTR 4.0.0 / 4.0.5 的 {@code Route} 没有 owner 字段。没有 owner 记录 → fail-open 整条跳过。</p>
 *
 * <p><b>权限判定</b>用 UUID 版本（{@link PermissionLookup}），{@code isAdmin} 固定 false：
 * OP 等级是在线玩家的实时属性，owner 是离线 UUID，推不出来；这与「OP 不进快照」一致。</p>
 *
 * <p><b>平台 → 车站反查</b>用运行时 {@code data.platformIdMap.get(platformId).area}
 * （{@code Platform.area} 由 MTR 在 sync 内部挂好，RETURN 时非本轮对账前的旧值），
 * 不依赖 {@code ChildParents}，也不依赖同一注入点上其它注入器的相对顺序。
 * {@code platform == null}（站台已删）或 {@code area == null}（孤儿站台 / 几何不匹配）
 * 在当前引用一侧一律 fail-open 放行。</p>
 *
 * <p><b>已知取舍</b>：加回的站台会追加到线路末尾，不还原原始站序；移除 / 加回都会让线路变脏，
 * MTR 会在 autosave 时写进存档——{@link RemovedRefsData} 就是这些引用的唯一恢复源。</p>
 */
public final class RouteRefReconciler {

    private RouteRefReconciler() {
    }

    /** routeId → 创建者 UUID（生产实现走 {@code OwnershipData::getCreator}）。 */
    @FunctionalInterface
    public interface OwnerLookup {

        /** @return 创建者 UUID；无归属记录返回 null */
        String getCreator(String routeId);
    }

    /**
     * 「owner 能否编辑 stationObjectId」的判定（生产实现走 {@code PermissionChecker} 的
     * UUID 重载，{@code isAdmin=false}）。
     */
    @FunctionalInterface
    public interface PermissionLookup {

        /** @return owner 有编辑权限返回 true */
        boolean canEdit(String ownerUuid, String stationObjectId);
    }

    /** 一条被处理的引用（routeId + platformId）。 */
    public record Ref(String routeId, long platformId) {
    }

    /** 一次对账的结果。 */
    public record Result(List<Ref> removed, List<Ref> restored, List<Ref> purged) {

        public Result(List<Ref> removed, List<Ref> restored, List<Ref> purged) {
            this.removed = List.copyOf(removed);
            this.restored = List.copyOf(restored);
            this.purged = List.copyOf(purged);
        }

        /** 本次对账是否有任何改动。 */
        public boolean changed() {
            return !removed.isEmpty() || !restored.isEmpty() || !purged.isEmpty();
        }
    }

    /**
     * 全量对账。
     *
     * <p>{@code removedRefs.isLoadFailed()} 为 true 时<b>立即返回空结果</b>：
     * 账本损坏时不能移除任何引用，否则那些移除将失去恢复源。</p>
     *
     * @param data        服务端 MTR 数据（sync RETURN 时刻）
     * @param owners      routeId → 创建者 UUID
     * @param removedRefs 引用账本
     * @param permissions owner → 车站 的编辑权限判定
     * @return 本次移除 / 加回 / 清理的统计
     */
    public static Result reconcile(Data data, OwnerLookup owners, RemovedRefsData removedRefs,
                                   PermissionLookup permissions) {
        final List<Ref> removed = new ArrayList<>();
        final List<Ref> restored = new ArrayList<>();
        final List<Ref> purged = new ArrayList<>();
        // 第一遍刚移除的引用：第二遍绝不能在同一次对账里又加回来
        final Set<Ref> removedThisPass = new LinkedHashSet<>();

        // 兜底 1：账本坏文件 → 本轮完全跳过，不做任何移除
        if (data == null || owners == null || removedRefs == null || permissions == null
                || removedRefs.isLoadFailed()) {
            return new Result(removed, restored, purged);
        }

        // ---- 第一遍：线路当前引用里，还有哪些站台的权限已经失效 ----
        final Set<Ref> currentRefs = new LinkedHashSet<>();
        for (Route route : data.routes) {
            if (route == null) {
                continue;
            }
            final String routeId = routeObjectId(route);
            if (routeId == null) {
                continue;
            }

            // 无 owner → fail-open 跳过整条线路
            final String owner = owners.getCreator(routeId);
            if (owner == null || owner.isEmpty()) {
                continue;
            }

            for (RoutePlatformData rpd : List.copyOf(route.getRoutePlatforms())) {
                if (rpd == null) {
                    continue;
                }
                final Platform platform = rpd.getPlatform();
                // sync RETURN 时 platform 必非 null；为 null 说明站台本轮已被 MTR 剪掉。
                if (platform == null) {
                    continue;
                }
                final long platformId = platform.getId();
                final Ref ref = new Ref(routeId, platformId);
                currentRefs.add(ref);

                final Station station = parentStation(data, platformId);
                if (station == null) {
                    // 孤儿站台 / 无归属车站 / 车站已删（area == null）
                    // → 当前引用一侧 fail-open，不移除；已被移除过的条目交给第二遍处理。
                    continue;
                }
                final String stationObjectId = RemovedRefsData.stationObjectId(station.getId());
                if (permissions.canEdit(owner, stationObjectId)) {
                    continue;
                }

                // 失去权限 → 临时移除 + 记录
                if (removePlatform(route, platformId) && removedRefs.recordRemoved(routeId, platformId, stationObjectId)) {
                    removed.add(ref);
                    removedThisPass.add(ref);
                }
            }
        }

        // ---- 第二遍：账本里的引用，权限恢复就加回，线路/站台不存在就清记录 ----
        for (Map.Entry<String, List<RemovedRefsData.RemovedRefEntry>> entry : removedRefs.getAll().entrySet()) {
            final String routeId = entry.getKey();
            final Route route = routeById(data, routeId);

            for (RemovedRefsData.RemovedRefEntry refEntry : entry.getValue()) {
                final long platformId = refEntry.platformId;
                final Ref ref = new Ref(routeId, platformId);

                // 线路已删 → 幽灵记录
                if (route == null) {
                    if (removedRefs.removeRemoved(routeId, platformId)) {
                        purged.add(ref);
                    }
                    continue;
                }

                final Platform platform = data.platformIdMap.get(platformId);
                if (platform == null) {
                    // 站台已删 → 记录永远无法加回，清掉
                    if (removedRefs.removeRemoved(routeId, platformId)) {
                        purged.add(ref);
                    }
                    continue;
                }

                final Station station = parentStation(data, platformId);
                if (station == null) {
                    // 父车站已删（孤儿站台）→ 记录里那一笔仍然有效（站台可能随车站恢复），
                    // 保持移除状态，交给 30 天清理或手动 restore。
                    continue;
                }
                if (!RemovedRefsData.stationObjectId(station.getId()).equals(refEntry.stationObjectId)) {
                    // 站台已换父车站 → 旧记录失效（永远无法按旧车站判定权限），清掉。
                    // 若当前引用还在，第一遍已经按新父车站重新判定过（移除了会重新记一笔新记录）。
                    if (removedRefs.removeRemoved(routeId, platformId)) {
                        purged.add(ref);
                    }
                    continue;
                }

                final String owner = owners.getCreator(routeId);
                if (owner == null || owner.isEmpty()) {
                    // 没有 owner 就无法判定权限 → fail-open，什么都不做
                    continue;
                }
                // 本轮刚移除的引用不要再加回来（第一遍与第二遍之间权限不可能变）
                if (removedThisPass.contains(ref)) {
                    continue;
                }
                // 权限仍未恢复 → 保持移除状态
                if (!permissions.canEdit(owner, refEntry.stationObjectId)) {
                    continue;
                }

                // 权限恢复 → 加回（幂等：已经在引用里就只删记录）
                if (!addPlatform(route, platformId)) {
                    if (removedRefs.removeRemoved(routeId, platformId)) {
                        restored.add(ref);
                    }
                    continue;
                }
                if (removedRefs.removeRemoved(routeId, platformId)) {
                    restored.add(ref);
                }
            }
        }

        // 节流落盘：变更后 5 秒 debounce 或累计 10 次对账先到先落
        removedRefs.afterReconcile();
        return new Result(removed, restored, purged);
    }

    /** 站台 → 父车站；孤儿站台 / 车站已删时返回 null。 */
    private static Station parentStation(Data data, long platformId) {
        final Platform platform = data.platformIdMap.get(platformId);
        if (platform == null) {
            return null;
        }
        // Platform.area 是 public U area（U = Station），sync 内部 mapAreasAndSavedRails 挂好
        return platform.area;
    }

    /** 原地移除线路对某站台的引用；确实移除了才返回 true。 */
    private static boolean removePlatform(Route route, long platformId) {
        return route.getRoutePlatforms().removeIf(rpd -> rpd != null
                && rpd.getPlatform() != null
                && rpd.getPlatform().getId() == platformId);
    }

    /** 原地加回线路对某站台的引用；原本没有（真的加了）才返回 true。 */
    private static boolean addPlatform(Route route, long platformId) {
        for (RoutePlatformData rpd : route.getRoutePlatforms()) {
            if (rpd != null && rpd.getPlatform() != null && rpd.getPlatform().getId() == platformId) {
                return false;
            }
        }
        route.getRoutePlatforms().add(new RoutePlatformData(platformId));
        return true;
    }

    /** MTR Route → {@code route:<HEX>}（与 ownership.json 的键一致）。 */
    static String routeObjectId(Route route) {
        final String hexId = Utilities.numberToPaddedHexString(route.getId());
        if (hexId == null || hexId.isEmpty()) {
            return null;
        }
        return "route:" + hexId;
    }

    /** routeId → Route（线性查找；sync 后线路数量有限，够用且无额外索引维护）。 */
    static Route routeById(Data data, String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return null;
        }
        for (Route route : data.routes) {
            if (route != null && routeId.equals(routeObjectId(route))) {
                return route;
            }
        }
        return null;
    }
}
