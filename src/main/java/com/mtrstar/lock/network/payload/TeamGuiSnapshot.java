package com.mtrstar.lock.network.payload;

import java.util.List;

/**
 * S2C：团队 GUI 全量快照（1.2.3）。
 *
 * <p>打开 GUI 时发一次，之后每次操作由服务端校验并重新发全量；
 * 客户端<b>从不做乐观更新</b>，永远只渲染最后一次收到的快照。</p>
 *
 * <p>纯数据 record，内含的嵌套 record 也都不含 Minecraft 类型。</p>
 *
 * @param myTeams       我所在的团队（最多 3 个）
 * @param applications  我作为队长收到的待批申请
 * @param invitations   我收到的待接受邀请
 * @param shares        我创建的对象当前分享给了哪些团队
 * @param myObjectIds   我创建的全部对象 ID（分享界面按前缀筛选用）
 * @param onlinePlayers 当前在线玩家（邀请 / 选择用）
 */
public record TeamGuiSnapshot(List<TeamEntry> myTeams,
                              List<PendingEntry> applications,
                              List<PendingEntry> invitations,
                              List<ShareEntry> shares,
                              List<String> myObjectIds,
                              List<PlayerEntry> onlinePlayers) {

    public TeamGuiSnapshot {
        myTeams = myTeams == null ? List.of() : List.copyOf(myTeams);
        applications = applications == null ? List.of() : List.copyOf(applications);
        invitations = invitations == null ? List.of() : List.copyOf(invitations);
        shares = shares == null ? List.of() : List.copyOf(shares);
        myObjectIds = myObjectIds == null ? List.of() : List.copyOf(myObjectIds);
        onlinePlayers = onlinePlayers == null ? List.of() : List.copyOf(onlinePlayers);
    }

    /** 空快照（断线 / 出错时客户端兜底）。 */
    public static TeamGuiSnapshot empty() {
        return new TeamGuiSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 一个团队的展示条目。
     *
     * @param teamId      团队 id
     * @param name        团队名
     * @param ownerUuid   队长 UUID
     * @param ownerName   队长显示名
     * @param memberCount 成员数
     * @param shareCount  分享给该团队的对象数
     * @param owner       当前玩家是否是该团队队长
     * @param members     成员列表（含队长），成员管理界面用
     */
    public record TeamEntry(String teamId, String name, String ownerUuid, String ownerName,
                            int memberCount, int shareCount, boolean owner, List<PlayerEntry> members) {

        public TeamEntry {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    /**
     * 待处理条目（收到的申请 / 邀请）。
     *
     * @param teamId     团队 id
     * @param teamName   团队名
     * @param playerUuid 对方玩家 UUID（申请人 / 邀请人 / 被邀请人）
     * @param playerName 对方玩家显示名
     */
    public record PendingEntry(String teamId, String teamName, String playerUuid, String playerName) {
    }

    /**
     * 一条分享关系。
     *
     * @param objectId 对象 ID
     * @param teamId   团队 id
     * @param teamName 团队名
     */
    public record ShareEntry(String objectId, String teamId, String teamName) {
    }
}
