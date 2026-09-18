package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Team} / {@link TeamData} 的单元测试。
 *
 * <p>用包内可见构造 {@code new TeamData(Path)} 注入临时文件，不触碰 {@code FabricLoader}
 * （单例走 lazy holder，测试不会触发它）。每个测试方法拿到独立的 {@link TempDir}。</p>
 */
class TeamDataTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String ALICE = "22222222-2222-2222-2222-222222222222";
    private static final String BOB = "33333333-3333-3333-3333-333333333333";

    @TempDir
    Path tempDir;

    private TeamData data() {
        return new TeamData(tempDir.resolve("teams.json"));
    }

    private Path file() {
        return tempDir.resolve("teams.json");
    }

    /** 建一个团队并让 alice 成为成员的便捷方法（owner 邀请 → alice 接受）。 */
    private static void addMember(TeamData data, Team team, String owner, String member) {
        assertTrue(data.invite(team.getTeamId(), owner, member), "邀请应成功");
        assertTrue(data.acceptInvitation(team.getTeamId(), member), "接受邀请应成功");
    }

    // =====================================================================
    // 创建 / 查找 / 名字
    // =====================================================================

    @Test
    @DisplayName("创建团队：owner 自动入队；可按 id / 名字查找")
    void createAndFind() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);

        assertNotNull(team);
        assertNotNull(team.getTeamId());
        assertEquals("A队", team.getName());
        assertEquals(OWNER, team.getOwnerUuid());
        assertTrue(team.isOwner(OWNER));
        assertTrue(team.isMember(OWNER));
        assertTrue(team.getCreatedAt() > 0L);

        assertSame(team, data.getTeam(team.getTeamId()));
        assertSame(team, data.getTeamByName("A队"));
        assertEquals(1, data.size());
        assertNull(data.getTeam(null));
        assertNull(data.getTeamByName(null));
    }

    @Test
    @DisplayName("中文团队名可用")
    void chineseName() {
        final TeamData data = data();
        final Team team = data.createTeam("北京地铁运营组", OWNER);
        assertNotNull(team);
        assertEquals("北京地铁运营组", team.getName());
        assertSame(team, data.getTeamByName("北京地铁运营组"));
    }

    @Test
    @DisplayName("名字边界：空 / 全空白 / 控制字符 / 33 字符 → null；32 字符恰好接受")
    void nameBoundaries() {
        final TeamData data = data();

        assertNull(data.createTeam(null, OWNER));
        assertNull(data.createTeam("", OWNER));
        assertNull(data.createTeam("   ", OWNER));
        assertNull(data.createTeam("bad\u0000name", OWNER));
        assertNull(data.createTeam("bad\nname", OWNER));
        assertNull(data.createTeam("x".repeat(33), OWNER));

        final Team ok = data.createTeam("x".repeat(32), OWNER);
        assertNotNull(ok);
        assertEquals(32, ok.getName().length());
        assertEquals(1, data.size());
    }

    @Test
    @DisplayName("名字去首尾空白后存储 / 查找")
    void nameTrimmed() {
        final TeamData data = data();
        final Team team = data.createTeam("  A队  ", OWNER);
        assertNotNull(team);
        assertEquals("A队", team.getName());
        assertSame(team, data.getTeamByName("A队"));
    }

    @Test
    @DisplayName("名字重复：第二个创建失败")
    void duplicateName() {
        final TeamData data = data();
        assertNotNull(data.createTeam("A队", OWNER));
        assertNull(data.createTeam("A队", ALICE));
        assertEquals(1, data.size());
    }

    @Test
    @DisplayName("非法 owner：null / 空串 → null")
    void invalidOwner() {
        final TeamData data = data();
        assertNull(data.createTeam("A队", null));
        assertNull(data.createTeam("A队", ""));
        assertEquals(0, data.size());
    }

    // =====================================================================
    // 加入 / 退出 / 申请 / 邀请
    // =====================================================================

    @Test
    @DisplayName("申请-批准：批准前不是成员，批准后入队")
    void applyApprove() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();

        assertTrue(data.applyToJoin(id, ALICE));
        assertFalse(team.isMember(ALICE));
        assertTrue(team.getPendingApplications().contains(ALICE));

        assertTrue(data.approveApplication(id, OWNER, ALICE));
        assertTrue(team.isMember(ALICE));
        assertFalse(team.getPendingApplications().contains(ALICE));
    }

    @Test
    @DisplayName("申请-拒绝：拒绝后不入队，申请被清掉")
    void applyDeny() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();

        assertTrue(data.applyToJoin(id, ALICE));
        assertTrue(data.denyApplication(id, OWNER, ALICE));
        assertFalse(team.isMember(ALICE));
        assertFalse(team.getPendingApplications().contains(ALICE));
        assertFalse(data.denyApplication(id, OWNER, ALICE)); // 已经没有了
    }

    @Test
    @DisplayName("邀请-接受：接受后入队")
    void inviteAccept() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();

        assertTrue(data.invite(id, OWNER, ALICE));
        assertFalse(team.isMember(ALICE));
        assertTrue(team.getPendingInvitations().contains(ALICE));

        assertTrue(data.acceptInvitation(id, ALICE));
        assertTrue(team.isMember(ALICE));
        assertFalse(team.getPendingInvitations().contains(ALICE));
    }

    @Test
    @DisplayName("邀请-拒绝：拒绝后不入队，邀请被清掉")
    void inviteDecline() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();

        assertTrue(data.invite(id, OWNER, ALICE));
        assertTrue(data.declineInvitation(id, ALICE));
        assertFalse(team.isMember(ALICE));
        assertFalse(team.getPendingInvitations().contains(ALICE));
        assertFalse(data.declineInvitation(id, ALICE)); // 已经没有了
    }

    @Test
    @DisplayName("退出：普通成员可退，退出后不再是成员")
    void leave() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        addMember(data, team, OWNER, ALICE);

        assertTrue(data.leaveTeam(team.getTeamId(), ALICE));
        assertFalse(team.isMember(ALICE));
        assertFalse(data.leaveTeam(team.getTeamId(), ALICE)); // 已经不在
    }

    @Test
    @DisplayName("重复申请 / 重复邀请 / 已在团队 → 全部拒绝")
    void duplicateApplyInvite() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();

        assertTrue(data.applyToJoin(id, ALICE));
        assertFalse(data.applyToJoin(id, ALICE), "重复申请应被拒");

        assertTrue(data.invite(id, OWNER, BOB));
        assertFalse(data.invite(id, OWNER, BOB), "重复邀请应被拒");

        // owner 已在团队 → 不能再申请 / 被邀请
        assertFalse(data.applyToJoin(id, OWNER));
        assertFalse(data.invite(id, OWNER, OWNER));

        // 已是成员也不能再被邀请 / 申请
        assertTrue(data.acceptInvitation(id, BOB));
        assertFalse(data.invite(id, OWNER, BOB));
        assertFalse(data.applyToJoin(id, BOB));
    }

    // =====================================================================
    // 每玩家团队上限
    // =====================================================================

    @Test
    @DisplayName("一个玩家最多同时在 3 个团队：第 4 个创建失败")
    void maxTeamsPerPlayerOnCreate() {
        final TeamData data = data();

        assertNotNull(data.createTeam("T1", OWNER));
        assertNotNull(data.createTeam("T2", OWNER));
        assertNotNull(data.createTeam("T3", OWNER));
        assertNull(data.createTeam("T4", OWNER), "第 4 个团队应被拒");

        assertEquals(3, data.getTeamsOfPlayer(OWNER).size());
        assertEquals(3, data.size());
    }

    @Test
    @DisplayName("达到上限后：批准申请 / 接受邀请也会失败（挂起记录保留）")
    void limitBlocksApproveAndAccept() {
        final TeamData data = data();
        final Team t1 = data.createTeam("T1", OWNER);
        final Team t2 = data.createTeam("T2", OWNER);
        final Team t3 = data.createTeam("T3", OWNER);
        final Team t4 = data.createTeam("T4", ALICE); // ALICE 的第 1 个

        // ALICE 依次加入 t1 / t2，凑满 3 个
        assertTrue(data.applyToJoin(t1.getTeamId(), ALICE));
        assertTrue(data.approveApplication(t1.getTeamId(), OWNER, ALICE));
        assertTrue(data.invite(t2.getTeamId(), OWNER, ALICE));
        assertTrue(data.acceptInvitation(t2.getTeamId(), ALICE));
        assertEquals(3, data.getTeamsOfPlayer(ALICE).size());

        // 申请可以登记，但批准被上限挡住；申请保留
        assertTrue(data.applyToJoin(t3.getTeamId(), ALICE));
        assertFalse(data.approveApplication(t3.getTeamId(), OWNER, ALICE));
        assertFalse(t3.isMember(ALICE));
        assertTrue(t3.getPendingApplications().contains(ALICE));

        // 邀请可以登记，但接受被上限挡住；邀请保留
        assertTrue(data.invite(t3.getTeamId(), OWNER, ALICE));
        assertFalse(data.acceptInvitation(t3.getTeamId(), ALICE));
        assertFalse(t3.isMember(ALICE));
        assertTrue(t3.getPendingInvitations().contains(ALICE));

        assertNotNull(t4); // 避免“未使用”噪声
    }

    @Test
    @DisplayName("getTeamsOfPlayer：owner 身份与成员身份都算")
    void teamsOfPlayer() {
        final TeamData data = data();
        final Team t1 = data.createTeam("T1", OWNER);
        final Team t2 = data.createTeam("T2", OWNER);
        final Team t3 = data.createTeam("T3", ALICE);
        addMember(data, t3, ALICE, OWNER);

        assertEquals(3, data.getTeamsOfPlayer(OWNER).size());
        assertTrue(data.getTeamsOfPlayer(OWNER).contains(t1));
        assertTrue(data.getTeamsOfPlayer(OWNER).contains(t2));
        assertTrue(data.getTeamsOfPlayer(OWNER).contains(t3));

        assertEquals(1, data.getTeamsOfPlayer(ALICE).size());
        assertTrue(data.getTeamsOfPlayer(ALICE).contains(t3));
        assertTrue(data.getTeamsOfPlayer(null).isEmpty());
        assertTrue(data.getTeamsOfPlayer(BOB).isEmpty());
    }

    // =====================================================================
    // 删除 / 改名 / 转让
    // =====================================================================

    @Test
    @DisplayName("删除团队：按 id 删除并清掉名字索引")
    void deleteTeam() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();

        assertTrue(data.deleteTeam(id));
        assertNull(data.getTeam(id));
        assertNull(data.getTeamByName("A队"));
        assertFalse(data.deleteTeam(id), "重复删除应返回 false");
        assertTrue(data.getTeamsOfPlayer(OWNER).isEmpty());
        assertEquals(0, data.size());
    }

    @Test
    @DisplayName("改名：owner 可改、名字索引同步；重名 / 非法名 / 非 owner 拒绝")
    void rename() {
        final TeamData data = data();
        final Team t1 = data.createTeam("A队", OWNER);
        final Team t2 = data.createTeam("B队", ALICE);
        final String id1 = t1.getTeamId();

        assertTrue(data.rename(id1, OWNER, "新队名"));
        assertEquals("新队名", t1.getName());
        assertNull(data.getTeamByName("A队"));
        assertSame(t1, data.getTeamByName("新队名"));

        assertFalse(data.rename(id1, OWNER, "B队"), "与其它团队重名应拒绝");
        assertEquals("新队名", t1.getName());

        assertFalse(data.rename(id1, OWNER, ""), "非法名字应拒绝");
        assertFalse(data.rename(id1, ALICE, "别名"), "非 owner 应拒绝");
        assertEquals("新队名", t1.getName());

        assertNotNull(t2);
    }

    @Test
    @DisplayName("owner 转让：目标须是成员；非 owner 不能转让；原 owner 保留成员身份")
    void transferOwnership() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();
        addMember(data, team, OWNER, ALICE);

        assertFalse(data.transferOwnership(id, ALICE, BOB), "非 owner 转让应拒绝");
        assertFalse(data.transferOwnership(id, OWNER, BOB), "目标不是成员应拒绝");
        assertFalse(data.transferOwnership(id, OWNER, OWNER), "转给自己应拒绝");

        assertTrue(data.transferOwnership(id, OWNER, ALICE));
        assertTrue(team.isOwner(ALICE));
        assertFalse(team.isOwner(OWNER));
        assertTrue(team.isMember(OWNER), "原 owner 应保留成员身份");
        assertTrue(team.isMember(ALICE));
    }

    // =====================================================================
    // 授权边界
    // =====================================================================

    @Test
    @DisplayName("非 owner 操作：invite / approve / deny / rename / transfer / kick 全部拒绝")
    void nonOwnerOperationsRejected() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();
        addMember(data, team, OWNER, ALICE);

        assertTrue(data.applyToJoin(id, BOB));

        assertFalse(data.approveApplication(id, ALICE, BOB), "非 owner 不能批准");
        assertFalse(data.denyApplication(id, ALICE, BOB), "非 owner 不能拒绝");
        assertFalse(data.invite(id, ALICE, BOB), "非 owner 不能邀请");
        assertFalse(data.rename(id, ALICE, "X队"), "非 owner 不能改名");
        assertFalse(data.transferOwnership(id, ALICE, BOB), "非 owner 不能转让");
        assertFalse(data.kickMember(id, ALICE, BOB), "非 owner 不能踢人");

        // 这些操作都不应改变状态
        assertEquals("A队", team.getName());
        assertEquals(OWNER, team.getOwnerUuid());
        assertTrue(team.getPendingApplications().contains(BOB));
        assertFalse(team.isMember(BOB));
    }

    @Test
    @DisplayName("owner 不能直接退出，也不能被移除 / 被踢")
    void ownerCannotBeRemoved() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();
        addMember(data, team, OWNER, ALICE);

        assertFalse(data.leaveTeam(id, OWNER), "owner 不能直接退出");
        assertFalse(data.kickMember(id, OWNER, OWNER), "owner 不能被踢");
        assertFalse(team.removeMember(OWNER), "Team.removeMember 也不能移除 owner");
        assertTrue(team.isOwner(OWNER));
        assertTrue(team.isMember(OWNER));
    }

    @Test
    @DisplayName("踢人：owner 可踢普通成员")
    void kickMember() {
        final TeamData data = data();
        final Team team = data.createTeam("A队", OWNER);
        final String id = team.getTeamId();
        addMember(data, team, OWNER, ALICE);

        assertTrue(data.kickMember(id, OWNER, ALICE));
        assertFalse(team.isMember(ALICE));
        assertFalse(data.kickMember(id, OWNER, ALICE), "已不在团队");
    }

    @Test
    @DisplayName("不存在的 teamId：所有操作安全返回 false")
    void unknownTeamId() {
        final TeamData data = data();
        assertNull(data.getTeam("nope"));
        assertFalse(data.applyToJoin("nope", ALICE));
        assertFalse(data.approveApplication("nope", OWNER, ALICE));
        assertFalse(data.invite("nope", OWNER, ALICE));
        assertFalse(data.acceptInvitation("nope", ALICE));
        assertFalse(data.leaveTeam("nope", ALICE));
        assertFalse(data.deleteTeam("nope"));
        assertFalse(data.rename("nope", OWNER, "X"));
        assertFalse(data.transferOwnership("nope", OWNER, ALICE));
        assertFalse(data.addMember("nope", ALICE));
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    @Test
    @DisplayName("持久化往返：save → load 保留团队 / 成员 / 申请 / 邀请 / owner")
    void persistenceRoundTrip() throws Exception {
        final Path f = file();

        final TeamData first = new TeamData(f);
        final Team t1 = first.createTeam("甲队", OWNER);
        final Team t2 = first.createTeam("乙队", ALICE);
        addMember(first, t1, OWNER, ALICE);   // 邀请→接受
        first.applyToJoin(t2.getTeamId(), BOB); // 挂起申请
        first.invite(t2.getTeamId(), ALICE, OWNER); // 挂起邀请
        first.save();

        assertTrue(Files.exists(f));
        assertTrue(Files.size(f) > 0L);

        final TeamData second = new TeamData(f);
        second.load();
        assertFalse(second.hasLoadFailed());
        assertEquals(2, second.size());

        final Team loaded1 = second.getTeam(t1.getTeamId());
        assertNotNull(loaded1);
        assertEquals("甲队", loaded1.getName());
        assertEquals(OWNER, loaded1.getOwnerUuid());
        assertTrue(loaded1.isMember(OWNER));
        assertTrue(loaded1.isMember(ALICE));
        assertSame(loaded1, second.getTeamByName("甲队"));

        final Team loaded2 = second.getTeam(t2.getTeamId());
        assertNotNull(loaded2);
        assertEquals(ALICE, loaded2.getOwnerUuid());
        assertTrue(loaded2.getPendingApplications().contains(BOB));
        assertTrue(loaded2.getPendingInvitations().contains(OWNER));
    }

    @Test
    @DisplayName("坏文件保护：load 失败置 loadFailed；之后 save 不覆盖坏文件")
    void badFileProtection() throws Exception {
        final Path f = file();
        final String corrupt = "{ this is not valid json";
        Files.writeString(f, corrupt, StandardCharsets.UTF_8);

        final TeamData data = new TeamData(f);
        data.load();
        assertTrue(data.hasLoadFailed(), "损坏文件后 loadFailed 应为 true");

        // 故意往内存塞数据，证明“跳过保存”不是因为内存为空
        assertNotNull(data.createTeam("A队", OWNER));
        data.save();

        assertEquals(corrupt, Files.readString(f, StandardCharsets.UTF_8),
                "load 失败后 save 不得覆盖坏文件");
    }

    @Test
    @DisplayName("文件不存在：load 重置 loadFailed；之后 save 正常写盘")
    void missingFileResetsFlag() throws Exception {
        final Path f = file();
        Files.writeString(f, "{ bad json", StandardCharsets.UTF_8);

        final TeamData data = new TeamData(f);
        data.load();
        assertTrue(data.hasLoadFailed());

        Files.delete(f);
        data.load();
        assertFalse(data.hasLoadFailed());

        assertNotNull(data.createTeam("A队", OWNER));
        data.save();
        assertTrue(Files.exists(f));
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains("A队"));
    }

    @Test
    @DisplayName("加载清理：null 集合 / 重名 / 非法名字条目被安全处理")
    void loadCleansUpDirtyData() throws Exception {
        final Path f = file();
        // 手工写一份“脏”JSON：team1 缺 members，team2 名字非法，team3 与 team1 重名
        Files.writeString(f,
                "{"
                        + "\"id-1\":{\"teamId\":\"id-1\",\"name\":\"甲队\",\"ownerUuid\":\"owner-1\",\"createdAt\":1},"
                        + "\"id-2\":{\"teamId\":\"id-2\",\"name\":\"\",\"ownerUuid\":\"owner-2\",\"members\":[\"owner-2\"],\"createdAt\":2},"
                        + "\"id-3\":{\"teamId\":\"id-3\",\"name\":\"甲队\",\"ownerUuid\":\"owner-3\",\"members\":[\"owner-3\"],\"createdAt\":3}"
                        + "}",
                StandardCharsets.UTF_8);

        final TeamData data = new TeamData(f);
        data.load();
        assertFalse(data.hasLoadFailed());

        // id-2 名字非法被跳过；id-3 与 id-1 重名，保留先出现的 id-1
        assertEquals(2, data.size());
        final Team t1 = data.getTeam("id-1");
        assertNotNull(t1);
        assertTrue(t1.isMember("owner-1"), "缺失的 members 应补上 owner");
        assertSame(t1, data.getTeamByName("甲队"));
        assertNull(data.getTeam("id-2"));
        assertNotNull(data.getTeam("id-3"), "id-3 仍在内存（只是不进名字索引）");
    }
}
