package com.mtrstar.lock.team;

/**
 * 团队 / 分享 / 称号操作的<b>结果码</b>。
 *
 * <p>1.2.3 起，团队与分享的全部操作逻辑集中在 {@link TeamActions}，称号集中在
 * {@link TitleActions}；命令层与 GUI 层都只依赖这里的结果码，再各自决定怎么呈现
 * （命令用 {@link ResultMessages#zh(ResultCode)} 保持与旧版逐字一致的中文提示，
 * GUI 用 {@link ResultMessages#langKey(ResultCode)} 走 lang 文件）。</p>
 *
 * <p>本枚举<b>纯数据</b>：不依赖 Minecraft / Fabric，可纯 JVM 单测。</p>
 */
public enum ResultCode {

    // ------------------------------------------------------------------
    // 成功
    // ------------------------------------------------------------------

    /** 团队创建成功。 */
    TEAM_CREATED,
    /** 入队申请已提交。 */
    APPLY_SUBMITTED,
    /** 入队申请已批准。 */
    APPLICATION_APPROVED,
    /** 入队申请已拒绝。 */
    APPLICATION_DENIED,
    /** 邀请已发出。 */
    INVITATION_SENT,
    /** 邀请已接受（加入成功）。 */
    INVITATION_ACCEPTED,
    /** 邀请已拒绝。 */
    INVITATION_DECLINED,
    /** 已退出团队。 */
    LEFT_TEAM,
    /** 成员已被踢出。 */
    MEMBER_KICKED,
    /** 队长已转让。 */
    OWNERSHIP_TRANSFERRED,
    /** 团队已解散。 */
    TEAM_DISBANDED,
    /** 对象已分享给团队。 */
    OBJECT_SHARED,
    /** 已取消对象对团队的分享。 */
    OBJECT_UNSHARED,
    /** 只读同步请求已完成（未改数据）。 */
    SYNCED,
    /** 称号已设置。 */
    TITLE_SET,
    /** 称号已清除。 */
    TITLE_CLEARED,

    // ------------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------------

    /** 创建团队失败（名字非法 / 重名 / 已达团队上限）。 */
    TEAM_CREATE_FAILED,
    /** 申请失败（已是成员 / 已申请过）。 */
    APPLY_FAILED,
    /** 批准失败（非队长 / 无待批申请 / 对方达上限）。 */
    APPROVE_FAILED,
    /** 拒绝失败（非队长 / 无待批申请）。 */
    DENY_FAILED,
    /** 邀请失败（非队长 / 已是成员 / 已邀请）。 */
    INVITE_FAILED,
    /** 接受邀请失败（无待接受邀请 / 已达上限）。 */
    JOIN_FAILED,
    /** 拒绝邀请失败（无待接受邀请）。 */
    DECLINE_FAILED,
    /** 队长不能直接退队。 */
    OWNER_CANNOT_LEAVE,
    /** 退出失败（非成员）。 */
    LEAVE_FAILED,
    /** 踢人失败（无权 / 目标是队长或自己）。 */
    KICK_FAILED,
    /** 转让失败（无权 / 目标不是成员）。 */
    TRANSFER_FAILED,
    /** 解散失败（数据层未删除）。 */
    DISBAND_FAILED,
    /** 解散需要队长或 OP 3+。 */
    DISBAND_NO_PERMISSION,

    /** 团队不存在。 */
    TEAM_NOT_FOUND,
    /** 对象 ID 格式错误。 */
    OBJECT_ID_INVALID,
    /** 对象没有归属记录。 */
    OBJECT_NO_OWNER,
    /** 不是对象创建者。 */
    NOT_OBJECT_CREATOR,
    /** 对象已经分享给该团队。 */
    ALREADY_SHARED,
    /** 对象没有分享给该团队。 */
    NOT_SHARED,

    /** 称号非法（空 / 超长 / 含控制字符）。 */
    TITLE_INVALID,
    /** 目标玩家当前没有自定义称呼。 */
    NO_TITLE,

    // ------------------------------------------------------------------
    // GUI / 协议
    // ------------------------------------------------------------------

    /** 需要 OP 权限等级 3。 */
    NEED_ADMIN,
    /** 目标玩家不在线。 */
    TARGET_NOT_ONLINE,
    /** 客户端与服务端协议版本不匹配。 */
    PROTOCOL_MISMATCH,
    /** GUI 操作被限流。 */
    RATE_LIMITED,
    /** 未安装 mtrlock 客户端，无法打开 GUI。 */
    CLIENT_REQUIRED,
    /** 未知的 GUI 操作。 */
    UNKNOWN_ACTION,

    // ------------------------------------------------------------------
    // 1.2.4：称号颜色（只追加，不改动上面任何已有结果码的名称与序号）
    // ------------------------------------------------------------------

    /** 称号颜色已设置。 */
    COLOR_SET,
    /** 称号颜色已清除。 */
    COLOR_RESET,
    /** 颜色输入非法。 */
    COLOR_INVALID,
    /** 目标玩家还没有称号（不能只设颜色）。 */
    TITLE_REQUIRED;

    /** 该结果码是否表示操作成功。 */
    public boolean isSuccess() {
        switch (this) {
            case TEAM_CREATED:
            case APPLY_SUBMITTED:
            case APPLICATION_APPROVED:
            case APPLICATION_DENIED:
            case INVITATION_SENT:
            case INVITATION_ACCEPTED:
            case INVITATION_DECLINED:
            case LEFT_TEAM:
            case MEMBER_KICKED:
            case OWNERSHIP_TRANSFERRED:
            case TEAM_DISBANDED:
            case OBJECT_SHARED:
            case OBJECT_UNSHARED:
            case SYNCED:
            case TITLE_SET:
            case TITLE_CLEARED:
            case COLOR_SET:
            case COLOR_RESET:
                return true;
            default:
                return false;
        }
    }
}
