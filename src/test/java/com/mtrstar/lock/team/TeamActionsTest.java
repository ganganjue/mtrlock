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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TeamActions} 的单元测试（1.2.3）。
 *
 * <p>覆盖 GUI action 权限矩阵：无权限 / 非成员 / 非队长 / OP 3+；
 * 并断言每个结果码，作为“GUI 与命令行为一致”的共同基准。</p>
 *
 * <p>纯 JVM：用包内可见构造注入临时文件的 {@link TeamData} / {@link ShareData}，
 * 不触碰 FabricLoader 单例。</p>
 */
class TeamActionsTest {

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
        teams = new TeamData(tempDir.resolve("teams.json"));
        shares = new ShareData(tempDir.resolve("shares.json"), creators::get,
                teamId -> teams.getTeam(teamId) != null);
        actions = new TeamActions(teams, shares, creators::get);
    }

    private Team newTeam(String name, String owner) {
        final Team team = teams.createTeam(name, owner);
        assertNotNull(team, "建队应成功");
        return team;
    }

    private void addMember(Team team, String owner, String member) {
        assertTrue(actions.invite(owner, team.getTeamId(), member).ok(), "邀请应成功");
        assertTrue(actions.acceptInvitation(member, team.getTeamId()).ok(), "接受邀请应成功");
    }

    // =====================================================================
    // 建队
    // =====================================================================

    @Test
    @DisplayName("创建团队：成功 → TEAM_CREATED；重名 / 非法名 → TEAM_CREATE_FAILED")
    void createTeam() {
        assertTrue(actions.createTeam(OWNER, "A队").ok());
        assertEquals(ResultCode.TEAM_CREATED, actions.createTeam(OWNER, "B队").code());
        assertEquals(ResultCode.TEAM_CREATE_FAILED, actions.createTeam(ALICE, "A队").code());
        assertEquals(ResultCode.TEAM_CREATE_FAILED, actions.createTeam(ALICE, "   ").code());
    }

    @Test
    @DisplayName("创建团队：每人最多 3 个，第 4 个 → TEAM_CREATE_FAILED")
    void createTeamLimit() {
        assertTrue(actions.createTeam(OWNER, "T1").ok());
        assertTrue(actions.createTeam(OWNER, "T2").ok());
        assertTrue(actions.createTeam(OWNER, "T3").ok());
        assertEquals(ResultCode.TEAM_CREATE_FAILED, actions.createTeam(OWNER, "T4").code());
    }

    // =====================================================================
    // 申请 / 批准 / 拒绝
    // =====================================================================

    @Test
    @DisplayName("申请加入：成功 → APPLY_SUBMITTED；重复 / 已成员 → APPLY_FAILED；团队不存在")
    void applyToJoin() {
        final Team team = newTeam("A队", OWNER);
        assertEquals(ResultCode.APPLY_SUBMITTED, actions.applyToJoin(ALICE, "A队").code());
        assertEquals(ResultCode.APPLY_FAILED, actions.applyToJoin(ALICE, "A队").code());
        assertEquals(ResultCode.APPLY_FAILED, actions.applyToJoin(OWNER, "A队").code());
        assertEquals(ResultCode.TEAM_NOT_FOUND, actions.applyToJoin(ALICE, "不存在").code());
        assertTrue(team.getPendingApplications().contains(ALICE));
    }

    @Test
    @DisplayName("权限矩阵：批准 / 拒绝只能由队长（非队长、非成员、普通成员都失败）")
    void approveDenyPermissionMatrix() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);
        assertTrue(actions.applyToJoin(BOB, "A队").ok());

        // 非队长（普通成员 / 非成员）不能批准
        assertEquals(ResultCode.APPROVE_FAILED, actions.approveApplication(ALICE, team.getTeamId(), BOB).code());
        assertEquals(ResultCode.APPROVE_FAILED, actions.approveApplication("路人", team.getTeamId(), BOB).code());
        // 队长可以批准
        assertEquals(ResultCode.APPLICATION_APPROVED, actions.approveApplication(OWNER, team.getTeamId(), BOB).code());

        // 拒绝：非队长失败
        final String CAROL = "44444444-4444-4444-4444-444444444444";
        assertTrue(actions.applyToJoin(CAROL, "A队").ok());
        assertEquals(ResultCode.DENY_FAILED, actions.denyApplication(ALICE, team.getTeamId(), CAROL).code());
        assertEquals(ResultCode.APPLICATION_DENIED, actions.denyApplication(OWNER, team.getTeamId(), CAROL).code());
    }

    // =====================================================================
    // 邀请 / 接受 / 拒绝
    // =====================================================================

    @Test
    @DisplayName("邀请：队长成功 → INVITATION_SENT；普通成员 / 非成员 → INVITE_FAILED")
    void invitePermissionMatrix() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);

        assertEquals(ResultCode.INVITE_FAILED, actions.invite(ALICE, team.getTeamId(), BOB).code());
        assertEquals(ResultCode.INVITE_FAILED, actions.invite("路人", team.getTeamId(), BOB).code());
        assertEquals(ResultCode.INVITATION_SENT, actions.invite(OWNER, team.getTeamId(), BOB).code());
        // 重复邀请 / 已是成员 → 失败
        assertEquals(ResultCode.INVITE_FAILED, actions.invite(OWNER, team.getTeamId(), BOB).code());
        assertEquals(ResultCode.INVITE_FAILED, actions.invite(OWNER, team.getTeamId(), ALICE).code());
    }

    @Test
    @DisplayName("接受 / 拒绝邀请：本人成功；无邀请 / 非本人 → 失败")
    void acceptDeclineInvitation() {
        final Team team = newTeam("A队", OWNER);
        assertTrue(actions.invite(OWNER, team.getTeamId(), ALICE).ok());
        assertEquals(ResultCode.INVITATION_ACCEPTED, actions.acceptInvitation(ALICE, team.getTeamId()).code());
        assertTrue(team.isMember(ALICE));

        assertTrue(actions.invite(OWNER, team.getTeamId(), BOB).ok());
        assertEquals(ResultCode.INVITATION_DECLINED, actions.declineInvitation(BOB, team.getTeamId()).code());
        assertEquals(ResultCode.DECLINE_FAILED, actions.declineInvitation(BOB, team.getTeamId()).code());
    }

    // =====================================================================
    // 退队 / 踢人 / 转让 / 解散
    // =====================================================================

    @Test
    @DisplayName("退队：成员成功；队长 → OWNER_CANNOT_LEAVE；非成员 → LEAVE_FAILED")
    void leaveTeam() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);

        assertEquals(ResultCode.OWNER_CANNOT_LEAVE, actions.leave(OWNER, team.getTeamId()).code());
        assertEquals(ResultCode.LEAVE_FAILED, actions.leave(BOB, team.getTeamId()).code());
        assertEquals(ResultCode.LEFT_TEAM, actions.leave(ALICE, team.getTeamId()).code());
        assertFalse(team.isMember(ALICE));
    }

    @Test
    @DisplayName("踢人矩阵：队长可踢；普通成员不可；OP3+ 可；不能踢队长 / 自己")
    void kickPermissionMatrix() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);
        addMember(team, OWNER, BOB);

        assertEquals(ResultCode.KICK_FAILED, actions.kick(ALICE, false, team.getTeamId(), BOB).code());
        assertEquals(ResultCode.KICK_FAILED, actions.kick(OWNER, false, team.getTeamId(), OWNER).code());
        assertEquals(ResultCode.KICK_FAILED, actions.kick(ALICE, false, team.getTeamId(), ALICE).code());

        // OP3+ 兜底：非队长也能踢
        assertEquals(ResultCode.MEMBER_KICKED, actions.kick(ALICE, true, team.getTeamId(), BOB).code());
        assertFalse(team.isMember(BOB));

        assertEquals(ResultCode.MEMBER_KICKED, actions.kick(OWNER, false, team.getTeamId(), ALICE).code());
    }

    @Test
    @DisplayName("转让矩阵：队长可转给成员；普通成员不可；OP3+ 可；目标非成员 / 自己失败")
    void transferPermissionMatrix() {
        final Team team = newTeam("A队", OWNER);
        addMember(team, OWNER, ALICE);
        addMember(team, OWNER, BOB);

        assertEquals(ResultCode.TRANSFER_FAILED, actions.transfer(ALICE, false, team.getTeamId(), BOB).code());
        assertEquals(ResultCode.TRANSFER_FAILED, actions.transfer(OWNER, false, team.getTeamId(), "路人").code());
        assertEquals(ResultCode.TRANSFER_FAILED, actions.transfer(OWNER, false, team.getTeamId(), OWNER).code());

        // OP3+ 兜底
        assertEquals(ResultCode.OWNERSHIP_TRANSFERRED, actions.transfer(ALICE, true, team.getTeamId(), BOB).code());
        assertTrue(team.isOwner(BOB));
        assertTrue(team.isMember(OWNER), "原队长应保留成员身份");
    }

    @Test
    @DisplayName("解散矩阵：队长可解散；普通成员 → DISBAND_NO_PERMISSION；OP3+ 可")
    void disbandPermissionMatrix() {
        final Team a = newTeam("A队", OWNER);
        addMember(a, OWNER, ALICE);
        assertEquals(ResultCode.DISBAND_NO_PERMISSION, actions.disband(ALICE, false, a.getTeamId()).code());
        assertEquals(ResultCode.TEAM_DISBANDED, actions.disband(OWNER, false, a.getTeamId()).code());
        assertEquals(0, teams.size());

        final Team b = newTeam("B队", OWNER);
        addMember(b, OWNER, ALICE);
        assertEquals(ResultCode.TEAM_DISBANDED, actions.disband(ALICE, true, b.getTeamId()).code());
    }

    @Test
    @DisplayName("团队不存在：leave / disband 都返回 TEAM_NOT_FOUND")
    void unknownTeam() {
        assertEquals(ResultCode.TEAM_NOT_FOUND, actions.leave(ALICE, "no-such-team").code());
        assertEquals(ResultCode.TEAM_NOT_FOUND, actions.disband(ALICE, true, "no-such-team").code());
    }

    // =====================================================================
    // 与数据层直调一致性
    // =====================================================================

    @Test
    @DisplayName("GUI action 与命令操作结果一致：同一操作重复调用结果码相同")
    void guiAndCommandAgree() {
        final Team team = newTeam("A队", OWNER);
        // 命令路径（也是 TeamActions）与 GUI 路径（同一个 TeamActions）在数据层完全同源
        final ActionResult first = actions.applyToJoin(ALICE, "A队");
        final ActionResult second = actions.applyToJoin(ALICE, "A队");
        assertEquals(ResultCode.APPLY_SUBMITTED, first.code());
        assertEquals(ResultCode.APPLY_FAILED, second.code());
        assertEquals(0, first.code().compareTo(ResultCode.APPLY_SUBMITTED));
        assertTrue(team.getPendingApplications().contains(ALICE));
    }
}
