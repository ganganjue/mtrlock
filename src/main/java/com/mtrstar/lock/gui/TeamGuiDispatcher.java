package com.mtrstar.lock.gui;

import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.team.ActionResult;
import com.mtrstar.lock.team.ResultCode;
import com.mtrstar.lock.team.TeamActions;

/**
 * 把 GUI 的 {@link TeamGuiAction} 映射到 {@link TeamActions}（1.2.3）。
 *
 * <p>这是“GUI 与命令行为一致”的关键：GUI 不自己实现任何权限 / 状态判断，
 * 只把玩家在界面上点的操作翻译成一次 {@link TeamActions} 调用；命令层调的是同一批方法。</p>
 *
 * <p>“按玩家名手动输入”的解析（名字 → 在线 UUID）需要服务端 PlayerManager，
 * 不在本类里做；服务端 handler 解析完把 {@code resolvedTargetUuid} 传进来，
 * 因此本类保持纯逻辑、可纯 JVM 单测。</p>
 */
public final class TeamGuiDispatcher {

    private TeamGuiDispatcher() {
    }

    /**
     * 执行一次 GUI 操作。
     *
     * @param actions            共享行为层（生产用 {@link TeamActions#get()}，测试注入实例）
     * @param actorUuid          操作者 UUID
     * @param actorIsAdmin       操作者是否 OP 3+
     * @param action             GUI 操作载荷
     * @param resolvedTargetUuid 服务端解析后的目标玩家 UUID；不需要目标时为 null
     * @return 操作结果
     */
    public static ActionResult dispatch(TeamActions actions,
                                        String actorUuid,
                                        boolean actorIsAdmin,
                                        TeamGuiAction action,
                                        String resolvedTargetUuid) {
        if (action == null || action.type() == null) {
            return ActionResult.fail(ResultCode.UNKNOWN_ACTION);
        }
        switch (action.type()) {
            case CREATE:
                return actions.createTeam(actorUuid, action.teamName());
            case APPLY:
                return actions.applyToJoin(actorUuid, action.teamName());
            case APPROVE:
                return actions.approveApplication(actorUuid, action.teamId(), resolvedTargetUuid);
            case DENY:
                return actions.denyApplication(actorUuid, action.teamId(), resolvedTargetUuid);
            case INVITE:
                return actions.invite(actorUuid, action.teamId(), resolvedTargetUuid);
            case ACCEPT:
                return actions.acceptInvitation(actorUuid, action.teamId());
            case DECLINE:
                return actions.declineInvitation(actorUuid, action.teamId());
            case LEAVE:
                return actions.leave(actorUuid, action.teamId());
            case KICK:
                return actions.kick(actorUuid, actorIsAdmin, action.teamId(), resolvedTargetUuid);
            case TRANSFER:
                return actions.transfer(actorUuid, actorIsAdmin, action.teamId(), resolvedTargetUuid);
            case DISBAND:
                return actions.disband(actorUuid, actorIsAdmin, action.teamId());
            case SHARE:
                return actions.share(actorUuid, action.objectId(), action.teamId());
            case UNSHARE:
                return actions.unshare(actorUuid, action.objectId(), action.teamId());
            case REQUEST_SYNC:
                return ActionResult.ok(ResultCode.SYNCED);
            default:
                return ActionResult.fail(ResultCode.UNKNOWN_ACTION);
        }
    }
}
