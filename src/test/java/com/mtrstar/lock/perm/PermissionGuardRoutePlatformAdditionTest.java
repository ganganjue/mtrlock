package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.tool.Utilities;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2 第一层“新增线路站台引用”权限补查回归测试。
 *
 * <p>纯 JVM：不依赖 Fabric / Minecraft / OwnershipData。线路当前引用与
 * platformId → station objectId 均由测试桩注入。</p>
 */
class PermissionGuardRoutePlatformAdditionTest {

    private static final long ROUTE_ID = 7592868532610729360L;
    private static final long PLATFORM_A = 1234567890123456789L;
    private static final long PLATFORM_B = -987654321098765432L;

    private static final String ROUTE_KEY = PermissionChecker.PREFIX_ROUTE + ":" + Utilities.numberToPaddedHexString(ROUTE_ID);
    private static final String PLATFORM_A_KEY = ChildParents.PREFIX_PLATFORM + ":" + Utilities.numberToPaddedHexString(PLATFORM_A);
    private static final String PLATFORM_B_KEY = ChildParents.PREFIX_PLATFORM + ":" + Utilities.numberToPaddedHexString(PLATFORM_B);
    private static final String STATION_A_KEY = PermissionChecker.PREFIX_STATION + ":0000000000000001";

    private static String routeUpdateJson(long... platformIds) {
        final StringBuilder platformData = new StringBuilder();
        for (int i = 0; i < platformIds.length; i++) {
            if (i > 0) {
                platformData.append(',');
            }
            platformData.append("{\"platformId\":").append(platformIds[i]).append('}');
        }
        return "{\"routes\":[{\"id\":" + ROUTE_ID
                + ",\"routePlatformData\":[" + platformData + "]}]}";
    }

    private static PermissionGuard.CreatorLookup routeHasOwner() {
        return objectId -> ROUTE_KEY.equals(objectId);
    }

    @Test
    @DisplayName("B 可编辑线路 R 但无 C 站台权限 → 新增引用被第一层拒绝")
    void routeEditorWithoutStationPermissionIsDenied() {
        final Set<String> allowed = Set.of(ROUTE_KEY);

        final var denied = PermissionGuard.findDeniedInUpdate(
                routeUpdateJson(PLATFORM_A),
                routeHasOwner(),
                null,
                allowed::contains,
                routeId -> Collections.emptySet(),
                platformId -> STATION_A_KEY);

        assertEquals(Collections.singletonList(PLATFORM_A_KEY), denied,
                "第一层必须拒绝无站台权限的新增引用，不能让包进入 MTR 后再被对账移除");
    }

    @Test
    @DisplayName("B 同时具备线路与站台权限 → 新增引用放行")
    void routeEditorWithStationPermissionIsAllowed() {
        final Set<String> allowed = Set.of(ROUTE_KEY, STATION_A_KEY);

        final var denied = PermissionGuard.findDeniedInUpdate(
                routeUpdateJson(PLATFORM_A),
                routeHasOwner(),
                null,
                allowed::contains,
                routeId -> Collections.emptySet(),
                platformId -> STATION_A_KEY);

        assertTrue(denied.isEmpty(), "线路 + 站台权限齐全时不应误拦: " + denied);
    }

    @Test
    @DisplayName("OP3+（权限判定恒 true）添加任意站台 → 放行")
    void adminPermissionBypassesStationCheck() {
        final var denied = PermissionGuard.findDeniedInUpdate(
                routeUpdateJson(PLATFORM_A),
                routeHasOwner(),
                null,
                objectId -> true,
                routeId -> Collections.emptySet(),
                platformId -> STATION_A_KEY);

        assertTrue(denied.isEmpty(), "OP3+ 必须走 PermissionChecker 的管理员豁免");
    }

    @Test
    @DisplayName("合法线路修改 + 非法新增引用 → 收集到拒绝项，调用方应整包取消")
    void mixedLegalEditAndIllegalAdditionCollectsDenial() {
        final AtomicInteger stationLookups = new AtomicInteger();
        final Set<String> allowed = Set.of(ROUTE_KEY); // 线路本身可编辑，站台不可编辑

        final var denied = PermissionGuard.findDeniedInUpdateDetailed(
                routeUpdateJson(PLATFORM_A, PLATFORM_B),
                routeHasOwner(),
                null,
                allowed::contains,
                routeId -> Set.of(PLATFORM_A), // A 是已有引用，B 是新增
                platformId -> {
                    stationLookups.incrementAndGet();
                    return STATION_A_KEY;
                });

        assertEquals(1, denied.size(), "整包拒绝只需收集一次，调用方据此发送一次提示");
        assertEquals(PLATFORM_B_KEY, denied.get(0).objectId());
        assertEquals(PermissionGuard.DenialSource.STATION_ADDITION, denied.get(0).source(),
                "混合合法修改 + 非法新增时必须选择站台专用文案");
        assertTrue(PermissionGuard.hasStationAdditionDenial(denied));
        assertEquals(1, stationLookups.get(), "已有引用 A 不应重复检查站台权限");
    }

