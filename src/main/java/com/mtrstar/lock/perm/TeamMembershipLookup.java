package com.mtrstar.lock.perm;

/**
 * 查询“某玩家是否为某团队成员”的最小抽象（纯逻辑）。
 *
 * <p>1.1.0 新增。生产实现走 {@code TeamData}（{@code getTeam(teamId).isMember(uuid)}），
 * 测试注入桩。</p>
 */
@FunctionalInterface
public interface TeamMembershipLookup {

    /**
     * 判断玩家是否属于指定团队。
     *
     * @param teamId     团队 id
     * @param playerUuid 玩家 UUID
     * @return 是成员返回 true
     */
    boolean isMemberOf(String teamId, String playerUuid);
}
