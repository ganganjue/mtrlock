package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.mapping.holder.ServerPlayerEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionChecker} 单元测试。
 *
 * <p>归属查询用简单桩 {@link PermissionChecker.OwnershipLookup} 注入，
 * 不触碰 {@link OwnershipData}（它依赖 FabricLoader / 文件系统，纯 JVM 测试里不可用）。
 * 玩家相关的逻辑走包内 seam {@code check(...) / checkCreator(...)}，
 * {@code objectIdOf(...)} 用真实 MTR 对象（Station / Route / Depot）验证前缀与 hexId。</p>
 */
class PermissionCheckerTest {

    private static final String CREATOR_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_UUID = "99999999-8888-7777-6666-555555555555";
    private static final String OBJECT_ID = "route:0B0829457F350DE9";

    /** 简单桩：可控 hasCreator / getCreator。 */
    private static PermissionChecker.OwnershipLookup ownership(boolean hasCreator, String creator) {
        return new PermissionChecker.OwnershipLookup() {
            @Override
            public boolean hasCreator(String objectId) {
                return hasCreator;
            }

            @Override
            public String getCreator(String objectId) {
                return creator;
            }
        };
    }

    private static PermissionChecker.OwnershipLookup noOwnership() {
        return ownership(false, null);
    }

    // =====================================================================
    // 管理员豁免
    // =====================================================================

    @Test
    @DisplayName("管理员：即使其它参数全为 null 也放行，不 NPE")
    void adminShortCircuitsEverything() {
        assertTrue(PermissionChecker.check(true, null, null, null));
    }

    @Test
    @DisplayName("管理员：不是创建者也放行")
    void adminBeatsNonCreator() {
        assertTrue(PermissionChecker.check(true, OTHER_UUID, OBJECT_ID, ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("isAdmin(null) 返回 false，不 NPE")
    void isAdminNullSafe() {
        assertFalse(PermissionChecker.isAdmin(null));
    }

    // =====================================================================
    // 创建者判定
    // =====================================================================

    @Test
    @DisplayName("非管理员 + 创建者本人 → true")
    void creatorMatches() {
        assertTrue(PermissionChecker.check(false, CREATOR_UUID, OBJECT_ID, ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("非管理员 + 不是创建者 → false")
    void creatorMismatch() {
        assertFalse(PermissionChecker.check(false, OTHER_UUID, OBJECT_ID, ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("无归属记录（hasCreator=false）→ false")
    void noOwnershipRecord() {
        assertFalse(PermissionChecker.checkCreator(CREATOR_UUID, OBJECT_ID, noOwnership()));
    }

    @Test
    @DisplayName("玩家 UUID 为 null → false")
    void playerUuidNull() {
        assertFalse(PermissionChecker.checkCreator(null, OBJECT_ID, ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("玩家 UUID 为空串 → false")
    void playerUuidEmpty() {
        assertFalse(PermissionChecker.checkCreator("", OBJECT_ID, ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("objectId 为 null → false")
    void objectIdNull() {
        assertFalse(PermissionChecker.checkCreator(CREATOR_UUID, null, ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("objectId 为空串 → false")
    void objectIdEmpty() {
        assertFalse(PermissionChecker.checkCreator(CREATOR_UUID, "", ownership(true, CREATOR_UUID)));
    }

    @Test
    @DisplayName("ownership 为 null → false，不 NPE")
    void ownershipNull() {
        assertFalse(PermissionChecker.checkCreator(CREATOR_UUID, OBJECT_ID, null));
    }

    @Test
    @DisplayName("有记录但 creator 为 null → false")
    void creatorValueNull() {
        assertFalse(PermissionChecker.checkCreator(CREATOR_UUID, OBJECT_ID, ownership(true, null)));
    }

    // =====================================================================
    // 公共 API 的 null 边界（不触碰 OwnershipData）
    // =====================================================================

    @Test
    @DisplayName("canEdit(null 玩家, objectId) → false")
    void canEditNullPlayer() {
        assertFalse(PermissionChecker.canEdit((ServerPlayerEntity) null, OBJECT_ID));
    }

    @Test
    @DisplayName("canEdit(null 玩家, null objectId) → false")
    void canEditNullPlayerNullId() {
        assertFalse(PermissionChecker.canEdit((ServerPlayerEntity) null, (String) null));
    }

    @Test
    @DisplayName("canEdit(null 玩家, null MTR 对象) → false")
    void canEditNullPlayerNullObject() {
        assertFalse(PermissionChecker.canEdit((ServerPlayerEntity) null, (Object) null));
    }

    // =====================================================================
    // objectIdOf：用真实 MTR 对象验证前缀 + getHexId() 原样拼接
    // =====================================================================

    @Test
    @DisplayName("Station → station:<getHexId()>")
    void objectIdOfStation() {
        final Station station = new Station(new ClientData());
        assertEquals("station:" + station.getHexId(), PermissionChecker.objectIdOf(station));
    }

    @Test
    @DisplayName("Route → route:<getHexId()>")
    void objectIdOfRoute() {
        final Route route = new Route(TransportMode.TRAIN, new ClientData());
        assertEquals("route:" + route.getHexId(), PermissionChecker.objectIdOf(route));
    }

    @Test
    @DisplayName("Depot → depot:<getHexId()>")
    void objectIdOfDepot() {
        final Depot depot = new Depot(TransportMode.TRAIN, new ClientData());
        assertEquals("depot:" + depot.getHexId(), PermissionChecker.objectIdOf(depot));
    }

    @Test
    @DisplayName("hexId 保留 16 位大写，不做 lowercase / 截断")
    void objectIdKeepsOriginalHexId() {
        final Station station = new Station(new ClientData());
        final String objectId = PermissionChecker.objectIdOf(station);
        assertEquals(station.getHexId(), objectId.substring("station:".length()));
        assertEquals(16, station.getHexId().length());
        assertEquals(station.getHexId().toUpperCase(java.util.Locale.ENGLISH), station.getHexId());
    }

    @Test
    @DisplayName("objectIdOf(null) → null")
    void objectIdOfNull() {
        assertNull(PermissionChecker.objectIdOf(null));
    }

    @Test
    @DisplayName("objectIdOf(非 MTR 类型) → null")
    void objectIdOfWrongType() {
        assertNull(PermissionChecker.objectIdOf("not an mtr object"));
        assertNull(PermissionChecker.objectIdOf(new Object()));
    }
}
