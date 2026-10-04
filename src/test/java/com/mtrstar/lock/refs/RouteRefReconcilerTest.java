package com.mtrstar.lock.refs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Utilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RouteRefReconciler} 单元测试（1.4.0）。
 *
 * <p>用真实的 MTR {@link ClientData} / {@link Route} / {@link Platform} / {@link Station}
 * （每个夹具都跑一次真正的 {@code Data#sync()}，因此 {@code platformIdMap} 与
 * {@code Platform.area} 是 MTR 自己挂好的，不是手工造的），owner 与权限用内存桩注入，
 * 完全纯 JVM。</p>
 */
class RouteRefReconcilerTest {

    private static final String OWNER_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OWNER_B = "bbbbbbbb-0000-0000-0000-000000000002";

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    /** 夹具数据。 */
    private Data data;
    private Station stationA;
    private Station stationB;
    private Platform platform1;
    private Platform platform2;
    private Route route;

    private String routeId;
    private String stationAId;
    private String stationBId;

    /** routeId → owner UUID（模拟 ownership.json）。 */
    private final Map<String, String> owners = new HashMap<>();

    /** owner → 可编辑的 stationObjectId 集合（模拟 canEdit(UUID, objectId, false)）。 */
    private final Map<String, Set<String>> permissions = new HashMap<>();

    private RemovedRefsData removedRefs;

    @BeforeEach
    void setUp() {
        data = new ClientData();

        stationA = addStation(0, 0, 100, 100);
        stationB = addStation(200, 200, 300, 300);

        platform1 = addPlatform(10, 10, stationA);
        platform2 = addPlatform(210, 210, stationB);

        route = new Route(TransportMode.TRAIN, data);
        data.routes.add(route);
        route.getRoutePlatforms().add(new RoutePlatformData(platform1.getId()));
        route.getRoutePlatforms().add(new RoutePlatformData(platform2.getId()));

        // 真正跑一次 MTR 的 sync：填 platformIdMap、挂 area、解析 rpd.platform
        data.sync();

        routeId = RouteRefReconciler.routeObjectId(route);
        stationAId = RemovedRefsData.stationObjectId(stationA.getId());
        stationBId = RemovedRefsData.stationObjectId(stationB.getId());

        owners.put(routeId, OWNER_A);
        grant(OWNER_A, stationAId);
        grant(OWNER_A, stationBId);

        removedRefs = new RemovedRefsData(tempDir.resolve("removed_refs.json"), now::get);
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    private Station addStation(long x1, long z1, long x2, long z2) {
        final Station station = new Station(data);
        station.setCorners(new Position(x1, Long.MIN_VALUE, z1), new Position(x2, Long.MAX_VALUE, z2));
        data.stations.add(station);
        return station;
    }

    private Platform addPlatform(long x, long z, Station parent) {
        final Platform platform = new Platform(
                new Position(x, 64, z), new Position(x + 5, 64, z), TransportMode.TRAIN, data);
        data.platforms.add(platform);
        return platform;
    }

    /** 在 stationB 范围内新增一个站台（MTR 的 position 是 final，改父车站只能新增对象）。 */
    private Platform addPlatformInStationB(long x, long z) {
        final Platform platform = addPlatform(x, z, stationB);
        data.sync();
        assertSame(stationB, platform.area, "新站台应挂到 stationB");
        return platform;
    }

    private void grant(String owner, String stationObjectId) {
        permissions.computeIfAbsent(owner, key -> new HashSet<>()).add(stationObjectId);
    }

    private void revoke(String owner, String stationObjectId) {
        final Set<String> set = permissions.get(owner);
        if (set != null) {
            set.remove(stationObjectId);
        }
    }

    private RouteRefReconciler.Result reconcile() {
        return RouteRefReconciler.reconcile(data, owners::get, removedRefs,
                (owner, stationObjectId) -> {
                    final Set<String> set = permissions.get(owner);
                    return set != null && set.contains(stationObjectId);
                });
    }

    /**
     * 已解析的引用 id 列表。
     *
     * <p>加回的条目是 {@code new RoutePlatformData(platformId)}，它的 {@code platform} 字段要等
     * 下一次 sync 才解析（生产代码里对账挂在 sync RETURN，所以那时必定非 null）；
     * 这里只统计「已解析」的条目，未解析的用 {@link #unresolvedRefCount()} 单独断言。</p>
     */
    private List<Long> referencedPlatformIds() {
        final List<Long> ids = new ArrayList<>();
        for (RoutePlatformData rpd : route.getRoutePlatforms()) {
            if (rpd.getPlatform() != null) {
                ids.add(rpd.getPlatform().getId());
            }
        }
        return ids;
    }

    /** 未解析（platform == null）的条目数——只可能是对账刚加回来的。 */
    private long unresolvedRefCount() {
        return route.getRoutePlatforms().stream().filter(rpd -> rpd.getPlatform() == null).count();
    }

    /** 让加回的条目像生产环境那样被 MTR 解析一次。 */
    private void resync() {
        data.sync();
    }

    /** 断言线路当前引用了该站台（加回后未解析的条目也算）。 */
    private void assertReferences(long platformId) {
        assertTrue(referencedPlatformIds().contains(platformId) || unresolvedRefCount() > 0,
                "线路应引用站台 " + platformId + "，实际引用=" + referencedPlatformIds()
                        + "，未解析=" + unresolvedRefCount());
    }

    /** 断言线路当前不再引用该站台。 */
    private void assertNotReferences(long platformId) {
        assertFalse(referencedPlatformIds().contains(platformId),
                "线路不应再引用站台 " + platformId);
    }

    // =====================================================================
    // 失权 → 移除
    // =====================================================================

    @Test
    @DisplayName("夹具自检：sync 后 platformIdMap / area / rpd.platform 都已就绪")
    void fixtureIsWired() {
        assertSame(platform1, data.platformIdMap.get(platform1.getId()));
        assertSame(stationA, platform1.area);
        assertEquals(2, route.getRoutePlatforms().size());
        assertNotNull(route.getRoutePlatforms().get(0).getPlatform());
        assertEquals("route:" + Utilities.numberToPaddedHexString(route.getId()), routeId);
    }

    @Test
    @DisplayName("owner 失去权限 → 从线路移除该站台并记录")
    void removesOnLostPermission() {
        revoke(OWNER_A, stationAId);

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.removed().size());
        assertEquals(new RouteRefReconciler.Ref(routeId, platform1.getId()), result.removed().get(0));
        assertTrue(result.changed());
        assertEquals(List.of(platform2.getId()), referencedPlatformIds(), "只移除失权的那个站台");
        assertTrue(removedRefs.hasRemoved(routeId, platform1.getId()));
        assertEquals(stationAId, removedRefs.getRemoved(routeId).get(0).stationObjectId);
        assertFalse(removedRefs.hasRemoved(routeId, platform2.getId()));
    }

    @Test
    @DisplayName("一次对账发现多条失权 → 全部移除，记录各一条")
    void removesAllLostPermissionsAtOnce() {
        revoke(OWNER_A, stationAId);
        revoke(OWNER_A, stationBId);

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(2, result.removed().size());
        assertTrue(referencedPlatformIds().isEmpty());
        assertEquals(2, removedRefs.size());
        assertTrue(removedRefs.hasRemoved(routeId, platform1.getId()));
        assertTrue(removedRefs.hasRemoved(routeId, platform2.getId()));
    }

    @Test
    @DisplayName("幂等：连续对账不会重复移除，也不重复记录")
    void idempotent() {
        revoke(OWNER_A, stationAId);
        assertEquals(1, reconcile().removed().size());

        final RouteRefReconciler.Result second = reconcile();
        assertFalse(second.changed(), "第二次对账应无事发生");
        assertEquals(1, removedRefs.size());
    }

    // =====================================================================
    // 恢复 → 加回
    // =====================================================================

    @Test
    @DisplayName("权限恢复 → 加回线路并删记录，不提示确认")
    void restoresOnPermissionBack() {
        revoke(OWNER_A, stationAId);
        reconcile();
        assertEquals(1, referencedPlatformIds().size());

        grant(OWNER_A, stationAId);
        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.restored().size());
        assertEquals(new RouteRefReconciler.Ref(routeId, platform1.getId()), result.restored().get(0));
        assertEquals(2, route.getRoutePlatforms().size(), "站台已加回（新增一条 new RoutePlatformData）");
        assertReferences(platform2.getId());
        assertReferences(platform1.getId());
        resync();
        assertEquals(2, referencedPlatformIds().size(), "resync 后两条都已解析");
        assertEquals(0, removedRefs.size(), "记录已删除");
        assertFalse(removedRefs.hasRemoved(routeId, platform1.getId()));
    }

    @Test
    @DisplayName("加回时数据侧不会重复添加（幂等），记录仍被清掉")
    void restoreIsIdempotent() {
        revoke(OWNER_A, stationAId);
        reconcile();
        // 手工把引用加回去（模拟网页 dashboard / 玩家自己加回来）
        route.getRoutePlatforms().add(new RoutePlatformData(platform1.getId()));
        data.sync();
        grant(OWNER_A, stationAId);

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.restored().size());
        assertReferences(platform1.getId());
        resync();
        assertEquals(2, route.getRoutePlatforms().size(), "不能出现重复引用");
        assertEquals(1, referencedPlatformIds().stream().filter(id -> id == platform1.getId()).count(),
                "platform1 只能出现一次");
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("权限还没恢复 → 保持移除，不擅自加回")
    void staysRemovedWhileStillRevoked() {
        revoke(OWNER_A, stationAId);
        reconcile();
        final RouteRefReconciler.Result result = reconcile();

        assertFalse(result.changed(), "第二次对账不应有改动，实际: " + result
                + "，账本: " + removedRefs.getAll());
        assertEquals(1, route.getRoutePlatforms().size());
        assertNotReferences(platform1.getId());
        assertTrue(removedRefs.hasRemoved(routeId, platform1.getId()));
    }

    // =====================================================================
    // fail-open
    // =====================================================================

    @Test
    @DisplayName("无 owner → 整条线路跳过（fail-open），即使权限桩全拒")
    void noOwnerIsSkipped() {
        owners.remove(routeId);
        permissions.clear();

        final RouteRefReconciler.Result result = reconcile();

        assertFalse(result.changed());
        assertEquals(2, route.getRoutePlatforms().size());
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("owner 为空串 → 同样 fail-open")
    void blankOwnerIsSkipped() {
        owners.put(routeId, "");
        permissions.clear();
        assertFalse(reconcile().changed());
        assertEquals(2, route.getRoutePlatforms().size());
    }

    @Test
    @DisplayName("孤儿站台（area == null，车站已删）→ 当前引用 fail-open，不误移除")
    void orphanPlatformIsFailOpenOnCurrentRefs() {
        // 删除车站：sync 会把 platform.area 清空，站台本身还在
        data.stations.remove(stationA);
        data.sync();
        assertNull(platform1.area, "车站删除后 area 应为 null");
        assertNotNull(data.platformIdMap.get(platform1.getId()), "站台本身还在");

        final RouteRefReconciler.Result result = reconcile();

        assertFalse(result.changed());
        assertEquals(2, route.getRoutePlatforms().size(), "孤儿站台不参与判定");
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("平台对象已从数据里消失 → MTR 自己剪掉该引用，对账不会误判为失权")
    void missingPlatformObjectIsNotMistakenForLostPermission() {
        data.platforms.remove(platform1);
        data.sync();

        final RouteRefReconciler.Result result = reconcile();

        assertFalse(result.changed(), "对账不应产生任何改动: " + result);
        assertEquals(1, route.getRoutePlatforms().size(), "MTR 的 sync 已把解析不到 platform 的条目剪掉");
        assertEquals(0, removedRefs.size(), "不属于权限问题的条目不进账本");
        assertEquals(List.of(platform2.getId()), referencedPlatformIds());
    }

    @Test
    @DisplayName("入参为 null → 空结果，不 NPE")
    void nullArgumentsAreSafe() {
        assertFalse(RouteRefReconciler.reconcile(null, owners::get, removedRefs, (a, b) -> true).changed());
        assertFalse(RouteRefReconciler.reconcile(data, null, removedRefs, (a, b) -> true).changed());
        assertFalse(RouteRefReconciler.reconcile(data, owners::get, null, (a, b) -> true).changed());
        assertFalse(RouteRefReconciler.reconcile(data, owners::get, removedRefs, null).changed());
    }

    // =====================================================================
    // 车站被删除
    // =====================================================================

    @Test
    @DisplayName("车站被删除（area 变 null）→ 已被记录过的引用保持移除")
    void deletedStationKeepsPreviouslyRemovedRefRemoved() {
        revoke(OWNER_A, stationAId);
        reconcile();
        assertEquals(1, removedRefs.size());

        // 车站被删除 → area 变 null；此时权限桩已无从判定
        data.stations.remove(stationA);
        data.sync();

        final RouteRefReconciler.Result result = reconcile();

        assertFalse(result.restored().contains(new RouteRefReconciler.Ref(routeId, platform1.getId())));
        assertEquals(1, route.getRoutePlatforms().size(), "不应被加回");
        assertNotReferences(platform1.getId());
        assertTrue(removedRefs.hasRemoved(routeId, platform1.getId()), "记录保留（等待 30 天清理 / 手动 restore）");
    }

    @Test
    @DisplayName("车站被删除且记录里父车站是 null → 保持移除，不炸（记录留给 30 天清理）")
    void stationObjectIdNullInLedger() {
        assertTrue(removedRefs.recordRemoved(routeId, platform1.getId(), null));
        // 手工把平台从引用里去掉，模拟「已被移除」
        route.getRoutePlatforms().removeIf(rpd -> rpd.getPlatform().getId() == platform1.getId());
        data.sync();
        data.stations.remove(stationA);
        data.sync();

        final RouteRefReconciler.Result result = reconcile();

        assertFalse(result.restored().contains(new RouteRefReconciler.Ref(routeId, platform1.getId())));
        assertEquals(1, referencedPlatformIds().size());
        assertTrue(removedRefs.hasRemoved(routeId, platform1.getId()));
    }

    // =====================================================================
    // 幽灵记录清理
    // =====================================================================

    @Test
    @DisplayName("幽灵记录：线路已删 → 记录被清理")
    void purgesWhenRouteDeleted() {
        assertTrue(removedRefs.recordRemoved(routeId, platform1.getId(), stationAId));
        data.routes.remove(route);

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.purged().size());
        assertEquals(new RouteRefReconciler.Ref(routeId, platform1.getId()), result.purged().get(0));
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("幽灵记录：站台已删 → 记录被清理")
    void purgesWhenPlatformDeleted() {
        assertTrue(removedRefs.recordRemoved(routeId, platform1.getId(), stationAId));
        data.platforms.remove(platform1);
        data.sync();

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.purged().size());
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("幽灵记录：站台还在但父车站已变（且已不在引用里）→ 记录被清理")
    void purgesWhenParentStationChangedAndNotReferenced() {
        // 在 stationB 新增一个站台，账本里记的是「它以前属于 stationA」
        final Platform moved = addPlatformInStationB(210, 210);
        final long movedId = moved.getId();
        assertTrue(removedRefs.recordRemoved(routeId, movedId, stationAId));

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.purged().size(), "旧父车站的记录失效: " + result);
        assertTrue(result.restored().isEmpty(), "不能按旧父车站的记录加回");
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("记录里的父车站与当前不一致（站台已换父车站）→ 旧记录失效被清，不误加回")
    void staleParentStationRecordIsPurgedNotRestored() {
        // platform1 实际属于 stationA，但账本里记的是 stationB
        assertTrue(removedRefs.recordRemoved(routeId, platform1.getId(), stationBId));
        // 模拟「已被移除过」：platform1 不在线路引用里
        route.getRoutePlatforms().removeIf(rpd -> rpd.getPlatform().getId() == platform1.getId());

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.purged().size(), "旧父车站的记录被清: " + result);
        assertTrue(result.restored().isEmpty(), "不能按旧记录加回");
        assertEquals(1, route.getRoutePlatforms().size());
        assertEquals(0, removedRefs.size());
    }

    @Test
    @DisplayName("站台换回原父车站后：记录与当前一致 → 权限恢复则加回")
    void recordMatchingCurrentParentCanBeRestored() {
        final long platformId = platform1.getId();
        route.getRoutePlatforms().removeIf(rpd -> rpd.getPlatform().getId() == platformId);
        assertTrue(removedRefs.recordRemoved(routeId, platformId, stationAId));

        // 权限仍在 → 直接加回
        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.restored().size(), "记录与当前父车站一致 → 自动加回: " + result);
        assertTrue(removedRefs.getRemoved(routeId).isEmpty());
        resync();
        assertTrue(referencedPlatformIds().contains(platformId));
    }

    @Test
    @DisplayName("幽灵记录：正常记录（线路 / 站台 / 父车站都在）不被清理")
    void normalRecordIsKept() {
        assertTrue(removedRefs.recordRemoved(routeId, platform1.getId(), stationAId));

        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.restored().size(), "权限还在 → 自动加回");
        assertEquals(0, result.purged().size());
        assertEquals(0, removedRefs.size());
    }

    // =====================================================================
    // 坏文件兜底
    // =====================================================================

    @Test
    @DisplayName("loadFailed → 本轮对账完全跳过，不做任何移除")
    void loadFailedSkipsWholeReconcile() throws IOException {
        final Path file = tempDir.resolve("broken.json");
        Files.writeString(file, "not json at all", StandardCharsets.UTF_8);
        final RemovedRefsData broken = new RemovedRefsData(file, now::get);
        broken.load();
        assertTrue(broken.isLoadFailed());

        revoke(OWNER_A, stationAId);
        final RouteRefReconciler.Result result = RouteRefReconciler.reconcile(
                data, owners::get, broken, (owner, stationObjectId) -> false);

        assertFalse(result.changed());
        assertEquals(2, route.getRoutePlatforms().size(), "坏文件时一个引用都不能移除");
        assertEquals(0, broken.size());
    }

    // =====================================================================
    // 覆盖不膨胀
    // =====================================================================

    @Test
    @DisplayName("反复横跳：多次失权 / 恢复后记录不膨胀")
    void flappingDoesNotGrowLedger() {
        for (int i = 0; i < 20; i++) {
            revoke(OWNER_A, stationAId);
            reconcile();
            grant(OWNER_A, stationAId);
            reconcile();
        }
        // 最后一轮是恢复 → 账本应为空
        assertEquals(0, removedRefs.size());
        // 恢复出来的是「未解析」条目，resync 一次即可确认能正常解析回去
        resync();
        assertEquals(2, route.getRoutePlatforms().size());
        assertEquals(2, referencedPlatformIds().size());
        assertTrue(referencedPlatformIds().contains(platform1.getId()));

        revoke(OWNER_A, stationAId);
        reconcile();
        assertEquals(1, removedRefs.size(), "20 次横跳后仍只有 1 条记录");
    }

    @Test
    @DisplayName("多线路共享站台：只处理自己 owner 的线路")
    void multipleRoutesAreIndependent() {
        final Route route2 = new Route(TransportMode.TRAIN, data);
        data.routes.add(route2);
        route2.getRoutePlatforms().add(new RoutePlatformData(platform1.getId()));
        data.sync();
        final String route2Id = RouteRefReconciler.routeObjectId(route2);
        owners.put(route2Id, OWNER_B);
        grant(OWNER_B, stationAId);

        revoke(OWNER_A, stationAId);
        final RouteRefReconciler.Result result = reconcile();

        assertEquals(1, result.removed().size(), "只有 A 的线路被移除");
        assertEquals(1, route.getRoutePlatforms().size());
        assertNotReferences(platform1.getId());
        assertEquals(1, route2.getRoutePlatforms().size(), "B 的线路不受影响");
        assertTrue(removedRefs.hasRemoved(routeId, platform1.getId()));
        assertFalse(removedRefs.hasRemoved(route2Id, platform1.getId()));
    }
}
