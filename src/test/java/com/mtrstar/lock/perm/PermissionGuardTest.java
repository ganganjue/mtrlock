package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.tool.Utilities;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionGuard} 单元测试（纯 JVM，不需要 Minecraft / Fabric / OwnershipData）。
 *
 * <p>覆盖：编辑请求新增 vs 编辑的区分、删除请求、fail-open、JSON 异常、hexId 原样拼接。</p>
 */
class PermissionGuardTest {

    private static final long STATION_ID = 794930712694558185L;
    private static final long ROUTE_ID = 7592868532610729360L;
    private static final long DEPOT_ID = -261431857219323562L;

    private static final String STATION_KEY = PermissionChecker.PREFIX_STATION + ":" + Utilities.numberToPaddedHexString(STATION_ID);
    private static final String ROUTE_KEY = PermissionChecker.PREFIX_ROUTE + ":" + Utilities.numberToPaddedHexString(ROUTE_ID);
    private static final String DEPOT_KEY = PermissionChecker.PREFIX_DEPOT + ":" + Utilities.numberToPaddedHexString(DEPOT_ID);

    /** 模拟 UpdateDataRequest 的 JSON：stations / routes / depots，元素含 "id"。 */
    private static String updateJson() {
        return "{\"stations\":[{\"id\":" + STATION_ID + "}],"
                + "\"routes\":[{\"id\":" + ROUTE_ID + "}],"
                + "\"depots\":[{\"id\":" + DEPOT_ID + "}]}";
    }

    /** 模拟 DeleteDataRequest 的 JSON：stationIds / routeIds / depotIds，元素是 long。 */
    private static String deleteJson() {
        return "{\"stationIds\":[" + STATION_ID + "],"
                + "\"routeIds\":[" + ROUTE_ID + "],"
                + "\"depotIds\":[" + DEPOT_ID + "]}";
    }

    private static PermissionGuard.CreatorLookup existing(Set<String> ids) {
        return ids::contains;
    }

    private static PermissionGuard.EditPermission denyAll() {
        return objectId -> false;
    }

    private static PermissionGuard.EditPermission allowAll() {
        return objectId -> true;
    }

    // =====================================================================
    // 编辑请求
    // =====================================================================

    @Test
    @DisplayName("编辑：全部是创建（无归属记录）→ 不拦截，且不调用 canEdit")
    void createIsNeverDenied() {
        final AtomicInteger canEditCalls = new AtomicInteger();
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                updateJson(),
                existing(Collections.emptySet()),
                objectId -> {
                    canEditCalls.incrementAndGet();
                    return false;
                });

