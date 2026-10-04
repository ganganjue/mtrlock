package com.mtrstar.lock.protect;

import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.perm.PermissionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockProtection} 单元测试（1.3.0）——方块拦截权限矩阵。
 *
 * <p>权限判定走真实的 {@link PermissionChecker#canEdit(String, String, boolean,
 * com.mtrstar.lock.perm.CreatorLookup, com.mtrstar.lock.perm.ShareLookup,
 * com.mtrstar.lock.perm.TeamMembershipLookup)}（生产上就是这条路径），
 * 归属 / 分享 / 团队成员用桩注入，不触碰单例与文件系统。</p>
 */
class BlockProtectionTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String STATION = "station:0B0829457F350DE9";
    private static final String DEPOT = "depot:1111111111111111";
    private static final String TEAM = "team-1";

    /** 用真实 PermissionChecker 构造判定函数。 */
    private static PermissionGuard.EditPermission permission(String playerUuid, boolean admin,
                                                             Map<String, String> creators,
                                                             Map<String, Set<String>> shares,
                                                             Set<String> memberships) {
        return objectId -> PermissionChecker.canEdit(objectId, playerUuid, admin,
                creators::get,
                id -> shares.getOrDefault(id, Set.of()),
                (teamId, uuid) -> memberships.contains(teamId + "|" + uuid));
    }

    private static Predicate<String> owned(String... objectIds) {
        final Set<String> set = Set.of(objectIds);
        return set::contains;
    }

    private static Predicate<String> noneOwned() {
        return objectId -> false;
    }

    // =====================================================================
    // 无覆盖 / 无归属 → fail-open
    // =====================================================================

    @Test
    @DisplayName("没有任何对象覆盖该方块 → 放行")
    void noCoveringObjects() {
        assertTrue(BlockProtection.canModify(List.of(), noneOwned(), id -> false));
        assertTrue(BlockProtection.canModify(null, noneOwned(), id -> false));
    }

    @Test
    @DisplayName("覆盖对象没有归属记录（网页创建 / 模组安装前）→ 放行")
    void noOwnershipRecordFailOpen() {
        assertTrue(BlockProtection.canModify(List.of(STATION), noneOwned(), id -> false));
    }

    @Test
    @DisplayName("hasCreator / permission 为 null（降级）→ 放行，不 NPE")
    void nullDependenciesFailOpen() {
        assertTrue(BlockProtection.canModify(List.of(STATION), null, id -> false));
        assertTrue(BlockProtection.canModify(List.of(STATION), owned(STATION), null));
    }

    @Test
    @DisplayName("集合里的 null / 空 id 被跳过")
    void nullAndEmptyIdsSkipped() {
        assertTrue(BlockProtection.canModify(Arrays.asList(null, "", STATION),
                owned(STATION), id -> true));
    }

    // =====================================================================
    // 权限矩阵
    // =====================================================================

    @Test
    @DisplayName("创建者本人 → 放行")
    void creatorAllowed() {
        final PermissionGuard.EditPermission p = permission(ALICE, false, Map.of(STATION, ALICE), Map.of(), Set.of());
        assertTrue(BlockProtection.canModify(List.of(STATION), owned(STATION), p));
    }

    @Test
    @DisplayName("非创建者、非团队成员 → 拒绝")
    void strangerDenied() {
        final PermissionGuard.EditPermission p = permission(BOB, false, Map.of(STATION, ALICE), Map.of(), Set.of());
        assertFalse(BlockProtection.canModify(List.of(STATION), owned(STATION), p));
    }

    @Test
    @DisplayName("对象已分享给团队，玩家是团队成员 → 放行")
    void teamMemberAllowed() {
        final PermissionGuard.EditPermission p = permission(BOB, false,
                Map.of(STATION, ALICE), Map.of(STATION, Set.of(TEAM)), Set.of(TEAM + "|" + BOB));
        assertTrue(BlockProtection.canModify(List.of(STATION), owned(STATION), p));
    }

    @Test
    @DisplayName("对象已分享给团队，玩家不是该团队成员 → 拒绝")
    void nonTeamMemberDenied() {
        final PermissionGuard.EditPermission p = permission(BOB, false,
                Map.of(STATION, ALICE), Map.of(STATION, Set.of(TEAM)), Set.of(TEAM + "|" + ALICE));
        assertFalse(BlockProtection.canModify(List.of(STATION), owned(STATION), p));
    }

    @Test
    @DisplayName("OP 3+（isAdmin）→ 放行，即使对象属于别人")
    void adminAllowed() {
        final PermissionGuard.EditPermission p = permission(BOB, true, Map.of(STATION, ALICE), Map.of(), Set.of());
        assertTrue(BlockProtection.canModify(List.of(STATION), owned(STATION), p));
    }

    @Test
    @DisplayName("OP 3+ 在无归属对象上同样放行")
    void adminOnUnownedAllowed() {
        final PermissionGuard.EditPermission p = permission(BOB, true, Map.of(), Map.of(), Set.of());
        assertTrue(BlockProtection.canModify(List.of(STATION), noneOwned(), p));
    }

    // =====================================================================
    // 重叠：任一拒绝即拒绝
    // =====================================================================

    @Test
    @DisplayName("重叠：一个允许一个拒绝 → 拒绝（防止用自有小车厂覆盖别人的车站）")
    void overlappingAnyDenyWins() {
        final PermissionGuard.EditPermission p = permission(ALICE, false,
                Map.of(STATION, BOB, DEPOT, ALICE), Map.of(), Set.of());
        assertFalse(BlockProtection.canModify(List.of(STATION, DEPOT), owned(STATION, DEPOT), p));
    }

    @Test
    @DisplayName("重叠：两个对象都允许 → 放行")
    void overlappingAllAllowed() {
        final PermissionGuard.EditPermission p = permission(ALICE, false,
                Map.of(STATION, ALICE, DEPOT, ALICE), Map.of(), Set.of());
        assertTrue(BlockProtection.canModify(List.of(STATION, DEPOT), owned(STATION, DEPOT), p));
    }

    @Test
    @DisplayName("重叠：一个无归属 + 一个是自己的 → 放行（无归属被跳过）")
    void overlappingUnownedPlusOwned() {
        final PermissionGuard.EditPermission p = permission(ALICE, false,
                Map.of(STATION, ALICE), Map.of(), Set.of());
        assertTrue(BlockProtection.canModify(List.of(STATION, DEPOT), owned(STATION), p));
    }

    @Test
    @DisplayName("重叠：一个无归属 + 一个别人的 → 拒绝")
    void overlappingUnownedPlusForeign() {
        final PermissionGuard.EditPermission p = permission(BOB, false,
                Map.of(STATION, ALICE), Map.of(), Set.of());
        assertFalse(BlockProtection.canModify(List.of(STATION, DEPOT), owned(STATION), p));
    }
}
