package com.mtrstar.lock.client;

import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.perm.PermissionGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Utilities;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2 客户端新增线路站台引用预判回归测试。
 *
 * <p>用真实 MTR {@link ClientData} 构造线路 / 站台 / 车站夹具；ClientOwnership
 * 使用纯静态缓存桩。测试直接调用生产使用的 {@link ClientRoutePlatformGuard}，
 * 因此覆盖“预判 → 拒绝 → 本地回滚”的同一条路径。</p>
 */
class ClientRoutePlatformGuardTest {

    private static final String OWNER_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OWNER_C = "cccccccc-0000-0000-0000-000000000003";
    private static final String OPERATOR_B = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String TEAM_T = "team-T";

    private ClientData data;
    private Station stationA;
    private Station stationC;
    private Platform platformA;
    private Platform platformC;
    private Route route;
    private String routeKey;
    private String stationCKey;

    @BeforeEach
    void setUp() {
        data = new ClientData();
        stationA = addStation(0, 0, 100, 100);
        stationC = addStation(200, 200, 300, 300);
        platformA = addPlatform(10, 10);
        platformC = addPlatform(210, 210);

        route = new Route(TransportMode.TRAIN, data);
        data.routes.add(route);
        route.getRoutePlatforms().add(new RoutePlatformData(platformA.getId()));
        data.sync();

        routeKey = PermissionChecker.PREFIX_ROUTE + ":" + Utilities.numberToPaddedHexString(route.getId());
        stationCKey = PermissionChecker.PREFIX_STATION + ":" + Utilities.numberToPaddedHexString(stationC.getId());
        platformC = data.platformIdMap.get(platformC.getId());
        assertNotNull(platformC);
    }

    @AfterEach
    void tearDown() {
        ClientOwnership.clear();
        ClientRoutePlatformGuard.clearSnapshot();
    }

    private Station addStation(long x1, long z1, long x2, long z2) {
        final Station station = new Station(data);
        station.setCorners(new Position(x1, Long.MIN_VALUE, z1), new Position(x2, Long.MAX_VALUE, z2));
        data.stations.add(station);
        return station;
    }

    private Platform addPlatform(long x, long z) {
        final Platform platform = new Platform(
                new Position(x, 64, z), new Position(x + 5, 64, z), TransportMode.TRAIN, data);
        data.platforms.add(platform);
        return platform;
    }

    private static String routeUpdateJson(long routeId, long... platformIds) {
        final StringBuilder refs = new StringBuilder();
        for (int i = 0; i < platformIds.length; i++) {
            if (i > 0) {
                refs.append(',');
            }
            refs.append("{\"platformId\":").append(platformIds[i]).append('}');
        }
        return "{\"routes\":[{\"id\":" + routeId + ",\"routePlatformData\":[" + refs + "]}]}";
    }

    private void setupRouteSharedToOperatorStationNotShared() {
        ClientOwnership.setOperator(false);
        ClientOwnership.setAll(Map.of(routeKey, OWNER_A, stationCKey, OWNER_C));
        ClientOwnership.setShareSnapshot(
                Map.of(routeKey, Set.of(TEAM_T)),
                Map.of(TEAM_T, Set.of(OPERATOR_B)));
    }