    @Test
    @DisplayName("删除线路站台引用（请求里不再出现）→ 不触发站台权限补查")
    void removedReferenceDoesNotTriggerStationCheck() {
        final AtomicInteger stationLookups = new AtomicInteger();

        final var denied = PermissionGuard.findDeniedInUpdateDetailed(
                routeUpdateJson(PLATFORM_A), // 只保留 A；B 是本次删除的引用
                routeHasOwner(),
                null,
                objectId -> ROUTE_KEY.equals(objectId),
                routeId -> Set.of(PLATFORM_A, PLATFORM_B),
                platformId -> {
                    stationLookups.incrementAndGet();
                    return STATION_A_KEY;
                });

        assertTrue(denied.isEmpty(), "删除引用不是新增，不应触发站台权限补查: " + denied);
        assertFalse(PermissionGuard.hasStationAdditionDenial(denied),
                "删除分支不得出现站台专用拒绝来源");
        assertEquals(0, stationLookups.get(), "删除分支只检查线路权限");
    }

    @Test
    @DisplayName("孤儿站台（父车站为 null）→ 补查不崩溃，交给对账层原逻辑")
    void orphanPlatformIsFailOpenAndDoesNotCrash() {
        final var denied = PermissionGuard.findDeniedInUpdate(
                routeUpdateJson(PLATFORM_A),
                routeHasOwner(),
                null,
                objectId -> ROUTE_KEY.equals(objectId), // 站台权限本身为 false
                routeId -> Collections.emptySet(),
                platformId -> null); // 模拟 platform.area == null

        assertTrue(denied.isEmpty(), "孤儿站台不应在第一层误拒；由既有对账逻辑处理");
    }

    @Test
    @DisplayName("站台补查拒绝项带 STATION_ADDITION 来源，供调用方选专用文案")
    void stationAdditionDenialCarriesSource() {
        final Set<String> allowed = Set.of(ROUTE_KEY);

        final var denied = PermissionGuard.findDeniedInUpdateDetailed(
                routeUpdateJson(PLATFORM_A),
                routeHasOwner(),
                null,
                allowed::contains,
                routeId -> Collections.emptySet(),
                platformId -> STATION_A_KEY);

        assertEquals(1, denied.size());
        assertEquals(PLATFORM_A_KEY, denied.get(0).objectId());
        assertEquals(PermissionGuard.DenialSource.STATION_ADDITION, denied.get(0).source());
        assertTrue(PermissionGuard.hasStationAdditionDenial(denied));
    }

    @Test
    @DisplayName("线路本身编辑拒绝仍带 EDIT 来源，不被站台专用文案覆盖")
    void plainEditDenialKeepsEditSource() {
        final long routeId = ROUTE_ID;
        final String content = "{\"routes\":[{\"id\":" + routeId + "}]}";

        final var denied = PermissionGuard.findDeniedInUpdateDetailed(
                content,
                routeHasOwner(),
                null,
                objectId -> false,
                routeIdLookup -> Collections.emptySet(),
                platformId -> STATION_A_KEY);

        assertEquals(1, denied.size());
        assertEquals(ROUTE_KEY, denied.get(0).objectId());
        assertEquals(PermissionGuard.DenialSource.EDIT, denied.get(0).source());
        assertFalse(PermissionGuard.hasStationAdditionDenial(denied));
    }

    @Test
    @DisplayName("多线路多新增引用 → 一次收集全部非法项（整包拒绝前不短路）")
    void collectsAllIllegalAdditions() {
        final long secondRouteId = ROUTE_ID + 1;
        final String secondRouteKey = PermissionChecker.PREFIX_ROUTE + ":" + Utilities.numberToPaddedHexString(secondRouteId);
        final String content = "{\"routes\":["
                + "{\"id\":" + ROUTE_ID + ",\"routePlatformData\":[{\"platformId\":" + PLATFORM_A + "}]},"
                + "{\"id\":" + secondRouteId + ",\"routePlatformData\":[{\"platformId\":" + PLATFORM_B + "}]}"
                + "]}";

        final var denied = PermissionGuard.findDeniedInUpdate(
                content,
                objectId -> ROUTE_KEY.equals(objectId) || secondRouteKey.equals(objectId),
                null,
                objectId -> ROUTE_KEY.equals(objectId) || secondRouteKey.equals(objectId),
                routeId -> Collections.emptySet(),
                platformId -> STATION_A_KEY);

        assertEquals(2, denied.size(), "两条线路的非法新增引用都要收集，不能只报第一个: " + denied);
        assertTrue(denied.contains(PLATFORM_A_KEY));
        assertTrue(denied.contains(PLATFORM_B_KEY));
    }
}
