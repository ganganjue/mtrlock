package com.mtrstar.lock.team;

import com.mtrstar.lock.command.CommandUtil;
import com.mtrstar.lock.perm.OwnershipData;

/**
 * 团队 / 分享操作的<b>唯一实现</b>（1.2.3）。
 *
 * <p>命令层（{@code TeamCommand}）与 GUI 层（{@code ServerGuiNetworking} /
 * {@code TeamGuiDispatcher}）都调用本类，因此“GUI 与命令行为一致”由结构保证，
 * 而不是靠两边各写一份再人工对齐。</p>
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>只做“操作者授权 + 调用数据层”，权限判定仍复用
 *       {@link TeamData} 的 actor 参数与 {@link CommandUtil} 的 id 校验；</li>
 *   <li>不碰 Minecraft / Fabric 类型（入参只收 UUID 字符串与 isAdmin 布尔），可纯 JVM 单测；</li>
 *   <li>不做任何文案拼装，只返回 {@link ActionResult}。</li>
 * </ul>
 *
 * <p>撤销分享：{@link TeamData#leaveTeam}/{@link TeamData#kickMember}/{@link TeamData#deleteTeam}
 * 内部已经挂了全局 {@link ShareData} 单例的撤销钩子；这里再对<b>注入的</b> {@code shares}
 * 调一次（幂等）。生产环境两者是同一个单例，第二次是空操作；单测里注入的 shares 才能被断言到。</p>
 */
public final class TeamActions {

    /** 对象创建者查询的最小接口（生产走 {@link OwnershipData}，测试注入桩）。 */
    @FunctionalInterface
    public interface CreatorLookup {

        /** @return 对象创建者 UUID；无归属记录返回 null */
        String getCreator(String objectId);
    }

    private final TeamData teams;
    private final ShareData shares;
    private final CreatorLookup creators;

    public TeamActions(TeamData teams, ShareData shares, CreatorLookup creators) {
        this.teams = teams;
        this.shares = shares;
        this.creators = creators;
    }

    /** 生产单例（延迟初始化，避免测试触碰 FabricLoader / OwnershipData）。 */
    private static volatile TeamActions production;

    public static TeamActions get() {
        TeamActions current = production;
        if (current == null) {
            synchronized (TeamActions.class) {
                current = production;
                if (current == null) {
                    current = new TeamActions(
                            TeamData.getInstance(),
                            ShareData.getInstance(),
                            objectId -> OwnershipData.getInstance().getCreator(objectId));
                    production = current;
                }
            }
        }
        return current;
    }

    // =====================================================================
    // 建队 / 申请 / 邀请
    // =====================================================================

    /** 创建团队（名字合法性 / 重名 / 每人上限都在 {@link TeamData} 里判）。 */
    public ActionResult createTeam(String actorUuid, String name) {
        final Team team = teams.createTeam(name, actorUuid);
        return team != null
                ? ActionResult.ok(ResultCode.TEAM_CREATED)
                : ActionResult.fail(ResultCode.TEAM_CREATE_FAILED);
    }

    /** 按<b>团队名</b>申请加入（与 {@code /team apply <名字>} 完全一致）。 */
    public ActionResult applyToJoin(String actorUuid, String teamName) {
        final Team team = teams.getTeamByName(teamName);
        if (team == null) {
            return ActionResult.fail(ResultCode.TEAM_NOT_FOUND);
        }
        return teams.applyToJoin(team.getTeamId(), actorUuid)
                ? ActionResult.ok(ResultCode.APPLY_SUBMITTED)
                : ActionResult.fail(ResultCode.APPLY_FAILED);
    }

    /** 批准入队申请（仅队长）。 */
    public ActionResult approveApplication(String actorUuid, String teamId, String applicantUuid) {
        return teams.approveApplication(teamId, actorUuid, applicantUuid)
                ? ActionResult.ok(ResultCode.APPLICATION_APPROVED)
                : ActionResult.fail(ResultCode.APPROVE_FAILED);
    }

    /** 拒绝入队申请（仅队长）。 */
    public ActionResult denyApplication(String actorUuid, String teamId, String applicantUuid) {
        return teams.denyApplication(teamId, actorUuid, applicantUuid)
                ? ActionResult.ok(ResultCode.APPLICATION_DENIED)
                : ActionResult.fail(ResultCode.DENY_FAILED);
    }

    /** 邀请玩家（仅队长）。 */
    public ActionResult invite(String actorUuid, String teamId, String targetUuid) {
        return teams.invite(teamId, actorUuid, targetUuid)
                ? ActionResult.ok(ResultCode.INVITATION_SENT)
                : ActionResult.fail(ResultCode.INVITE_FAILED);
    }

