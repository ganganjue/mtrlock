package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.1.0 {@link PermissionChecker} “分享感知”纯逻辑重载的单元测试。
 *
 * <p>全部用 lambda / 桩注入 {@link CreatorLookup} / {@link ShareLookup} /
 * {@link TeamMembershipLookup}，不触碰 {@code OwnershipData} / {@code ShareData} /
 * {@code TeamData} 单例（它们依赖 FabricLoader），可在纯 JVM 下运行。</p>
 */
class PermissionCheckerShareTest {

    private static final String OBJECT_ID = "route:0B0829457F350DE9";
    private static final String OTHER_OBJECT_ID = "station:695F49B3A0810590";

    private static final String CREATOR = "creator-1111";
    private static final String MEMBER = "member-2222";
    private static final String OUTSIDER = "outsider-3333";

    private static final String TEAM_A = "team-A";
    private static final String TEAM_B = "team-B";

    // =====================================================================
    // 桩
    // =====================================================================

    private static CreatorLookup creator(String objectId, String creatorUuid) {
        return id -> objectId.equals(id) ? creatorUuid : null;
    }

    private static CreatorLookup noCreator() {
        return id -> null;
    }

    private static ShareLookup shares(String objectId, Set<String> teamIds) {
        return id -> objectId.equals(id) ? teamIds : Collections.emptySet();
    }

    private static ShareLookup noShares() {
        return id -> Collections.emptySet();
    }

    private static TeamMembershipLookup members(Map<String, Set<String>> teamMembers) {
        return (teamId, playerUuid) -> teamMembers.getOrDefault(teamId, Collections.emptySet()).contains(playerUuid);
    }

    private static TeamMembershipLookup noMembers() {
        return (teamId, playerUuid) -> false;
    }

    /** 便捷调用：默认非管理员。 */
    private static boolean decide(String objectId, String playerUuid,
                                  CreatorLookup creators, ShareLookup shares,
                                  TeamMembershipLookup memberships) {
        return PermissionChecker.canEdit(objectId, playerUuid, false, creators, shares, memberships);
    }

    // =====================================================================
    // 管理员 / 创建者
    // =====================================================================

    @Test
    @DisplayName("管理员：即使其它参数全为 null 也放行，不 NPE")
    void adminShortCircuits() {
        assertTrue(PermissionChecker.canEdit(null, null, true, null, null, null));
    }

    @Test
    @DisplayName("管理员：不是创建者、也没分享也放行")
    void adminBeatsNonCreator() {
        assertTrue(PermissionChecker.canEdit(OBJECT_ID, OUTSIDER, true,
                noCreator(), noShares(), noMembers()));
    }

    @Test
    @DisplayName("创建者本人 → true（即使没有分享）")
    void creatorAllowed() {
        assertTrue(decide(OBJECT_ID, CREATOR, creator(OBJECT_ID, CREATOR), noShares(), noMembers()));
    }

    @Test
    @DisplayName("非创建者、无分享 → false")
    void nonCreatorNoShareDenied() {
        assertFalse(decide(OBJECT_ID, OUTSIDER, creator(OBJECT_ID, CREATOR), noShares(), noMembers()));
    }

    // =====================================================================
    // 团队成员
    // =====================================================================

