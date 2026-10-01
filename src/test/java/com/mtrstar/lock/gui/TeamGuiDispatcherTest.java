package com.mtrstar.lock.gui;

import com.mtrstar.lock.network.GuiProtocol;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.team.ActionResult;
import com.mtrstar.lock.team.ResultCode;
import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamActions;
import com.mtrstar.lock.team.TeamData;
import com.mtrstar.lock.team.TeamTestSupport;
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
 * {@link TeamGuiDispatcher} 单元测试（1.2.3）。
 *
 * <p>验证 GUI action 与命令路径（同一个 {@link TeamActions}）结果一致，
 * 并覆盖权限矩阵中的关键分支。</p>
 */
class TeamGuiDispatcherTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String ALICE = "22222222-2222-2222-2222-222222222222";
    private static final String BOB = "33333333-3333-3333-3333-333333333333";

    @TempDir
    Path tempDir;

    private TeamData teams;
    private ShareData shares;
    private TeamActions actions;
    private final Map<String, String> creators = new HashMap<>();

    @BeforeEach
    void setUp() {
        teams = TeamTestSupport.teamData(tempDir.resolve("teams.json"));
        shares = TeamTestSupport.shareData(tempDir.resolve("shares.json"), creators::get,
                teamId -> teams.getTeam(teamId) != null);
        actions = new TeamActions(teams, shares, creators::get);
    }

    private ActionResult gui(String actor, boolean admin, TeamActionType type,
                             String teamId, String teamName, String target, String objectId) {
        final TeamGuiAction action = TeamGuiAction.of(type, teamId, teamName, target, null, objectId);
        return TeamGuiDispatcher.dispatch(actions, actor, admin, action, target);
    }

    @Test
    @DisplayName("CREATE / APPLY / ACCEPT / DECLINE 映射正确")
    void createApplyAcceptDecline() {
        assertEquals(ResultCode.TEAM_CREATED, gui(OWNER, false, TeamActionType.CREATE, null, "A队", null, null).code());
        final Team team = teams.getTeamByName("A队");

        assertEquals(ResultCode.APPLY_SUBMITTED, gui(ALICE, false, TeamActionType.APPLY, null, "A队", null, null).code());
        assertEquals(ResultCode.APPLICATION_APPROVED,
                gui(OWNER, false, TeamActionType.APPROVE, team.getTeamId(), null, ALICE, null).code());

        assertTrue(gui(OWNER, false, TeamActionType.INVITE, team.getTeamId(), null, BOB, null).ok());
        assertEquals(ResultCode.INVITATION_ACCEPTED,
                gui(BOB, false, TeamActionType.ACCEPT, team.getTeamId(), null, null, null).code());

        assertTrue(gui(OWNER, false, TeamActionType.INVITE, team.getTeamId(), null, "carol-uuid", null).ok());
        assertEquals(ResultCode.INVITATION_DECLINED,
                gui("carol-uuid", false, TeamActionType.DECLINE, team.getTeamId(), null, null, null).code());
    }

    @Test
    @DisplayName("权限矩阵：非队长 KICK / TRANSFER / DISBAND 失败，OP3+ 兜底成功")
    void permissionMatrix() {
        final Team team = teams.createTeam("A队", OWNER);
        assertTrue(actions.invite(OWNER, team.getTeamId(), ALICE).ok());
        assertTrue(actions.acceptInvitation(ALICE, team.getTeamId()).ok());
        assertTrue(actions.invite(OWNER, team.getTeamId(), BOB).ok());
        assertTrue(actions.acceptInvitation(BOB, team.getTeamId()).ok());

        // 非队长
        assertEquals(ResultCode.KICK_FAILED, gui(ALICE, false, TeamActionType.KICK, team.getTeamId(), null, BOB, null).code());
        assertEquals(ResultCode.TRANSFER_FAILED, gui(ALICE, false, TeamActionType.TRANSFER, team.getTeamId(), null, BOB, null).code());
        assertEquals(ResultCode.DISBAND_NO_PERMISSION, gui(ALICE, false, TeamActionType.DISBAND, team.getTeamId(), null, null, null).code());

        // OP3+ 兜底
        assertEquals(ResultCode.MEMBER_KICKED, gui(ALICE, true, TeamActionType.KICK, team.getTeamId(), null, BOB, null).code());
        assertEquals(ResultCode.OWNERSHIP_TRANSFERRED, gui(ALICE, true, TeamActionType.TRANSFER, team.getTeamId(), null, ALICE, null).code());
        assertEquals(ResultCode.TEAM_DISBANDED, gui(ALICE, true, TeamActionType.DISBAND, team.getTeamId(), null, null, null).code());
    }

    @Test
    @DisplayName("LEAVE：队长 → OWNER_CANNOT_LEAVE；成员成功")
    void leave() {
        final Team team = teams.createTeam("A队", OWNER);
        assertTrue(actions.invite(OWNER, team.getTeamId(), ALICE).ok());
        assertTrue(actions.acceptInvitation(ALICE, team.getTeamId()).ok());

        assertEquals(ResultCode.OWNER_CANNOT_LEAVE, gui(OWNER, false, TeamActionType.LEAVE, team.getTeamId(), null, null, null).code());
        assertEquals(ResultCode.LEFT_TEAM, gui(ALICE, false, TeamActionType.LEAVE, team.getTeamId(), null, null, null).code());
    }

    @Test
    @DisplayName("SHARE / UNSHARE 映射与授权正确")
    void shareUnshare() {
        final Team team = teams.createTeam("A队", OWNER);
        final String objectId = "route:00000000000000AA";
        creators.put(objectId, OWNER);

        assertEquals(ResultCode.NOT_OBJECT_CREATOR,
                gui(ALICE, false, TeamActionType.SHARE, team.getTeamId(), null, null, objectId).code());
        assertEquals(ResultCode.OBJECT_SHARED,
                gui(OWNER, false, TeamActionType.SHARE, team.getTeamId(), null, null, objectId).code());
        assertEquals(ResultCode.OBJECT_UNSHARED,
                gui(OWNER, false, TeamActionType.UNSHARE, team.getTeamId(), null, null, objectId).code());
    }

    @Test
    @DisplayName("REQUEST_SYNC → SYNCED，且不改数据")
    void requestSync() {
        assertEquals(ResultCode.SYNCED, gui(ALICE, false, TeamActionType.REQUEST_SYNC, null, null, null, null).code());
        assertEquals(0, teams.size());
    }

    @Test
    @DisplayName("null action / null type → UNKNOWN_ACTION")
    void unknownAction() {
        assertEquals(ResultCode.UNKNOWN_ACTION,
                TeamGuiDispatcher.dispatch(actions, ALICE, false, null, null).code());
        final TeamGuiAction bad = new TeamGuiAction(GuiProtocol.VERSION, null, null, null, null, null, null);
        assertEquals(ResultCode.UNKNOWN_ACTION,
                TeamGuiDispatcher.dispatch(actions, ALICE, false, bad, null).code());
    }

    @Test
    @DisplayName("GUI 与命令一致：同一操作经 dispatcher 与直调 TeamActions 结果码相同")
    void guiMatchesCommand() {
        assertEquals(ResultCode.TEAM_CREATED,
                gui(OWNER, false, TeamActionType.CREATE, null, "A队", null, null).code());
        final Team team = teams.getTeamByName("A队");
        assertFalse(team == null);

        // dispatcher 路径
        final ActionResult viaGui = gui(ALICE, false, TeamActionType.APPLY, null, "A队", null, null);
        // 命令路径（同一个 TeamActions 方法）
        final ActionResult viaCommand = actions.applyToJoin(BOB, "A队");
        assertEquals(ResultCode.APPLY_SUBMITTED, viaGui.code());
        assertEquals(ResultCode.APPLY_SUBMITTED, viaCommand.code());
        assertEquals(viaGui.ok(), viaCommand.ok());
    }
}
