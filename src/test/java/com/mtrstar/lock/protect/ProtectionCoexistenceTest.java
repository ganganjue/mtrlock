package com.mtrstar.lock.protect;

import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.perm.PermissionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.tool.Utilities;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与现有「编辑 / 删除保护」共存的回归测试（1.3.0）。
 *
 * <p>两套保护必须走同一个 {@link PermissionChecker} 判定，且互不干扰：</p>
 * <ul>
 *   <li>编辑 / 删除保护：{@link PermissionGuard#findDeniedInUpdate} / {@code findDeniedInDelete}；</li>
 *   <li>区域方块保护：{@link BlockProtection}。</li>
 * </ul>
 *
 * <p>同一个 objectId、同一个玩家，两边结论必须一致：创建者 / 团队成员 / OP 3+ 放行，
 * 陌生人拒绝，无归属 fail-open 放行。</p>
 */
class ProtectionCoexistenceTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String TEAM = "team-1";

    /** 一个含 station id 的 UpdateDataRequest JSON（坐标不重要，只需要 id）。 */
    private static String updateJson(long id) {
        return "{\"stations\":[{\"id\":" + id + ",\"transportMode\":\"TRAIN\",\"name\":\"\","
                + "\"position1\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"position2\":{\"x\":10,\"y\":0,\"z\":10}}],"
                + "\"platforms\":[],\"sidings\":[],\"routes\":[],\"depots\":[]}";
    }

    private static PermissionGuard.EditPermission permission(String playerUuid, boolean admin,
                                                             Map<String, String> creators,
                                                             Map<String, Set<String>> shares,
                                                             Set<String> memberships) {
        return objectId -> PermissionChecker.canEdit(objectId, playerUuid, admin,
                creators::get,
                id -> shares.getOrDefault(id, Set.of()),
                (teamId, uuid) -> memberships.contains(teamId + "|" + uuid));
    }

    @Test
    @DisplayName("创建者：编辑保护放行，方块保护放行")
    void creatorAllowedByBoth() {
        final long id = 12345L;
        final String objectId = "station:" + Utilities.numberToPaddedHexString(id);
        final Map<String, String> creators = Map.of(objectId, ALICE);
        final PermissionGuard.EditPermission p = permission(ALICE, false, creators, Map.of(), Set.of());

        assertEquals(List.of(), PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p));
        assertTrue(BlockProtection.canModify(List.of(objectId), creators::containsKey, p));
    }

    @Test
    @DisplayName("陌生人：编辑保护拒绝，方块保护拒绝（结论一致）")
    void strangerDeniedByBoth() {
        final long id = 67890L;
        final String objectId = "station:" + Utilities.numberToPaddedHexString(id);
        final Map<String, String> creators = Map.of(objectId, ALICE);
        final PermissionGuard.EditPermission p = permission(BOB, false, creators, Map.of(), Set.of());

        assertEquals(List.of(objectId),
                PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p));
        assertFalse(BlockProtection.canModify(List.of(objectId), creators::containsKey, p));
    }

    @Test
    @DisplayName("团队成员：编辑保护放行，方块保护放行")
    void teamMemberAllowedByBoth() {
        final long id = 111L;
        final String objectId = "station:" + Utilities.numberToPaddedHexString(id);
        final Map<String, String> creators = Map.of(objectId, ALICE);
        final Map<String, Set<String>> shares = Map.of(objectId, Set.of(TEAM));
        final PermissionGuard.EditPermission p = permission(BOB, false, creators, shares, Set.of(TEAM + "|" + BOB));

        assertEquals(List.of(), PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p));
        assertTrue(BlockProtection.canModify(List.of(objectId), creators::containsKey, p));
    }

    @Test
    @DisplayName("OP 3+：编辑保护放行，方块保护放行")
    void adminAllowedByBoth() {
        final long id = 222L;
        final String objectId = "station:" + Utilities.numberToPaddedHexString(id);
        final Map<String, String> creators = Map.of(objectId, ALICE);
        final PermissionGuard.EditPermission p = permission(BOB, true, creators, Map.of(), Set.of());

        assertEquals(List.of(), PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p));
        assertTrue(BlockProtection.canModify(List.of(objectId), creators::containsKey, p));
    }

    @Test
    @DisplayName("无归属：两边都 fail-open 放行")
    void unownedFailOpenByBoth() {
        final long id = 333L;
        final String objectId = "station:" + Utilities.numberToPaddedHexString(id);
        final Map<String, String> creators = Map.of();
        final PermissionGuard.EditPermission p = permission(BOB, false, creators, Map.of(), Set.of());

        assertEquals(List.of(), PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p));
        assertTrue(BlockProtection.canModify(List.of(objectId), creators::containsKey, p));
    }

    @Test
    @DisplayName("方块保护不改变编辑保护的结果：同一判定函数两边共用")
    void blockProtectionDoesNotLeakIntoEditProtection() {
        final long id = 444L;
        final String objectId = "station:" + Utilities.numberToPaddedHexString(id);
        final Map<String, String> creators = Map.of(objectId, ALICE);
        final PermissionGuard.EditPermission p = permission(BOB, false, creators, Map.of(), Set.of());

        // 先跑方块保护（会拒绝），再跑编辑保护，结论不受影响。
        assertFalse(BlockProtection.canModify(List.of(objectId), creators::containsKey, p));
        assertEquals(List.of(objectId),
                PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p));

        // 反向：先编辑保护，再方块保护。
        final PermissionGuard.EditPermission p2 = permission(BOB, false, creators, Map.of(), Set.of());
        assertEquals(List.of(objectId),
                PermissionGuard.findDeniedInUpdate(updateJson(id), creators::containsKey, null, p2));
        assertFalse(BlockProtection.canModify(List.of(objectId), creators::containsKey, p2));
    }
}
