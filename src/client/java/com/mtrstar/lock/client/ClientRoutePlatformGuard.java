package com.mtrstar.lock.client;

import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.PermissionGuard;
import com.mtrstar.lock.refs.RemovedRefsData;
import com.mtrstar.lock.refs.RouteRefReconciler;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端“新增线路站台引用”预判（1.5.2）。
 *
 * <p>与服务端共用 {@link PermissionGuard#findDeniedInUpdateDetailed} 的判定与
 * {@link PermissionGuard#STATION_ADDITION_DENY_KEY} 文案 key；客户端只负责把
 * MinecraftClientData 的当前线路 / 站台快照注入进去。</p>
 *
 * <p>MTR 的 {@code DashboardScreen.onClickAddPlatformToRoute} 会先本地修改线路，
 * 再调用 {@code RegistryClient.sendPacketToServer}。因此不能直接用“修改后的当前线路”
 * 做差集；{@link DashboardScreenMixin} 会在本地修改前调用 {@link #captureBeforeAdd(Route)}
 * 保存旧 platformId 集合，发包拦截时消费该校验快照并在拒绝后回滚本地引用。</p>
 */
public final class ClientRoutePlatformGuard {

    private static final ThreadLocal<Snapshot> SNAPSHOT = new ThreadLocal<>();

    private ClientRoutePlatformGuard() {
    }

    /** 本地修改前的线路引用快照。 */
    public record Snapshot(long routeId, Set<Long> platformIds) {

        public Snapshot {
            platformIds = Set.copyOf(platformIds);
        }
    }

    /** 一次客户端预判结果。 */
    public record CheckResult(List<PermissionGuard.Denial> denials, Snapshot snapshot) {

        public CheckResult {
            denials = List.copyOf(denials);
        }

        public boolean denied() {
            return !denials.isEmpty();
        }

        public boolean stationPermissionDenied() {
            return PermissionGuard.hasStationAdditionDenial(denials);
        }

        public List<String> deniedObjectIds() {
            return PermissionGuard.objectIdsOf(denials);
        }
    }

    /** DashboardScreen 本地修改前调用：保存该线路原有的 platformId 集合。 */
    public static void captureBeforeAdd(Route route) {
        if (route == null) {
            SNAPSHOT.remove();
            return;
        }
        SNAPSHOT.set(new Snapshot(route.getId(), platformIdsOf(route)));
    }

    /** RegistryClientMixin 发包拦截时取出并清掉快照（同一次点击只使用一次）。 */
    public static Snapshot takeSnapshot() {
        final Snapshot snapshot = SNAPSHOT.get();
        SNAPSHOT.remove();
        return snapshot;
    }

    /** 断线 / 异常路径清理，避免残留快照影响下一次编辑。 */
    public static void clearSnapshot() {
        SNAPSHOT.remove();
    }

    /**
     * 客户端预判：数据未就绪（{@code data == null}）或孤儿站台时不检查新增引用，
     * 直接 fail-open 交给服务端兜底。
     */
    public static CheckResult check(String content,
                                    String playerUuid,
                                    Data data,
                                    Snapshot snapshot) {
        final PermissionGuard.EditPermission permission =
                objectId -> ClientOwnership.canEditOrUnknown(objectId, playerUuid);
        final List<PermissionGuard.Denial> denials = PermissionGuard.findDeniedInUpdateDetailed(
                content,
                ClientOwnership::hasCreator,
                ChildParents::get,
                permission,
                data == null ? null : routeId -> currentPlatformIds(data, routeId, snapshot),
                data == null ? null : platformId -> stationObjectId(data, platformId));
        return new CheckResult(denials, snapshot);
    }

    /**
     * 服务端拒绝后恢复本地 {@code Route.routePlatformData}，并清理 MTR 本地
     * {@code Platform.routes / routeColors} 缓存。
     *
     * @return 确实移除了本次新增引用返回 true
     */
    public static boolean rollbackAddedPlatforms(Data data, Snapshot snapshot) {
        if (data == null || snapshot == null) {
            return false;
        }
        final Route route = data.routeIdMap.get(snapshot.routeId());
        if (route == null) {
            return false;
        }

        final List<Long> removedPlatformIds = new ArrayList<>();
        route.getRoutePlatforms().removeIf(routePlatformData -> {
            final Platform platform = routePlatformData == null ? null : routePlatformData.getPlatform();
            if (platform != null && !snapshot.platformIds().contains(platform.getId())) {
                removedPlatformIds.add(platform.getId());
                return true;
            }
            return false;
        });

        for (Long platformId : removedPlatformIds) {
            final Platform platform = data.platformIdMap.get(platformId);
            if (platform == null) {
                continue;
            }
            platform.routes.remove(route);
            // routeColors 是颜色集合，不能简单 remove(route.getColor())：同色线路可能还有别的。
            platform.routeColors.clear();
            for (Route otherRoute : platform.routes) {
                platform.routeColors.add(otherRoute.getColor());
            }
        }
        return !removedPlatformIds.isEmpty();
    }

    /** 当前线路已解析 platformId；没有快照时退化为读取客户端当前线路。 */
    private static Set<Long> currentPlatformIds(Data data, long routeId, Snapshot snapshot) {
        if (snapshot != null && snapshot.routeId() == routeId) {
            return snapshot.platformIds();
        }
        final Route route = data.routeIdMap.get(routeId);
        return route == null ? Set.of() : platformIdsOf(route);
    }

    /** 复用服务端同一套：platformIdMap.get(platformId).area → station objectId。 */
    private static String stationObjectId(Data data, long platformId) {
        final Station station = RouteRefReconciler.parentStation(data, platformId);
        return station == null ? null : RemovedRefsData.stationObjectId(station.getId());
    }

    private static Set<Long> platformIdsOf(Route route) {
        final Set<Long> platformIds = new HashSet<>();
        for (RoutePlatformData routePlatformData : route.getRoutePlatforms()) {
            if (routePlatformData != null && routePlatformData.getPlatform() != null) {
                platformIds.add(routePlatformData.getPlatform().getId());
            }
        }
        return platformIds;
    }
}