    /** 接受邀请（被邀请人本人）。 */
    public ActionResult acceptInvitation(String actorUuid, String teamId) {
        return teams.acceptInvitation(teamId, actorUuid)
                ? ActionResult.ok(ResultCode.INVITATION_ACCEPTED)
                : ActionResult.fail(ResultCode.JOIN_FAILED);
    }

    /** 拒绝邀请（被邀请人本人）。 */
    public ActionResult declineInvitation(String actorUuid, String teamId) {
        return teams.declineInvitation(teamId, actorUuid)
                ? ActionResult.ok(ResultCode.INVITATION_DECLINED)
                : ActionResult.fail(ResultCode.DECLINE_FAILED);
    }

    // =====================================================================
    // 退队 / 踢人 / 转让 / 解散
    // =====================================================================

    /** 主动退出（队长不能退；成功后撤销该玩家分享给本团队的对象）。 */
    public ActionResult leave(String actorUuid, String teamId) {
        final Team team = teams.getTeam(teamId);
        if (team == null) {
            return ActionResult.fail(ResultCode.TEAM_NOT_FOUND);
        }
        if (team.isOwner(actorUuid)) {
            return ActionResult.fail(ResultCode.OWNER_CANNOT_LEAVE);
        }
        if (!teams.leaveTeam(teamId, actorUuid)) {
            return ActionResult.fail(ResultCode.LEAVE_FAILED);
        }
        shares.revokeAllFromPlayer(teamId, actorUuid);
        return ActionResult.ok(ResultCode.LEFT_TEAM);
    }

    /** 踢人（队长或 OP 3+；成功后撤销被踢成员分享给本团队的对象）。 */
    public ActionResult kick(String actorUuid, boolean actorIsAdmin, String teamId, String targetUuid) {
        if (!teams.kickMember(teamId, actorUuid, actorIsAdmin, targetUuid)) {
            return ActionResult.fail(ResultCode.KICK_FAILED);
        }
        shares.revokeAllFromPlayer(teamId, targetUuid);
        return ActionResult.ok(ResultCode.MEMBER_KICKED);
    }

    /** 转让队长（队长或 OP 3+）。 */
    public ActionResult transfer(String actorUuid, boolean actorIsAdmin, String teamId, String targetUuid) {
        return teams.transferOwnership(teamId, actorUuid, actorIsAdmin, targetUuid)
                ? ActionResult.ok(ResultCode.OWNERSHIP_TRANSFERRED)
                : ActionResult.fail(ResultCode.TRANSFER_FAILED);
    }

    /** 解散团队（队长或 OP 3+；成功后清理指向该团队的全部分享）。 */
    public ActionResult disband(String actorUuid, boolean actorIsAdmin, String teamId) {
        final Team team = teams.getTeam(teamId);
        if (team == null) {
            return ActionResult.fail(ResultCode.TEAM_NOT_FOUND);
        }
        if (!team.isOwner(actorUuid) && !actorIsAdmin) {
            return ActionResult.fail(ResultCode.DISBAND_NO_PERMISSION);
        }
        if (!teams.deleteTeam(teamId)) {
            return ActionResult.fail(ResultCode.DISBAND_FAILED);
        }
        shares.revokeAllForTeam(teamId);
        return ActionResult.ok(ResultCode.TEAM_DISBANDED);
    }

    // =====================================================================
    // 分享
    // =====================================================================

    /** 分享对象给团队（只有对象创建者可以分享）。 */
    public ActionResult share(String actorUuid, String objectId, String teamId) {
        if (!CommandUtil.isValidObjectId(objectId)) {
            return ActionResult.fail(ResultCode.OBJECT_ID_INVALID);
        }
        final String creator = creators.getCreator(objectId);
        if (creator == null) {
            return ActionResult.fail(ResultCode.OBJECT_NO_OWNER);
        }
        if (!creator.equals(actorUuid)) {
            return ActionResult.fail(ResultCode.NOT_OBJECT_CREATOR);
        }
        return shares.share(objectId, teamId)
                ? ActionResult.ok(ResultCode.OBJECT_SHARED)
                : ActionResult.fail(ResultCode.ALREADY_SHARED);
    }

    /** 取消分享（只有对象创建者可以取消）。 */
    public ActionResult unshare(String actorUuid, String objectId, String teamId) {
        if (!CommandUtil.isValidObjectId(objectId)) {
            return ActionResult.fail(ResultCode.OBJECT_ID_INVALID);
        }
        final String creator = creators.getCreator(objectId);
        if (creator == null || !creator.equals(actorUuid)) {
            return ActionResult.fail(ResultCode.NOT_OBJECT_CREATOR);
        }
        return shares.unshare(objectId, teamId)
                ? ActionResult.ok(ResultCode.OBJECT_UNSHARED)
                : ActionResult.fail(ResultCode.NOT_SHARED);
    }
}
