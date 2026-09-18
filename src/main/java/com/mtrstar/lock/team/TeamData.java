package com.mtrstar.lock.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.mtrstar.lock.Mtrlock;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队数据管理器（TeamData）。
 *
 * <p>与 {@code OwnershipData} 同级模式：单例 + 懒加载 holder + 包内 {@link #TeamData(Path)} 测试构造
 * + {@code loadFailed} 坏文件保护。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>持有全部团队：{@code ConcurrentHashMap<String, Team>}（key = teamId）；</li>
 *   <li>维护名字索引：{@code ConcurrentHashMap<String, String>}（name → teamId），保证团队名唯一；</li>
 *   <li>在此层做<b>操作者授权</b>（owner 才能邀请 / 批准 / 拒绝 / 改名 / 转让 / 踢人）与
 *       <b>每人最多 {@value #MAX_TEAMS_PER_PLAYER} 个团队</b>的上限；</li>
 *   <li>持久化到 {@code config/mtrperm/teams.json}。</li>
 * </ul>
 *
 * <p>线程安全：内存容器用 {@link ConcurrentHashMap}；涉及“读-改-写”的复合操作（建队、成员变动、
 * 改名、删除）用 {@link #lock} 串行化，避免名字索引与成员上限被并发破坏。
 * 磁盘 IO（load/save）另用 {@link #ioLock} 互斥。</p>
 *
 * <p><b>阶段边界</b>：本类不实现 shares.json（阶段 2）、不发网络包（阶段 5）、不注册命令（阶段 4）。</p>
 */
public final class TeamData {

    /** 一个玩家最多同时加入的团队数。 */
    public static final int MAX_TEAMS_PER_PLAYER = 3;

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/teams.json。 */
    private static final String FILE_NAME = "mtrperm/teams.json";

    /** 与 OwnershipData 同款 Gson 配置。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** {@code Map<String, Team>} 的 Gson 类型令牌（泛型擦除，必须显式提供）。 */
    private static final Type MAP_TYPE = new TypeToken<Map<String, Team>>() {
    }.getType();

    /** 懒加载 holder：单元测试用 {@code new TeamData(Path)} 时不会触发 FabricLoader。 */
    private static final class InstanceHolder {
        private static final TeamData INSTANCE = new TeamData();
    }

    /** 全局唯一的团队数据实例。 */
    public static TeamData getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /** teamId → Team。 */
    private final Map<String, Team> teams = new ConcurrentHashMap<>();

    /** 团队名 → teamId（保证唯一）。 */
    private final Map<String, String> nameToId = new ConcurrentHashMap<>();

    /** 复合内存操作的互斥锁。 */
    private final Object lock = new Object();

    /** 磁盘 IO 互斥锁，只保护 load()/save()。 */
    private final Object ioLock = new Object();

    /** 数据文件绝对路径。 */
    private final Path file;

    /** 上一次 load() 是否失败（坏文件保护，语义同 OwnershipData.loadFailed）。 */
    private boolean loadFailed;

    /** 生产构造：延迟到真正调用时才解析 FabricLoader 配置目录。 */
    private TeamData() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    /** 包内可见构造：仅用于单元测试注入临时文件路径。 */
    TeamData(Path file) {
        this.file = file;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /** 按 teamId 查团队；不存在返回 null。 */
    public Team getTeam(String teamId) {
        return teamId == null ? null : teams.get(teamId);
    }

    /** 按团队名查团队；名字非法 / 不存在返回 null。 */
    public Team getTeamByName(String name) {
        final String valid = Team.validateName(name);
        if (valid == null) {
            return null;
        }
        final String teamId = nameToId.get(valid);
        return teamId == null ? null : teams.get(teamId);
    }

    /** 全部团队的快照（按 createdAt、teamId 稳定排序）。 */
    public List<Team> getAllTeams() {
        return sortedCopy(teams.values());
    }

    /** 某玩家加入的所有团队（owner 也算成员），按 createdAt、teamId 稳定排序。 */
    public List<Team> getTeamsOfPlayer(String playerUuid) {
        final List<Team> result = new ArrayList<>();
        if (playerUuid == null || playerUuid.isEmpty()) {
            return result;
        }
        for (Team team : teams.values()) {
            if (team.isMember(playerUuid)) {
                result.add(team);
            }
        }
        result.sort(TEAM_ORDER);
        return result;
    }

    /** 团队总数（调试 / 测试用）。 */
    public int size() {
        return teams.size();
    }

    // =====================================================================
    // 建队 / 解散
    // =====================================================================

    /**
     * 创建一个团队（创建者即 owner，自动入队）。
     *
     * <p>边界：名字 null / 空 / 全空白 / 含控制字符 / 超过 32 字符 → null；
     * 名字重复 → null；owner 非法 / owner 已在 {@value #MAX_TEAMS_PER_PLAYER} 个团队 → null。</p>
     *
     * @param name      团队名
     * @param ownerUuid owner 玩家 UUID
     * @return 新团队；失败返回 null
     */
    public Team createTeam(String name, String ownerUuid) {
        final String validName = Team.validateName(name);
        if (validName == null || ownerUuid == null || ownerUuid.isEmpty()) {
            return null;
        }
        final Team team;
        synchronized (lock) {
            if (nameToId.containsKey(validName)) {
                return null;
            }
            if (isAtTeamLimit(ownerUuid)) {
                return null;
            }
            team = new Team(validName, ownerUuid);
            team.normalize();
            teams.put(team.getTeamId(), team);
            nameToId.put(validName, team.getTeamId());
        }
        notifyChanged();
        return team;
    }

    /**
     * 解散团队（删除团队并清掉名字索引）。
     *
     * <p><b>授权</b>：本方法只按 teamId 删除，操作者校验交给上层（阶段 4 命令系统 / OP 兜底）。
     * 阶段 2 会在这里挂接“清理指向该团队的分享记录”。</p>
     *
     * @param teamId 团队 id
     * @return 真的删掉了一个团队才返回 true
     */
    public boolean deleteTeam(String teamId) {
        if (teamId == null || teamId.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            final Team removed = teams.remove(teamId);
            if (removed == null) {
                return false;
            }
            // 只有 name 仍指向本团队时才移除，避免误删同名新团队（理论上不会发生，防御性写法）
            nameToId.remove(removed.getName(), teamId);
        }
        // 阶段 2：团队解散成功 → 清理所有指向该团队的分享记录
        ShareData.getInstance().revokeAllForTeam(teamId);
        notifyChanged();
        return true;
    }

    // =====================================================================
    // 成员管理（带操作者授权）
    // =====================================================================

    /**
     * 玩家申请加入团队（不需要授权，任何非成员都能申请）。
     *
     * @return 申请被登记才返回 true
     */
    public boolean applyToJoin(String teamId, String playerUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            return team != null && team.applyToJoin(playerUuid);
        }
    }

    /**
     * 批准入队申请（仅 owner）。
     *
     * @return 操作者是 owner、申请人有待批申请、且申请人未达团队上限才返回 true
     */
    public boolean approveApplication(String teamId, String actorUuid, String applicantUuid) {
        final boolean approved;
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || !team.isOwner(actorUuid)) {
                return false;
            }
            if (!team.getPendingApplications().contains(applicantUuid)) {
                return false;
            }
            if (isAtTeamLimit(applicantUuid)) {
                return false;
            }
            approved = team.approveApplication(applicantUuid);
        }
        if (approved) notifyChanged();
        return approved;
    }

    /**
     * 拒绝入队申请（仅 owner）。
     *
     * @return 操作者是 owner 且确实删掉了一条申请才返回 true
     */
    public boolean denyApplication(String teamId, String actorUuid, String applicantUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            return team != null && team.isOwner(actorUuid) && team.denyApplication(applicantUuid);
        }
    }

    /**
     * 邀请玩家加入（仅 owner）。
     *
     * @return 操作者是 owner 且邀请被登记才返回 true
     */
    public boolean invite(String teamId, String actorUuid, String targetUuid) {
        final boolean invited;
        synchronized (lock) {
            final Team team = teams.get(teamId);
            invited = team != null && team.isOwner(actorUuid) && team.invite(targetUuid);
        }
        if (invited) notifyChanged();
        return invited;
    }

    /**
     * 接受邀请（被邀请人本人操作，不需要 owner）。
     *
     * @return 确实有待接受邀请、且本人未达团队上限才返回 true
     */
    public boolean acceptInvitation(String teamId, String playerUuid) {
        final boolean accepted;
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || !team.getPendingInvitations().contains(playerUuid)) {
                return false;
            }
            if (isAtTeamLimit(playerUuid)) {
                return false;
            }
            accepted = team.acceptInvitation(playerUuid);
        }
        if (accepted) notifyChanged();
        return accepted;
    }

    /**
     * 拒绝 / 忽略邀请（被邀请人本人操作）。
     *
     * @return 确实删掉了一条邀请才返回 true
     */
    public boolean declineInvitation(String teamId, String playerUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            return team != null && team.denyInvitation(playerUuid);
        }
    }

    /**
     * 主动退出团队。
     *
     * <p><b>owner 不能直接退出</b>（返回 false）；需先转让 owner 或解散团队。
     * 阶段 2 会在这里挂接“撤销该玩家分享给本团队的对象”。</p>
     *
     * @return 非 owner 成员成功退队才返回 true
     */
    public boolean leaveTeam(String teamId, String playerUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || team.isOwner(playerUuid)) {
                return false;
            }
            if (!team.removeMember(playerUuid)) {
                return false;
            }
        }
        // 阶段 2：退队成功 → 撤销该玩家作为创建者分享给本团队的分享
        ShareData.getInstance().revokeAllFromPlayer(teamId, playerUuid);
        notifyChanged();
        return true;
    }

    /**
     * 踢出成员（仅 owner；不能踢 owner / 自己）。
     *
     * <p>阶段 2 会在这里挂接“撤销被踢成员分享给本团队的对象”。</p>
     *
     * @return 真的踢掉了一个成员才返回 true
     */
    public boolean kickMember(String teamId, String actorUuid, String targetUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || !team.isOwner(actorUuid)) {
                return false;
            }
            if (team.isOwner(targetUuid) || (actorUuid != null && actorUuid.equals(targetUuid))) {
                return false;
            }
            if (!team.removeMember(targetUuid)) {
                return false;
            }
        }
        // 阶段 2：踢人成功 → 撤销被踢玩家作为创建者分享给本团队的分享
        ShareData.getInstance().revokeAllFromPlayer(teamId, targetUuid);
        notifyChanged();
        return true;
    }

    /**
     * 直接加人（低层便捷方法，供内部 / 测试使用；受团队上限约束）。
     *
     * <p>正常加入流程请用 申请→批准 或 邀请→接受。</p>
     *
     * @return 成功入队才返回 true
     */
    public boolean addMember(String teamId, String playerUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || isAtTeamLimit(playerUuid)) {
                return false;
            }
            return team.addMember(playerUuid);
        }
    }

    // =====================================================================
    // 团队属性变更（带操作者授权）
    // =====================================================================

    /**
     * 改团队名（仅 owner；新名字需合法且不与其它团队重复）。
     *
     * @return 改名成功才返回 true
     */
    public boolean rename(String teamId, String actorUuid, String newName) {
        final boolean renamed;
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || !team.isOwner(actorUuid)) {
                return false;
            }
            final String valid = Team.validateName(newName);
            if (valid == null) {
                return false;
            }
            final String existingId = nameToId.get(valid);
            if (existingId != null && !existingId.equals(teamId)) {
                return false;
            }
            final String oldName = team.getName();
            if (!team.rename(valid)) {
                return false;
            }
            // 先摘旧名索引，再挂新名（oldName 必须在 team.rename 之前捕获）
            nameToId.remove(oldName, teamId);
            nameToId.put(valid, teamId);
            renamed = true;
        }
        if (renamed) notifyChanged();
        return renamed;
    }

    /**
     * 转让 owner（仅现任 owner；新 owner 必须是本团队成员）。
     *
     * @return 转让成功才返回 true
     */
    public boolean transferOwnership(String teamId, String actorUuid, String newOwnerUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null || !team.isOwner(actorUuid)) {
                return false;
            }
            return team.transferOwnership(newOwnerUuid);
            
        }
    }
    /**
     * 踢人（owner 或 OP 3+ 兜底）。
     *
     * <p>与现有 3 参版本并存（3 参版等价于 actorIsAdmin=false）。阶段 2 的撤销分享 hook
     * 只在“真的踢掉了”时才触发。</p>
     */
    public boolean kickMember(String teamId, String actorUuid, boolean actorIsAdmin, String targetUuid) {
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null) {
                return false;
            }
            if (!actorIsAdmin && !team.isOwner(actorUuid)) {
                return false;
            }
            if (team.isOwner(targetUuid)) {
                return false;
            }
            if (actorUuid != null && actorUuid.equals(targetUuid)) {
                return false;
            }
            if (!team.removeMember(targetUuid)) {
                return false;
            }
        }
        // 阶段 2：踢人成功 → 撤销被踢玩家作为创建者分享给本团队的分享
        ShareData.getInstance().revokeAllFromPlayer(teamId, targetUuid);
        notifyChanged();
        return true;
    }

    /**
     * 转让 owner（owner 或 OP 3+ 兜底）。
     *
     * <p>与现有 3 参版本并存（3 参版等价于 actorIsAdmin=false）。</p>
     */
    public boolean transferOwnership(String teamId, String actorUuid, boolean actorIsAdmin,
                                     String newOwnerUuid) {
        final boolean transferred;
        synchronized (lock) {
            final Team team = teams.get(teamId);
            if (team == null) {
                return false;
            }
            if (!actorIsAdmin && !team.isOwner(actorUuid)) {
                return false;
            }
            transferred = team.transferOwnership(newOwnerUuid);
        }
        if (transferred) notifyChanged();
        return transferred;
    }


    /** 变更监听器（S2C 推送用）。 */
    private static volatile Runnable changeListener = () -> {};
    public static void setChangeListener(Runnable listener) {
        changeListener = listener != null ? listener : () -> {};
    }
    private static void notifyChanged() {
        try { changeListener.run(); } catch (Exception ignored) {}
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 该玩家是否已加入 {@value #MAX_TEAMS_PER_PLAYER} 个团队。 */
    private boolean isAtTeamLimit(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return true; // 非法 uuid 视为不能加入
        }
        int count = 0;
        for (Team team : teams.values()) {
            if (team.isMember(playerUuid)) {
                count++;
                if (count >= MAX_TEAMS_PER_PLAYER) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Comparator<Team> TEAM_ORDER = Comparator
            .comparingLong(Team::getCreatedAt)
            .thenComparing(Team::getTeamId);

    private static List<Team> sortedCopy(java.util.Collection<Team> source) {
        final List<Team> result = new ArrayList<>(source);
        result.sort(TEAM_ORDER);
        return result;
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    /** 仅供测试 / 调试：上一次 load() 是否失败。 */
    boolean hasLoadFailed() {
        synchronized (ioLock) {
            return loadFailed;
        }
    }

    /**
     * 将内存中的团队数据写入 {@code config/mtrperm/teams.json}。
     *
     * <p>与 OwnershipData 相同的两道保护：</p>
     * <ol>
     *   <li>上次 load 失败（坏文件）→ 跳过写盘，绝不覆盖坏文件；</li>
     *   <li>内存为空但文件非空 → 跳过，避免空内存误清空文件。</li>
     * </ol>
     */
    public void save() {
        synchronized (ioLock) {
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载团队数据失败，跳过保存以避免覆盖损坏文件: {}", file);
                return;
            }
            try {
                if (teams.isEmpty() && Files.exists(file) && Files.size(file) > 0L) {
                    Mtrlock.LOGGER.warn("[mtrlock] 内存团队数据为空但文件非空，跳过保存以避免清空: {}", file);
                    return;
                }

                final Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }

                final Map<String, Team> snapshot = new HashMap<>(teams);
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, MAP_TYPE, writer);
                }
                Mtrlock.LOGGER.info("[mtrlock] 已保存 {} 个团队到 {}", snapshot.size(), file);
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存团队数据失败: {}", file, e);
            }
        }
    }

    /**
     * 从 {@code config/mtrperm/teams.json} 读取团队数据并替换内存内容。
     *
     * <p>文件不存在 → 重置失败标志后返回；文件损坏 → 记录日志、保留原有内存、置
     * {@code loadFailed=true}，后续 save 跳过写盘。</p>
     *
     * <p>加载时做引用完整性清理：过滤 null / 非法名字的团队；重建名字索引（重名保留第一条并告警）；
     * 补全缺失 teamId；每个团队 {@link Team#normalize()}（补空集合 + 保证 owner 在成员表）。</p>
     */
    public void load() {
        synchronized (ioLock) {
            if (!Files.exists(file)) {
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 团队数据文件不存在，跳过加载: {}", file);
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                final Map<String, Team> data = GSON.fromJson(reader, MAP_TYPE);

                synchronized (lock) {
                    teams.clear();
                    nameToId.clear();
                    if (data != null) {
                        for (Map.Entry<String, Team> entry : data.entrySet()) {
                            final String key = entry.getKey();
                            Team team = entry.getValue();
                            if (key == null || key.isEmpty() || team == null) {
                                continue;
                            }
                            team.setTeamId(key);
                            team.normalize();
                            final String valid = Team.validateName(team.getName());
                            if (valid == null) {
                                Mtrlock.LOGGER.warn("[mtrlock] 跳过名字非法的团队: id={}, name={}", key, team.getName());
                                continue;
                            }
                            team.setName(valid);
                            teams.put(key, team);
                            if (nameToId.putIfAbsent(valid, key) != null) {
                                Mtrlock.LOGGER.warn("[mtrlock] 团队重名，保留先出现者: name={}, kept={}", valid, nameToId.get(valid));
                            }
                        }
                    }
                }

                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 已加载 {} 个团队", teams.size());
            } catch (IOException | JsonSyntaxException | JsonIOException e) {
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载团队数据失败，保留原有内存数据，后续 save 将跳过: {}", file, e);
            }
        }
    }
}
