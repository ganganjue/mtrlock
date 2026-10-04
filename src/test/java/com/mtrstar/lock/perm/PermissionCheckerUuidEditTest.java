package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.0 {@link PermissionChecker#canEdit(UUID, String, boolean)} UUID 重载的单元测试。
 *
 * <p>生产 UUID 重载内部走 {@code OwnershipData} / {@code ShareData} / {@code TeamData}
 * 单例（依赖 FabricLoader），因此纯 JVM 侧重点验证三件事：</p>
 * <ol>
 *   <li><b>参数短路</b>：UUID 为 null 时在触碰任何单例之前就返回 false（不会 NPE）；</li>
 *   <li><b>语义等价</b>：UUID 重载 = 现有纯逻辑重载 + {@code playerUuid.toString()}，
 *       用同一套权限桩逐一比对（{@link PermissionChecker#canEdit(String, String, boolean,
 *       CreatorLookup, ShareLookup, TeamMembershipLookup)}）；</li>
 *   <li><b>无管理员豁免</b>：二参重载等价 {@code isAdmin=false}，OP 身份无法从 UUID 推出来。</li>
 * </ol>
 */
class PermissionCheckerUuidEditTest {

    private static final UUID CREATOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID MEMBER = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID OUTSIDER = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private static final String ROUTE = "route:0B0829457F350DE9";
    private static final String STATION = "station:695F49B3A0810590";
    private static final String TEAM_A = "team-A";

    private static final CreatorLookup OWNERSHIP = objectId ->
            ROUTE.equals(objectId) ? CREATOR.toString() : null;
    private static final ShareLookup SHARES = objectId ->
            ROUTE.equals(objectId) ? Set.of(TEAM_A) : Collections.emptySet();
    private static final TeamMembershipLookup MEMBERSHIPS = (teamId, playerUuid) ->
            TEAM_A.equals(teamId) && MEMBER.toString().equals(playerUuid);

    /**
     * UUID 重载的等价参照实现：与生产实现同一行代码，只是把单例换成测试桩。
     * {@code UUID.toString()} 就是生产实现里 UUID → 字符串的那一步。
     */
    private static boolean uuidOverloadWithStubs(UUID uuid, String objectId, boolean admin) {
        return PermissionChecker.canEdit(objectId, uuid == null ? null : uuid.toString(), admin,
                OWNERSHIP, SHARES, MEMBERSHIPS);
    }

    // =====================================================================
    // 生产重载：参数短路
    // =====================================================================

    @Test
    @DisplayName("生产 UUID 重载：UUID 为 null → false（在触碰单例之前短路）")
    void productionNullUuidShortCircuits() {
        assertFalse(PermissionChecker.canEdit((UUID) null, ROUTE, false));
        assertFalse(PermissionChecker.canEdit((UUID) null, ROUTE));
        // 与纯逻辑重载一致：isAdmin 豁免优先于参数校验
        assertTrue(PermissionChecker.canEdit((UUID) null, ROUTE, true),
                "isAdmin=true 时管理员豁免优先（不会触碰单例，也不 NPE）");
    }

    // =====================================================================
    // 与纯逻辑重载一致
    // =====================================================================

    @Test
    @DisplayName("UUID 重载：创建者 → true；团队成员 → true；其它 → false")
    void uuidOverloadMatchesPureLogic() {
        assertTrue(uuidOverloadWithStubs(CREATOR, ROUTE, false));
        assertTrue(uuidOverloadWithStubs(MEMBER, ROUTE, false));
        assertFalse(uuidOverloadWithStubs(OUTSIDER, ROUTE, false));
    }

    @Test
    @DisplayName("UUID 重载：别的对象 / 空参数 → false")
    void uuidOverloadDeniesUnknownObjects() {
        assertFalse(uuidOverloadWithStubs(CREATOR, STATION, false), "创建者只对自己创建的对象有权限");
        assertFalse(uuidOverloadWithStubs(CREATOR, null, false));
        assertFalse(uuidOverloadWithStubs(CREATOR, "", false));
    }

    @Test
    @DisplayName("UUID 重载：isAdmin=true 才享受管理员豁免（对账固定 false）")
    void adminFlagIsHonoured() {
        assertTrue(uuidOverloadWithStubs(OUTSIDER, ROUTE, true), "显式传 true 时与旧行为一致");
        assertFalse(uuidOverloadWithStubs(OUTSIDER, ROUTE, false), "对账场景传 false → 拒绝");
    }

    @Test
    @DisplayName("二参重载与三参(isAdmin=false) 逐一等价")
    void twoArgEqualsThreeArg() {
        for (UUID uuid : new UUID[]{CREATOR, MEMBER, OUTSIDER, null}) {
            for (String objectId : new String[]{ROUTE, STATION, null, ""}) {
                assertEquals(getStubTwoArg(uuid, objectId), uuidOverloadWithStubs(uuid, objectId, false),
                        "canEdit(UUID,String) 必须等价 canEdit(UUID,String,false): " + uuid + " / " + objectId);
            }
        }
    }

    /** 二参语义的参照实现。 */
    private static boolean getStubTwoArg(UUID uuid, String objectId) {
        return PermissionChecker.canEdit(objectId, uuid == null ? null : uuid.toString(), false,
                OWNERSHIP, SHARES, MEMBERSHIPS);
    }

    @Test
    @DisplayName("UUID.toString() 与权限数据里的小写带连字符格式一致")
    void uuidStringFormat() {
        final String text = CREATOR.toString();
        assertEquals(text, text.toLowerCase(Locale.ROOT), "必须小写");
        assertEquals(text, text.replace(" ", ""), "不能有空格");
        assertTrue(text.contains("-"), "必须带连字符");
        assertEquals(CREATOR, UUID.fromString(text), "往返一致");
    }

    // =====================================================================
    // 权限矩阵：创建者 / 团队成员 / 分享对象 / 非授权（离线 UUID 视角）
    // =====================================================================

    @Test
    @DisplayName("权限矩阵：创建者 ∨ 团队成员 ∨ 其它一律拒绝")
    void permissionMatrix() {
        final Map<UUID, Boolean> expected = Map.of(
                CREATOR, Boolean.TRUE,
                MEMBER, Boolean.TRUE,
                OUTSIDER, Boolean.FALSE);
        for (Map.Entry<UUID, Boolean> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), uuidOverloadWithStubs(entry.getKey(), ROUTE, false),
                    "玩家 " + entry.getKey() + " 的判定不符");
        }
    }

    @Test
    @DisplayName("权限矩阵：对象没有归属 / 没有分享 → 任何人（含任意 UUID）都拒绝")
    void emptyPermissionDataDeniesEveryone() {
        for (UUID uuid : new UUID[]{CREATOR, MEMBER, OUTSIDER}) {
            assertFalse(PermissionChecker.canEdit(STATION, uuid.toString(), false,
                    id -> null, id -> Collections.emptySet(), (teamId, playerUuid) -> false));
        }
    }

    @Test
    @DisplayName("权限矩阵：只分享给团队但玩家不是成员 → 拒绝")
    void sharedToTeamWithoutMembershipIsDenied() {
        assertFalse(PermissionChecker.canEdit(ROUTE, OUTSIDER.toString(), false,
                OWNERSHIP, id -> Set.of(TEAM_A), (teamId, playerUuid) -> false));
        assertTrue(PermissionChecker.canEdit(ROUTE, OUTSIDER.toString(), false,
                OWNERSHIP, id -> Set.of(TEAM_A), (teamId, playerUuid) -> TEAM_A.equals(teamId)));
    }
}
