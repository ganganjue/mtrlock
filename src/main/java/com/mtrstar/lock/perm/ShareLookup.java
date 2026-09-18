package com.mtrstar.lock.perm;

import java.util.Set;

/**
 * 查询“某对象被分享给了哪些团队”的最小抽象（纯逻辑）。
 *
 * <p>1.1.0 新增。生产实现走 {@code ShareData.getInstance()::getTeamsOfObject}，
 * 测试注入桩。判定规则见 {@link PermissionChecker#canEdit(String, String, boolean,
 * CreatorLookup, ShareLookup, TeamMembershipLookup)}。</p>
 */
@FunctionalInterface
public interface ShareLookup {

    /**
     * 取收到该对象分享的团队 id 集合。
     *
     * @param objectId 对象 id
     * @return teamId 集合；无分享应返回空集合（约定不返回 {@code null}，但
     * {@link PermissionChecker} 对 {@code null} 也做了防御）
     */
    Set<String> teamsOfObject(String objectId);
}