    private static Set<Long> referencedPlatformIds(Route route) {
        final Set<Long> ids = new HashSet<>();
        for (RoutePlatformData routePlatformData : route.getRoutePlatforms()) {
            if (routePlatformData.getPlatform() != null) {
                ids.add(routePlatformData.getPlatform().getId());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("B 无站台权限 → 客户端预判拒绝并回滚 MTR 本地乐观新增")
    void noStationPermissionDeniedAndRollbackRestoresRoute() {
        setupRouteSharedToOperatorStationNotShared();
        ClientRoutePlatformGuard.captureBeforeAdd(route);
        final ClientRoutePlatformGuard.Snapshot snapshot = ClientRoutePlatformGuard.takeSnapshot();

        // 模拟 MTR DashboardScreen.onClickAddPlatformToRoute：先本地 add + writePlatformCache。
        final RoutePlatformData added = new RoutePlatformData(platformC.getId());
        route.getRoutePlatforms().add(added);
        added.writePlatformCache(route, data.platformIdMap);
        assertTrue(referencedPlatformIds(route).contains(platformC.getId()));

        final ClientRoutePlatformGuard.CheckResult result = ClientRoutePlatformGuard.check(
                routeUpdateJson(route.getId(), platformA.getId(), platformC.getId()),
                OPERATOR_B,
                data,
                snapshot);

        assertTrue(result.denied(), "客户端预判必须拒绝");
        assertTrue(result.stationPermissionDenied(), "拒绝来源应为站台权限补查");
        assertFalse(result.deniedObjectIds().isEmpty());
        assertTrue(ClientRoutePlatformGuard.rollbackAddedPlatforms(data, snapshot));

        assertEquals(Set.of(platformA.getId()), referencedPlatformIds(route),
                "回滚后 Route.routePlatformData 应恢复为本地修改前");
        assertFalse(platformC.routes.contains(route), "回滚后 Platform.routes 不应残留该线路");
    }

    @Test
    @DisplayName("B 同时具备线路与站台权限 → 客户端放行")
    void stationPermissionAllowsSend() {
        ClientOwnership.setOperator(false);
        ClientOwnership.setAll(Map.of(routeKey, OWNER_A, stationCKey, OWNER_C));
        ClientOwnership.setShareSnapshot(
                Map.of(routeKey, Set.of(TEAM_T), stationCKey, Set.of(TEAM_T)),
                Map.of(TEAM_T, Set.of(OPERATOR_B)));

        final ClientRoutePlatformGuard.CheckResult result = ClientRoutePlatformGuard.check(
                routeUpdateJson(route.getId(), platformA.getId(), platformC.getId()),
                OPERATOR_B,
                data,
                null);

        assertFalse(result.denied(), "线路 + 站台权限齐全时客户端不应拦截: " + result.denials());
    }

    @Test
    @DisplayName("MinecraftClientData 未就绪（data == null）→ fail-open 发包")
    void nullClientDataFailsOpen() {
        setupRouteSharedToOperatorStationNotShared();

        final ClientRoutePlatformGuard.CheckResult result = ClientRoutePlatformGuard.check(
                routeUpdateJson(route.getId(), platformA.getId(), platformC.getId()),
                OPERATOR_B,
                null,
                null);

        assertFalse(result.denied(), "客户端数据未就绪时必须交给服务端兜底");
    }

    @Test
    @DisplayName("孤儿站台（platform.area == null）→ fail-open 发包")
    void orphanPlatformFailsOpen() {
        setupRouteSharedToOperatorStationNotShared();
        final Platform orphan = new Platform(
                new Position(1000, 64, 1000), new Position(1005, 64, 1000), TransportMode.TRAIN, data);
        data.platforms.add(orphan);
        data.sync();
        assertEquals(null, orphan.area, "夹具应为孤儿站台");

        final ClientRoutePlatformGuard.CheckResult result = ClientRoutePlatformGuard.check(
                routeUpdateJson(route.getId(), platformA.getId(), orphan.getId()),
                OPERATOR_B,
                data,
                null);

        assertFalse(result.denied(), "孤儿站台应由服务端补查 / 对账层处理，客户端 fail-open");
    }

    @Test
    @DisplayName("OP 3+ 客户端预判 → 放行")
    void operatorAllowed() {
        setupRouteSharedToOperatorStationNotShared();
        ClientOwnership.setOperator(true);

        final ClientRoutePlatformGuard.CheckResult result = ClientRoutePlatformGuard.check(
                routeUpdateJson(route.getId(), platformA.getId(), platformC.getId()),
                OPERATOR_B,
                data,
                null);

        assertFalse(result.denied(), "OP3+ 必须由 ClientOwnership.canEditOrUnknown 直接放行");
    }
}
