package com.mtrstar.lock.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TeamActions} 的分享 / 撤销分享单元测试（1.2.3）。
 *
 * <p>重点覆盖：</p>
 * <ul>
 *   <li>分享 / 取消分享的授权（只有对象创建者）；</li>
 *   <li>被踢 / 退队自动撤销“该玩家分享给该团队”的对象；</li>
 *   <li>解散团队撤销指向该团队的全部分享；</li>
 *   <li>撤销只影响目标玩家 / 目标团队，不误删别人的分享。</li>
 * </ul>
 */
class TeamActionsShareTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String ALICE = "22222222-2222-2222-2222-222222222222";
    private static final String BOB = "33333333-3333-3333-3333-333333333333";

    private static final String OBJ_ALICE = "route:00000000000000AA";
    private static final String OBJ_OWNER = "station:00000000000000BB";
    private static final String OBJ_BOB = "depot:00000000000000CC";

    @TempDir
    Path tempDir;

    private TeamData teams;
    private ShareData shares;
    private TeamActions actions;
    private final Map<String, String> creators = new HashMap<>();

    @BeforeEach
    void setUp() {
        teams = new TeamData(tempDir.resolve("teams.json"));
        shares = new ShareData(tempDir.resolve("shares.json"), creators::get,
                teamId -> teams.getTeam(teamId) != null);
        actions = new TeamActions(teams, shares, creators::get);
    }

    private Team newTeam(String name, String owner) {
        final Team team = teams.createTeam(name, owner);
        assertTrue(team != null);
        return team;
    }

    private void addMember(Team team, String owner, String member) {
        assertTrue(actions.invite(owner, team.getTeamId(), member).ok());
        assertTrue(actions.acceptInvitation(member, team.getTeamId()).ok());
    }

    // =====================================================================
    // 分享
    // =====================================================================

    @Test
    @DisplayName("分享：创建者成功 → OBJECT_SHARED；重复分享 → ALREADY_SHARED")
    void shareByCreator() {
        final Team team = newTeam("A队", OWNER);
        creators.put(OBJ_ALICE, ALICE);

        assertEquals(ResultCode.OBJECT_SHARED, actions.share(ALICE, OBJ_ALICE, team.getTeamId()).code());
        assertTrue(shares.getTeamsOfObject(OBJ_ALICE).contains(team.getTeamId()));
        assertEquals(ResultCode.ALREADY_SHARED, actions.share(ALICE, OBJ_ALICE, team.getTeamId()).code());
    }

    @Test
    @DisplayName("分享失败：对象 ID 非法 → OBJECT_ID_INVALID")
    void shareInvalidId() {
        final Team team = newTeam("A队", OWNER);
        assertEquals(ResultCode.OBJECT_ID_INVALID, actions.share(ALICE, "foo:bad", team.getTeamId()).code());
        assertEquals(ResultCode.OBJECT_ID_INVALID, actions.share(ALICE, null, team.getTeamId()).code());
    }

    @Test
    @DisplayName("分享失败：无归属记录 → OBJECT_NO_OWNER；非创建者 → NOT_OBJECT_CREATOR")
    void sharePermissionMatrix() {
        final Team team = newTeam("A队", OWNER);
        creators.put(OBJ_ALICE, ALICE);

        assertEquals(ResultCode.OBJECT_NO_OWNER, actions.share(BOB, "route:00000000000000FF", team.getTeamId()).code());
        assertEquals(ResultCode.NOT_OBJECT_CREATOR, actions.share(BOB, OBJ_ALICE, team.getTeamId()).code());
        assertEquals(ResultCode.NOT_OBJECT_CREATOR, actions.share(OWNER, OBJ_ALICE, team.getTeamId()).code());
    }

    // =====================================================================
    // 取消分享
    // =====================================================================

    @Test
    @DisplayName("取消分享：创建者成功 → OBJECT_UNSHARED；未分享 → NOT_SHARED")
    void unshare() {
        final Team team = newTeam("A队", OWNER);
        creators.put(OBJ_ALICE, ALICE);
        assertTrue(actions.share(ALICE, OBJ_ALICE, team.getTeamId()).ok());

        assertEquals(ResultCode.OBJECT_UNSHARED, actions.unshare(ALICE, OBJ_ALICE, team.getTeamId()).code());
        assertFalse(shares.getTeamsOfObject(OBJ_ALICE).contains(team.getTeamId()));
        assertEquals(ResultCode.NOT_SHARED, actions.unshare(ALICE, OBJ_ALICE, team.getTeamId()).code());
    }

    @Test
    @DisplayName("取消分享失败：ID 非法 / 非创建者")
    void unsharePermissionMatrix() {
        final Team team = newTeam("A队", OWNER);
        creators.put(OBJ_ALICE, ALICE);
        assertTrue(actions.share(ALICE, OBJ_ALICE, team.getTeamId()).ok());

        assertEquals(ResultCode.OBJECT_ID_INVALID, actions.unshare(ALICE, "bad", team.getTeamId()).code());
        assertEquals(ResultCode.NOT_OBJECT_CREATOR, actions.unshare(BOB, OBJ_ALICE, team.getTeamId()).code());
        // 无归属记录也按“非创建者”拒绝（与命令一致）
        assertEquals(ResultCode.NOT_OBJECT_CREATOR, actions.unshare(BOB, OBJ_BOB, team.getTeamId()).code());
    }

    // =====================================================================
    // 撤销分享钩子
    // =====================================================================

    @Test
    @DisplayName("踢人自动撤销：被踢成员分享给本团队的对象被撤回，别人分享的不受影响")
    void kickRevokesShares() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);
        creators.put(OBJ_ALICE, ALICE);
        creators.put(OBJ_OWNER, OWNER);

        assertTrue(actions.share(ALICE, OBJ_ALICE, team.getTeamId()).ok());
        assertTrue(actions.share(OWNER, OBJ_OWNER, team.getTeamId()).ok());

        assertEquals(ResultCode.MEMBER_KICKED, actions.kick(OWNER, false, team.getTeamId(), ALICE).code());

        assertFalse(shares.getTeamsOfObject(OBJ_ALICE).contains(team.getTeamId()),
                "被踢成员的分享应被撤销");
        assertTrue(shares.getTeamsOfObject(OBJ_OWNER).contains(team.getTeamId()),
                "别人的分享不应被误删");
    }

    @Test
    @DisplayName("退队自动撤销：成员主动退队后其分享被撤回")
    void leaveRevokesShares() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);
        creators.put(OBJ_ALICE, ALICE);
        assertTrue(actions.share(ALICE, OBJ_ALICE, team.getTeamId()).ok());

        assertEquals(ResultCode.LEFT_TEAM, actions.leave(ALICE, team.getTeamId()).code());
        assertFalse(shares.getTeamsOfObject(OBJ_ALICE).contains(team.getTeamId()));
    }

    @Test
    @DisplayName("解散团队：撤销指向该团队的全部分享")
    void disbandRevokesAllShares() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);
        creators.put(OBJ_ALICE, ALICE);
        creators.put(OBJ_BOB, BOB);
        assertTrue(actions.share(ALICE, OBJ_ALICE, team.getTeamId()).ok());
        assertTrue(actions.share(BOB, OBJ_BOB, team.getTeamId()).ok());

        assertEquals(ResultCode.TEAM_DISBANDED, actions.disband(OWNER, false, team.getTeamId()).code());
        assertFalse(shares.getTeamsOfObject(OBJ_ALICE).contains(team.getTeamId()));
        assertFalse(shares.getTeamsOfObject(OBJ_BOB).contains(team.getTeamId()));
    }
}
