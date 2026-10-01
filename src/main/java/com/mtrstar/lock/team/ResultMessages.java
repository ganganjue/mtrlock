package com.mtrstar.lock.team;

import java.util.Locale;

/**
 * {@link ResultCode} 的文案映射（1.2.3）。
 *
 * <p>两条通道：</p>
 * <ul>
 *   <li>{@link #zh(ResultCode)}：命令层使用，失败文案与 1.2.2 的命令提示<b>逐字一致</b>
 *       （命令的成功文案仍由命令自己拼上团队名 / 对象 id 等上下文）；</li>
 *   <li>{@link #langKey(ResultCode)}：GUI 层使用，客户端按玩家语言从
 *       {@code assets/mtrlock/lang/*.json} 取文案。</li>
 * </ul>
 *
 * <p>纯函数，不依赖 Minecraft / Fabric，可纯 JVM 单测。</p>
 */
public final class ResultMessages {

    private ResultMessages() {
    }

    /** GUI 文案的 lang key 前缀。 */
    public static final String LANG_PREFIX = "gui.mtrlock.result.";

    /** 结果码对应的 lang key（形如 {@code gui.mtrlock.result.team_created}）。 */
    public static String langKey(ResultCode code) {
        return LANG_PREFIX + code.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 结果码的中文文案。
     *
     * <p>成功码给的是通用短语（命令实际拼的是更具体的成功提示）；失败码与旧版命令
     * 提示逐字一致，方便命令层直接复用。</p>
     */
    public static String zh(ResultCode code) {
        switch (code) {
            // 成功（通用短语）
            case TEAM_CREATED: return "已创建团队";
            case APPLY_SUBMITTED: return "已提交申请";
            case APPLICATION_APPROVED: return "已批准加入";
            case APPLICATION_DENIED: return "已拒绝该申请";
            case INVITATION_SENT: return "已邀请加入";
            case INVITATION_ACCEPTED: return "已加入团队";
            case INVITATION_DECLINED: return "已拒绝邀请";
            case LEFT_TEAM: return "已退出团队";
            case MEMBER_KICKED: return "已踢出成员";
            case OWNERSHIP_TRANSFERRED: return "已转让创建者";
            case TEAM_DISBANDED: return "已解散团队";
            case OBJECT_SHARED: return "已分享给团队";
            case SYNCED: return "已同步";
            case OBJECT_UNSHARED: return "已取消分享";
            case TITLE_SET: return "称号已设置";
            case TITLE_CLEARED: return "称呼已清除";

            // 失败（与旧版命令逐字一致）
            case TEAM_CREATE_FAILED:
                return "创建失败：名字非法（空 / 超 " + Team.MAX_NAME_LENGTH + " 字 / 含控制字符）、名字重复，"
                        + "或你已加入 " + TeamData.MAX_TEAMS_PER_PLAYER + " 个团队";
            case APPLY_FAILED:
                return "申请失败：你已是成员，或已经申请过";
            case APPROVE_FAILED:
                return "批准失败：你不是创建者，或对方没有待批申请，或对方已达 "
                        + TeamData.MAX_TEAMS_PER_PLAYER + " 个团队上限";
            case DENY_FAILED:
                return "拒绝失败：你不是创建者，或对方没有待批申请";
            case INVITE_FAILED:
                return "邀请失败：你不是创建者，或对方已是成员 / 已被邀请";
            case JOIN_FAILED:
                return "加入失败：你没有待接受邀请，或已达 " + TeamData.MAX_TEAMS_PER_PLAYER + " 个团队上限";
            case DECLINE_FAILED:
                return "拒绝失败：你没有待接受邀请";
            case OWNER_CANNOT_LEAVE:
                return "创建者不能直接退出。请先 /team transfer 转让，或 /team disband 解散";
            case LEAVE_FAILED:
                return "退出失败：你不是该团队成员";
            case KICK_FAILED:
                return "踢人失败：你没有权限（需为创建者或 OP 3+），或不能踢创建者 / 自己";
            case TRANSFER_FAILED:
                return "转让失败：你没有权限（需为创建者或 OP 3+），或目标不是成员";
            case DISBAND_FAILED:
                return "解散失败：团队不存在";
            case DISBAND_NO_PERMISSION:
                return "解散失败：需要团队创建者或 OP 3+";

            case TEAM_NOT_FOUND:
                return "团队不存在";
            case OBJECT_ID_INVALID:
                return "对象 ID 格式错误。正确格式如 route:0B0829457F350DE9。用 /mtrlock my 查看你的对象";
            case OBJECT_NO_OWNER:
                return "该对象没有归属记录（可能是模组安装前创建的）";
            case NOT_OBJECT_CREATOR:
                return "只有对象创建者可以分享";
            case ALREADY_SHARED:
                return "分享失败：该对象已经分享给这个团队";
            case NOT_SHARED:
                return "取消失败：该对象没有分享给这个团队";

            case TITLE_INVALID:
                return "称呼非法：不能为空、不超过 " + TitleData.MAX_TITLE_LENGTH + " 个字符、且不能含控制字符";
            case NO_TITLE:
                return "该玩家当前没有自定义称呼";
            case COLOR_SET:
                return "称号颜色已设置";
            case COLOR_RESET:
                return "称号颜色已清除";
            case COLOR_INVALID:
                return "颜色非法：支持 16 原版色（&a / red 等）、#RRGGBB、&x&r&r&g&g&b&b";
            case TITLE_REQUIRED:
                return "该玩家还没有称号，请先设置称号文本再设置颜色";

            case NEED_ADMIN:
                return "需要 OP 权限等级 3 才能使用该命令";
            case TARGET_NOT_ONLINE:
                return "找不到在线玩家";
            case PROTOCOL_MISMATCH:
                return "mtrlock 客户端与服务端版本不匹配，请把两端升级到同一版本";
            case RATE_LIMITED:
                return "操作过于频繁，请稍后再试";
            case CLIENT_REQUIRED:
                return "需要安装 mtrlock 客户端才能使用 GUI";
            case UNKNOWN_ACTION:
                return "未知的 GUI 操作";
            default:
                return code.name();
        }
    }
}
