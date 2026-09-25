package com.mtrstar.lock.client;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能 6：客户端归属 / 分享缓存。
 *
 * <p>由服务端 S2C 包填充（{@code mtrlock:sync_ownership}，见 {@code MtrlockClient}），
 * 只用于“发包前的本地预判”，<b>不是权威</b>；权威判定在服务端（功能 5）。</p>
 *
 * <p>字段：</p>
 * <ul>
 *   <li>{@code OWNERSHIP}：objectId → 创建者 UUID（全量快照）；</li>
 *   <li>{@code operator}：当前客户端玩家是否 OP 3+（服务端随包逐个下发）；</li>
 *   <li>{@code OBJECT_TEAMS} / {@code TEAM_MEMBERS}：分享 / 团队快照
 *       （<b>阶段 5</b> S2C 填充，阶段 3 恒为空）；</li>
 *   <li>{@code TEAM_NAMES}：teamId → 团队名（<b>阶段 6</b> S2C 填充，供客户端显示名 / 头顶名字前缀使用）；</li>
 *   <li>{@code shareInfoSynced}：是否已收到分享 / 团队快照（阶段 5 才置 true）。</li>
 * </ul>
 */
public final class ClientOwnership {

    private static final Map<String, String> OWNERSHIP = new ConcurrentHashMap<>();

    /** objectId → 收到分享的 teamId 集合（阶段 5 S2C 填充；阶段 3 恒为空）。 */
    private static final Map<String, Set<String>> OBJECT_TEAMS = new ConcurrentHashMap<>();

    /** teamId → 成员 UUID 集合（阶段 5 S2C 填充；阶段 3 恒为空）。 */
    private static final Map<String, Set<String>> TEAM_MEMBERS = new ConcurrentHashMap<>();

    /** teamId → 团队名（阶段 6 S2C 填充；用于客户端显示名 / 头顶名字前缀）。 */
    private static final Map<String, String> TEAM_NAMES = new ConcurrentHashMap<>();

    private static volatile boolean operator;

    /** 是否已收到分享 / 团队快照。阶段 3 恒为 false → {@link #canEditOrUnknown} 对非创建者 fail-open。 */
    private static volatile boolean shareInfoSynced;

    private ClientOwnership() {
    }

    /** 全量替换归属快照。 */
    public static void setAll(Map<String, String> snapshot) {
        OWNERSHIP.clear();
        if (snapshot != null) {
            OWNERSHIP.putAll(snapshot);
        }
    }

    public static void setOperator(boolean value) {
        operator = value;
    }

    public static boolean isOperator() {
        return operator;
    }

    public static boolean hasCreator(String objectId) {
        return objectId != null && OWNERSHIP.containsKey(objectId);
    }

    public static String getCreator(String objectId) {
        return objectId == null ? null : OWNERSHIP.get(objectId);
    }

    /**
     * 本地“精确”预判（只看归属，不考虑分享）：当前玩家能否编辑 / 删除该 objectId。
     *
     * <p><b>不要</b>再用它做发包拦截——它会误拦团队成员。发包拦截请用
     * {@link #canEditOrUnknown(String, String)}。</p>
     */
    public static boolean canEdit(String objectId, String playerUuid) {
        if (operator) {
            return true;
        }
        if (objectId == null || playerUuid == null) {
            return false;
        }
        final String creator = OWNERSHIP.get(objectId);
        return creator != null && creator.equals(playerUuid);
    }

    /**
     * 本地预判（分享感知，未同步时 fail-open）：当前玩家能否编辑 / 删除该 objectId。
     *
     * <p>判定顺序：</p>
     * <ol>
     *   <li>OP 3+ → true；</li>
     *   <li>objectId / playerUuid 非法 → false；</li>
     *   <li>创建者本人 → true；</li>
     *   <li>分享 / 团队信息<b>未同步</b> → true（fail-open，避免误拦团队成员，由服务端兜底）；</li>
     *   <li>已同步：对象分享给的任一团队里包含该玩家 → true；否则 false。</li>
     * </ol>
     *
     * <p><b>阶段 3~5 之间的刻意不对称</b>：客户端此时还没有分享 / 团队快照，所以对非创建者一律
     * fail-open（不拦）；服务端判定始终精确。阶段 5 补 S2C 同步后，客户端才恢复精确拦截。</p>
     */
    public static boolean canEditOrUnknown(String objectId, String playerUuid) {
        if (operator) {
            return true;
        }
        if (objectId == null || objectId.isEmpty() || playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final String creator = OWNERSHIP.get(objectId);
        if (creator != null && creator.equals(playerUuid)) {
            return true;
        }
        // 分享 / 团队信息未同步 → fail-open
        if (!shareInfoSynced) {
            return true;
        }
        return isMemberOfSharedTeam(objectId, playerUuid);
    }

    /** 已同步分享信息时：对象分享给的任一团队里包含该玩家。 */
    private static boolean isMemberOfSharedTeam(String objectId, String playerUuid) {
        final Set<String> teamIds = OBJECT_TEAMS.get(objectId);
        if (teamIds == null || teamIds.isEmpty()) {
            return false;
        }
        for (String teamId : teamIds) {
            final Set<String> members = TEAM_MEMBERS.get(teamId);
            if (members != null && members.contains(playerUuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 阶段 5 S2C 同步入口：整体替换分享 / 团队快照，并标记“已同步”。
     *
     * <p>阶段 3 不调用本方法，所以 {@code shareInfoSynced} 保持 false。</p>
     *
     * @param objectTeams objectId → teamId 集合
     * @param teamMembers teamId → 成员 UUID 集合
     */
    public static void setShareSnapshot(Map<String, Set<String>> objectTeams,
                                        Map<String, Set<String>> teamMembers) {
        OBJECT_TEAMS.clear();
        TEAM_MEMBERS.clear();
        copyInto(objectTeams, OBJECT_TEAMS);
        copyInto(teamMembers, TEAM_MEMBERS);
        shareInfoSynced = true;
    }

    /** 分享 / 团队快照是否已同步（阶段 5 之前恒 false）。 */
    public static boolean isShareInfoSynced() {
        return shareInfoSynced;
    }

    private static void copyInto(Map<String, Set<String>> source, Map<String, Set<String>> target) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
    }

    /** 某团队的名字；未知 / 非法参数返回 null。 */
    public static String getTeamName(String teamId) {
        return teamId == null ? null : TEAM_NAMES.get(teamId);
    }

    /**
     * teamId → 成员 UUID 集合（<b>只读用途</b>：调用方不要修改返回的 map / set）。
     *
     * <p>返回内部实时视图（不拷贝）：显示名 / 头顶名字每帧都可能查询，避免反复分配。</p>
     */
    public static Map<String, Set<String>> getTeamMembers() {
        return TEAM_MEMBERS;
    }

    /** 阶段 6 S2C 同步入口：整体替换 teamId → 团队名。 */
    public static void setTeamNames(Map<String, String> teamNames) {
        TEAM_NAMES.clear();
        if (teamNames == null) {
            return;
        }
        for (Map.Entry<String, String> entry : teamNames.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                TEAM_NAMES.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 退出服务器时清空缓存。 */
    public static void clear() {
        OWNERSHIP.clear();
        OBJECT_TEAMS.clear();
        TEAM_MEMBERS.clear();
        TEAM_NAMES.clear();
        operator = false;
        shareInfoSynced = false;
    }

    public static int size() {
        return OWNERSHIP.size();
    }
}