        assertTrue(denied.isEmpty(), "创建请求不应被拦截");
        assertEquals(0, canEditCalls.get(), "对无归属记录的对象不应调用 canEdit");
    }

    @Test
    @DisplayName("编辑：已归属对象且非创建者 → 拒绝")
    void editDenied() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                updateJson(),
                existing(Collections.singleton(ROUTE_KEY)),
                denyAll());

        assertEquals(Collections.singletonList(ROUTE_KEY), denied);
    }

    @Test
    @DisplayName("编辑：已归属对象且是创建者 → 放行")
    void editAllowed() {
        final Set<String> all = new HashSet<>();
        all.add(STATION_KEY);
        all.add(ROUTE_KEY);
        all.add(DEPOT_KEY);

        final List<String> denied = PermissionGuard.findDeniedInUpdate(updateJson(), existing(all), allowAll());
        assertTrue(denied.isEmpty());
    }

    @Test
    @DisplayName("编辑：混合请求（新对象 + 别人的对象）只拒绝别人的那个")
    void mixedCreateAndDeniedEdit() {
        // 只有 route 有归属记录；station/depot 是新建
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                updateJson(),
                existing(Collections.singleton(ROUTE_KEY)),
                denyAll());

        assertEquals(Collections.singletonList(ROUTE_KEY), denied);
    }

    // =====================================================================
    // 删除请求
    // =====================================================================

    @Test
    @DisplayName("删除：已归属对象且非创建者 → 拒绝")
    void deleteDenied() {
        final List<String> denied = PermissionGuard.findDeniedInDelete(
                deleteJson(),
                existing(Collections.singleton(STATION_KEY)),
                denyAll());

        assertEquals(Collections.singletonList(STATION_KEY), denied);
    }

    @Test
    @DisplayName("删除：全部是创建者 → 放行")
    void deleteAllowed() {
        final Set<String> all = new HashSet<>();
        all.add(STATION_KEY);
        all.add(ROUTE_KEY);
        all.add(DEPOT_KEY);

        assertTrue(PermissionGuard.findDeniedInDelete(deleteJson(), existing(all), allowAll()).isEmpty());
    }

    @Test
    @DisplayName("删除：无归属记录（历史对象）→ fail-open 放行")
    void deleteUnknownIsAllowed() {
        assertTrue(PermissionGuard.findDeniedInDelete(deleteJson(), existing(Collections.emptySet()), denyAll()).isEmpty());
    }

    @Test
    @DisplayName("删除：混合（1 个别人的 + 2 个无记录）只拒绝 1 个")
    void deleteMixed() {
        final List<String> denied = PermissionGuard.findDeniedInDelete(
                deleteJson(),
                existing(Collections.singleton(DEPOT_KEY)),
                denyAll());

        assertEquals(Collections.singletonList(DEPOT_KEY), denied);
    }

    // =====================================================================
    // 健壮性 / 边界
    // =====================================================================

    @Test
    @DisplayName("JSON 非法 / 为空 / 缺少字段 → 不拦截（fail-open）")
    void malformedIsFailOpen() {
        assertTrue(PermissionGuard.findDeniedInUpdate(null, existing(Collections.singleton(ROUTE_KEY)), denyAll()).isEmpty());
        assertTrue(PermissionGuard.findDeniedInUpdate("", existing(Collections.singleton(ROUTE_KEY)), denyAll()).isEmpty());
        assertTrue(PermissionGuard.findDeniedInUpdate("{not json", existing(Collections.singleton(ROUTE_KEY)), denyAll()).isEmpty());
        assertTrue(PermissionGuard.findDeniedInUpdate("{}", existing(Collections.singleton(ROUTE_KEY)), denyAll()).isEmpty());
        assertTrue(PermissionGuard.findDeniedInDelete("[]", existing(Collections.singleton(ROUTE_KEY)), denyAll()).isEmpty());
    }

    @Test
    @DisplayName("objectId 用 16 位大写 hexId 原样拼接，不做 lowercase")
    void objectIdKeepsOriginalHex() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                updateJson(),
                existing(Collections.singleton(ROUTE_KEY)),
                denyAll());

        assertEquals(1, denied.size());
        final String objectId = denied.get(0);
        assertTrue(objectId.startsWith("route:"));
        final String hex = objectId.substring("route:".length());
        assertEquals(Utilities.numberToPaddedHexString(ROUTE_ID), hex);
        assertEquals(16, hex.length());
        assertEquals(hex.toUpperCase(java.util.Locale.ENGLISH), hex);
    }

    // =====================================================================
    // 方案 A：子对象（platform / siding）用父对象（station / depot）判权限
    // =====================================================================

    private static final long PLATFORM_ID = 1234567890123456789L;
    private static final long SIDING_ID = -987654321098765432L;

    private static String platformUpdateJson() {
        return "{\"platforms\":[{\"id\":" + PLATFORM_ID + "}]}";
    }

    private static String sidingUpdateJson() {
        return "{\"sidings\":[{\"id\":" + SIDING_ID + "}]}";
    }

    private static String platformDeleteJson() {
        return "{\"platformIds\":[" + PLATFORM_ID + "]}";
    }

    private static String sidingDeleteJson() {
        return "{\"sidingIds\":[" + SIDING_ID + "]}";
    }

    private static String childKey(String prefix, long id) {
        return prefix + ":" + Utilities.numberToPaddedHexString(id);
    }

    @Test
    @DisplayName("编辑 platform：父 station 有归属且非创建者 → 拒绝")
    void platformEditDeniedByParent() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                platformUpdateJson(),
                existing(Collections.singleton(STATION_KEY)),
                id -> STATION_KEY,
                denyAll());

        assertEquals(Collections.singletonList(childKey(ChildParents.PREFIX_PLATFORM, PLATFORM_ID)), denied);
    }

    @Test
    @DisplayName("编辑 platform：父 station 无归属 → fail-open 放行")
    void platformEditFailOpenWhenParentHasNoRecord() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                platformUpdateJson(),
                existing(Collections.emptySet()),
                id -> STATION_KEY,
                denyAll());

        assertTrue(denied.isEmpty());
    }

    @Test
    @DisplayName("编辑 platform：父 station 是当前操作者 → 放行")
    void platformEditAllowedWhenParentOwns() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                platformUpdateJson(),
                existing(Collections.singleton(STATION_KEY)),
                id -> STATION_KEY,
                allowAll());

        assertTrue(denied.isEmpty());
    }

    @Test
    @DisplayName("编辑 platform：ChildParents 查不到父 → fail-open 放行")
    void platformEditFailOpenWhenParentUnknown() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                platformUpdateJson(),
                existing(Collections.emptySet()), // platform 自身也无归属记录
                id -> null,
                denyAll());

        assertTrue(denied.isEmpty());
    }

    @Test
    @DisplayName("删除 platform：父 station 有归属且非创建者 → 拒绝")
    void platformDeleteDeniedByParent() {
        final List<String> denied = PermissionGuard.findDeniedInDelete(
                platformDeleteJson(),
                existing(Collections.singleton(STATION_KEY)),
                id -> STATION_KEY,
                denyAll());

        assertEquals(Collections.singletonList(childKey(ChildParents.PREFIX_PLATFORM, PLATFORM_ID)), denied);
    }

    @Test
    @DisplayName("编辑 siding：父 depot 有归属且非创建者 → 拒绝")
    void sidingEditDeniedByParent() {
        final List<String> denied = PermissionGuard.findDeniedInUpdate(
                sidingUpdateJson(),
                existing(Collections.singleton(DEPOT_KEY)),
                id -> DEPOT_KEY,
                denyAll());

        assertEquals(Collections.singletonList(childKey(ChildParents.PREFIX_SIDING, SIDING_ID)), denied);
    }

    @Test
    @DisplayName("删除 siding：父 depot 有归属且非创建者 → 拒绝")
    void sidingDeleteDeniedByParent() {
        final List<String> denied = PermissionGuard.findDeniedInDelete(
                sidingDeleteJson(),
                existing(Collections.singleton(DEPOT_KEY)),
                id -> DEPOT_KEY,
                denyAll());

        assertEquals(Collections.singletonList(childKey(ChildParents.PREFIX_SIDING, SIDING_ID)), denied);
    }

}