    @Test
    @DisplayName("对象分享给团队 A，玩家是团队 A 成员 → true")
    void teamMemberAllowed() {
        assertTrue(decide(OBJECT_ID, MEMBER,
                noCreator(),
                shares(OBJECT_ID, Set.of(TEAM_A)),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
    }

    @Test
    @DisplayName("对象分享给团队 A，玩家不是任何成员 → false")
    void nonMemberDenied() {
        assertFalse(decide(OBJECT_ID, OUTSIDER,
                noCreator(),
                shares(OBJECT_ID, Set.of(TEAM_A)),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
    }

    @Test
    @DisplayName("对象分享给团队 A，玩家在团队 B → false（必须是同一团队）")
    void membershipInOtherTeamDenied() {
        assertFalse(decide(OBJECT_ID, OUTSIDER,
                noCreator(),
                shares(OBJECT_ID, Set.of(TEAM_A)),
                members(Map.of(TEAM_B, Set.of(OUTSIDER)))));
    }

    @Test
    @DisplayName("对象分享给 A、B 两个团队，玩家是 B 成员 → true")
    void anySharedTeamMatches() {
        assertTrue(decide(OBJECT_ID, OUTSIDER,
                noCreator(),
                shares(OBJECT_ID, Set.of(TEAM_A, TEAM_B)),
                members(Map.of(TEAM_A, Set.of(MEMBER), TEAM_B, Set.of(OUTSIDER)))));
    }

    @Test
    @DisplayName("分享的是别的对象 → 不放行")
    void otherObjectSharDenied() {
        assertFalse(decide(OBJECT_ID, MEMBER,
                noCreator(),
                shares(OTHER_OBJECT_ID, Set.of(TEAM_A)),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
    }

    @Test
    @DisplayName("无归属 + 无分享 → false")
    void noRecordNoShareDenied() {
        assertFalse(decide(OBJECT_ID, MEMBER, noCreator(), noShares(), noMembers()));
    }

    // =====================================================================
    // 边界 / null 安全
    // =====================================================================

    @Test
    @DisplayName("objectId 为 null / 空 → false")
    void objectIdInvalid() {
        assertFalse(decide(null, MEMBER, creator(OBJECT_ID, CREATOR), noShares(), noMembers()));
        assertFalse(decide("", MEMBER, creator(OBJECT_ID, CREATOR), noShares(), noMembers()));
    }

    @Test
    @DisplayName("playerUuid 为 null / 空 → false")
    void playerUuidInvalid() {
        assertFalse(decide(OBJECT_ID, null, creator(OBJECT_ID, CREATOR), noShares(), noMembers()));
        assertFalse(decide(OBJECT_ID, "", creator(OBJECT_ID, CREATOR), noShares(), noMembers()));
    }

    @Test
    @DisplayName("shares / memberships 为 null → 非创建者返回 false，不 NPE")
    void nullLookups() {
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(), null, noMembers()));
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(), noShares(), null));
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(), null, null));
    }

    @Test
    @DisplayName("creators 为 null → 仍可用分享判定")
    void nullCreatorsFallsBackToShares() {
        assertTrue(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                null,
                shares(OBJECT_ID, Set.of(TEAM_A)),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                null, noShares(), noMembers()));
    }

    @Test
    @DisplayName("ShareLookup 返回 null / 空集合 → 不 NPE、不放行")
    void shareLookupNullOrEmpty() {
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(),
                id -> null,
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(),
                noShares(),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
    }

    @Test
    @DisplayName("分享集合里含 null teamId → 跳过，不 NPE")
    void nullTeamIdInSharesIsSkipped() {
        final java.util.Set<String> withNull = new java.util.HashSet<>();
        withNull.add(null);
        withNull.add(TEAM_A);
        assertTrue(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(),
                shares(OBJECT_ID, withNull),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));

        final java.util.Set<String> onlyNull = new java.util.HashSet<>();
        onlyNull.add(null);
        assertFalse(PermissionChecker.canEdit(OBJECT_ID, MEMBER, false,
                noCreator(),
                shares(OBJECT_ID, onlyNull),
                members(Map.of(TEAM_A, Set.of(MEMBER)))));
    }

    @Test
    @DisplayName("创建者优先：创建者 + 分享信息为空也放行")
    void creatorWinsRegardlessOfShares() {
        assertTrue(decide(OBJECT_ID, CREATOR,
                creator(OBJECT_ID, CREATOR),
                id -> null,
                (teamId, uuid) -> {
                    throw new AssertionError("创建者命中后不应再查团队成员");
                }));
    }
}
