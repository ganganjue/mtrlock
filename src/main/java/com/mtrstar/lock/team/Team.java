package com.mtrstar.lock.team;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一个团队（Team）——纯数据模型 + 状态机。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>本类只维护团队自身的状态（成员、申请、邀请、owner），<b>不做操作者授权</b>；
 *       “谁能不能邀请 / 批准 / 改名”由 {@link TeamData} 的 actor 参数判定。</li>
 *   <li>本类也<b>不碰磁盘、不发网络包、不读配置</b>；持久化在 {@link TeamData}。</li>
 * </ul>
 *
 * <p>状态机规则（都在方法里硬性保证，返回 {@code boolean} 表示是否真的改变了状态）：</p>
 * <ul>
 *   <li>owner 一定在 {@link #members} 里，且不能通过 {@link #removeMember(String)} 移除；</li>
 *   <li>重复申请 / 重复邀请 / 已在团队 → false；</li>
 *   <li>批准申请和接受邀请都会顺手清掉另一侧的挂起记录，避免“既申请又被邀请”的悬挂状态；</li>
 *   <li>解散 / 从团队列表移除由 {@link TeamData} 负责。</li>
 * </ul>
 *
 * <p>Gson 序列化：字段直接映射到 JSON；提供包内无参构造供 Gson 反序列化。
 * {@link #normalize()} 负责在加载后把可能为 null 的集合补成空集合，并保证 owner 在成员表里。</p>
 */
public final class Team {

    /** 团队名最大长度（按 Unicode code point 计，中文 / emoji 都按“字符”算）。 */
    public static final int MAX_NAME_LENGTH = 32;

    /**
     * 团队 id：随机 UUID 字符串。
     * 注意：这里用 {@link UUID#randomUUID()} 而不是 MTR 的 long id，避免与 MTR 对象 id 混淆。
     */
    private String teamId;

    /** 团队名（已通过 {@link #validateName(String)} 校验；存储时去掉首尾空白）。 */
    private String name;

    /** owner 的玩家 UUID（成员之一）。 */
    private String ownerUuid;

    /** 成员集合（含 owner）。 */
    private Set<String> members = new LinkedHashSet<>();

    /** 待批准的入队申请（uuid）。 */
    private Set<String> pendingApplications = new LinkedHashSet<>();

    /** 待接受的入队邀请（uuid）。 */
    private Set<String> pendingInvitations = new LinkedHashSet<>();

    /** 创建时间（毫秒时间戳），仅用于稳定排序 / 展示。 */
    private long createdAt;

    /** Gson 反序列化用：字段由反射填充，集合字段可能为 null，加载后由 {@link #normalize()} 修正。 */
    Team() {
    }

    /**
     * 创建一个新团队。
     *
     * <p>本构造<b>不做</b>名字合法性校验（校验在 {@link TeamData#createTeam(String, String)}），
     * 但会把 owner 自动加进成员表。</p>
     *
     * @param name      团队名
     * @param ownerUuid owner 的玩家 UUID
     */
    public Team(String name, String ownerUuid) {
        this.teamId = UUID.randomUUID().toString();
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.createdAt = System.currentTimeMillis();
        if (ownerUuid != null && !ownerUuid.isEmpty()) {
            this.members.add(ownerUuid);
        }
    }

    // =====================================================================
    // 名字校验（Team 与 TeamData 共用）
    // =====================================================================

    /**
     * 校验并规范化团队名。
     *
     * <p>规则：null / 空 / 全空白 → null；长度 &gt; {@link #MAX_NAME_LENGTH}（按 code point）→ null；
     * 含 ISO 控制字符 → null；否则返回去掉首尾空白后的名字。</p>
     *
     * @param raw 原始名字
     * @return 规范化后的名字；非法时返回 {@code null}
     */
    public static String validateName(String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); ) {
            final int cp = trimmed.codePointAt(i);
            if (Character.isISOControl(cp)) {
                return null;
            }
            i += Character.charCount(cp);
        }
        return trimmed;
    }

    /** 名字是否合法（{@link #validateName(String)} 的布尔版）。 */
    public static boolean isValidName(String raw) {
        return validateName(raw) != null;
    }

    // =====================================================================
    // 只读访问
    // =====================================================================

    public String getTeamId() {
        return teamId;
    }

    public String getName() {
        return name;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** 成员快照（不可变拷贝，外部改不动内部集合）。 */
    public Set<String> getMembers() {
        return copyOf(members);
    }

    /** 待批准申请快照。 */
    public Set<String> getPendingApplications() {
        return copyOf(pendingApplications);
    }

    /** 待接受邀请快照。 */
    public Set<String> getPendingInvitations() {
        return copyOf(pendingInvitations);
    }

    public boolean isMember(String uuid) {
        return uuid != null && members.contains(uuid);
    }

    public boolean isOwner(String uuid) {
        return uuid != null && uuid.equals(ownerUuid);
    }

    // =====================================================================
    // 成员管理
    // =====================================================================

    /**
     * 直接加人（低层方法；是否允许该操作由 {@link TeamData} 决定）。
     *
     * @param uuid 玩家 UUID
     * @return 真的加进去了才返回 true（重复加 / 非法参数返回 false）
     */
    public boolean addMember(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }
        return members.add(uuid);
    }

    /**
     * 移除成员。
     *
     * <p><b>owner 不能被移除</b>（owner 只能先转让或解散团队）。</p>
     *
     * @param uuid 玩家 UUID
     * @return 真的移除了才返回 true
     */
    public boolean removeMember(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }
        if (uuid.equals(ownerUuid)) {
            return false;
        }
        return members.remove(uuid);
    }

    // =====================================================================
    // 申请入队
    // =====================================================================

    /**
     * 玩家申请加入。
     *
     * @param uuid 申请人 UUID
     * @return 新登记了一条申请返回 true；已在团队 / 重复申请 / 非法参数返回 false
     */
    public boolean applyToJoin(String uuid) {
        if (uuid == null || uuid.isEmpty() || isMember(uuid)) {
            return false;
        }
        return pendingApplications.add(uuid);
    }

    /**
     * 批准申请（授权检查在 {@link TeamData}）。
     *
     * @param uuid 申请人 UUID
     * @return 该申请人确实有待批申请且成功入队才返回 true
     */
    public boolean approveApplication(String uuid) {
        if (uuid == null || !pendingApplications.remove(uuid)) {
            return false;
        }
        pendingInvitations.remove(uuid);
        members.add(uuid);
        return true;
    }

    /**
     * 拒绝申请（授权检查在 {@link TeamData}）。
     *
     * @param uuid 申请人 UUID
     * @return 确实删掉了一条待批申请才返回 true
     */
    public boolean denyApplication(String uuid) {
        if (uuid == null) {
            return false;
        }
        return pendingApplications.remove(uuid);
    }

    // =====================================================================
    // 邀请入队
    // =====================================================================

    /**
     * 邀请玩家加入（授权检查在 {@link TeamData}）。
     *
     * @param uuid 被邀请人 UUID
     * @return 新登记了一条邀请返回 true；已在团队 / 重复邀请 / 非法参数返回 false
     */
    public boolean invite(String uuid) {
        if (uuid == null || uuid.isEmpty() || isMember(uuid)) {
            return false;
        }
        return pendingInvitations.add(uuid);
    }

    /**
     * 接受邀请。
     *
     * @param uuid 被邀请人 UUID
     * @return 确实有待接受邀请且成功入队才返回 true
     */
    public boolean acceptInvitation(String uuid) {
        if (uuid == null || !pendingInvitations.remove(uuid)) {
            return false;
        }
        pendingApplications.remove(uuid);
        members.add(uuid);
        return true;
    }

    /**
     * 拒绝 / 忽略邀请。
     *
     * @param uuid 被邀请人 UUID
     * @return 确实删掉了一条待接受邀请才返回 true
     */
    public boolean denyInvitation(String uuid) {
        if (uuid == null) {
            return false;
        }
        return pendingInvitations.remove(uuid);
    }

    // =====================================================================
    // 团队属性变更
    // =====================================================================

    /**
     * 改名（授权检查在 {@link TeamData}）。
     *
     * @param newName 新名字
     * @return 名字合法且确实变了才返回 true；非法名字返回 false
     */
    public boolean rename(String newName) {
        final String valid = validateName(newName);
        if (valid == null) {
            return false;
        }
        this.name = valid;
        return true;
    }

    /**
     * 转让 owner（授权检查在 {@link TeamData}）。
     *
     * <p>新 owner 必须是当前成员；转让后原 owner <b>保留成员身份</b>。</p>
     *
     * @param newOwnerUuid 新 owner UUID
     * @return 转让成功返回 true；目标不是成员 / 就是现任 owner / 非法参数返回 false
     */
    public boolean transferOwnership(String newOwnerUuid) {
        if (newOwnerUuid == null || newOwnerUuid.isEmpty()) {
            return false;
        }
        if (newOwnerUuid.equals(ownerUuid)) {
            return false;
        }
        if (!members.contains(newOwnerUuid)) {
            return false;
        }
        this.ownerUuid = newOwnerUuid;
        return true;
    }

    // =====================================================================
    // 加载期修正
    // =====================================================================

    /**
     * 反序列化后的规范化：把 null 集合补空、过滤 null / 空串元素，并保证 owner 在成员表里。
     * 由 {@link TeamData#load()} 在把团队放进内存前调用。
     */
    void normalize() {
        members = normalizeSet(members);
        pendingApplications = normalizeSet(pendingApplications);
        pendingInvitations = normalizeSet(pendingInvitations);
        if (ownerUuid != null && !ownerUuid.isEmpty()) {
            members.add(ownerUuid);
        }
        if (teamId == null) {
            teamId = "";
        }
    }

    /** 加载时用 JSON 的 key 补齐缺失的 teamId。 */
    void setTeamId(String value) {
        this.teamId = value;
    }

    /** 加载时写入规范化后的名字。 */
    void setName(String value) {
        this.name = value;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private static Set<String> copyOf(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static Set<String> normalizeSet(Set<String> source) {
        final Set<String> out = new LinkedHashSet<>();
        if (source != null) {
            for (String value : source) {
                if (value != null && !value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Team{id=" + teamId + ", name=" + name + ", owner=" + ownerUuid
                + ", members=" + members.size() + "}";
    }
}
