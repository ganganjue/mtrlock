package com.mtrstar.lock.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分享数据管理器（ShareData）。
 *
 * <p>语义：<b>对象永远归属创建者个人</b>，分享只是“对象 → 团队”的附加授权。
 * 一个对象可以分享给多个团队；一个团队也可以收到多个对象的分享。</p>
 *
 * <p>数据结构（{@code Map<String, Set<String>>}）：</p>
 * <pre>
 *   key   = objectId，形如 "route:0B0829457F350DE9"
 *   value = 收到该对象分享的 teamId 集合（线程安全 set）
 * </pre>
 *
 * <p>与 {@link OwnershipData} / {@link TeamData} 同级模式：单例 + 懒加载 holder +
 * 包内 {@link #ShareData(Path)} 测试构造 + {@code loadFailed} 坏文件保护。</p>
 *
 * <p>依赖方向：{@code ShareData → OwnershipData}（撤销时查对象创建者）、
 * {@code ShareData → TeamData}（清理孤儿 teamId）。<b>反向不依赖</b>，避免循环。</p>
 *
 * <p><b>阶段边界</b>：本类不发网络包（阶段 5）、不注册命令（阶段 4）、不做权限判定（阶段 3）。</p>
 */
public final class ShareData {

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/shares.json。 */
    private static final String FILE_NAME = "mtrperm/shares.json";

    /** 与其它数据类同款 Gson 配置。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** {@code Map<String, Set<String>>} 的 Gson 类型令牌。 */
    private static final Type MAP_TYPE = new TypeToken<Map<String, Set<String>>>() {
    }.getType();

    /** 懒加载 holder：单元测试用 {@code new ShareData(Path)} 时不会触发 FabricLoader。 */
    private static final class InstanceHolder {
        private static final ShareData INSTANCE = new ShareData();
    }

    /** 全局唯一的分享数据实例。 */
    public static ShareData getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 查询某 objectId 的创建者（撤销分享时需要知道“这个对象是不是某玩家建的”）。
     * 生产实现走 {@link OwnershipData}；测试注入桩。
     */
    @FunctionalInterface
    public interface CreatorLookup {

        /** @return 创建者 UUID；无记录返回 null */
        String getCreator(String objectId);
    }

    /**
     * 判断某 teamId 是否仍然存在（清理孤儿 teamId 时需要）。
     * 生产实现走 {@link TeamData}；测试注入桩。
     */
    @FunctionalInterface
    public interface TeamExistsLookup {

        boolean teamExists(String teamId);
    }

    /** objectId → teamId 集合。 */
    private final Map<String, Set<String>> shares = new ConcurrentHashMap<>();

    /** 复合内存操作的互斥锁。 */
    private final Object lock = new Object();

    /** 磁盘 IO 互斥锁，只保护 load()/save()。 */
    private final Object ioLock = new Object();

    /** 数据文件绝对路径。 */
    private final Path file;

    /** 创建者查询（延迟到真正调用才触碰 OwnershipData 单例）。 */
    private final CreatorLookup creators;

    /** 团队存在性查询（延迟到真正调用才触碰 TeamData 单例）。 */
    private final TeamExistsLookup teamExists;

    /** 上一次 load() 是否失败（坏文件保护，语义同 OwnershipData.loadFailed）。 */
    private boolean loadFailed;

    /**
     * 生产构造：<b>不在构造时解析 FabricLoader</b>（file = null），把配置目录解析推迟到第一次
     * {@link #load()} / {@link #save()}。这样即使 FabricLoader 未初始化，{@link #getInstance()}
     * 本身也不会炸，便于纯 JVM 测试里团队钩子安全地拿到一个空的内存实例。
     */
    private ShareData() {
        this(null,
                objectId -> OwnershipData.getInstance().getCreator(objectId),
                teamId -> TeamData.getInstance().getTeam(teamId) != null);
    }

    /** 包内可见构造：默认走真实单例（仅供生产 / 特殊测试使用；单测请用 3 参构造注入桩）。 */
    ShareData(Path file) {
        this(file,
                objectId -> OwnershipData.getInstance().getCreator(objectId),
                teamId -> TeamData.getInstance().getTeam(teamId) != null);
    }

    /** 包内可见构造：注入创建者 / 团队查询桩，纯 JVM 单元测试用。file 可为 null（延迟解析）。 */
    ShareData(Path file, CreatorLookup creators, TeamExistsLookup teamExists) {
        this.file = file;
        this.creators = creators;
        this.teamExists = teamExists;
    }

    /** 解析数据文件路径；生产单例延迟到第一次 IO 时才触碰 FabricLoader。 */
    private Path resolveFile() {
        final Path current = file;
        return current != null ? current : FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /**
     * 某对象被分享给了哪些团队（不可变快照）。
     *
     * @return teamId 集合；无分享返回空集合（不是 null）
     */
    public Set<String> getTeamsOfObject(String objectId) {
        if (objectId == null) {
            return Collections.emptySet();
        }
        final Set<String> set = shares.get(objectId);
        if (set == null || set.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    /**
     * 某团队收到了哪些对象的分享（不可变快照）。
     *
     * @return objectId 集合；无分享返回空集合
     */
    public Set<String> getObjectsSharedToTeam(String teamId) {
        final Set<String> result = new LinkedHashSet<>();
        if (teamId == null || teamId.isEmpty()) {
            return Collections.emptySet();
        }
        for (Map.Entry<String, Set<String>> entry : shares.entrySet()) {
            if (entry.getValue().contains(teamId)) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 有分享记录的对象数（调试 / 测试用）。 */
    public int size() {
        return shares.size();
    }

    /** 分享关系总数（objectId, teamId 对数；调试 / 测试用）。 */
    public int totalShares() {
        int total = 0;
        for (Set<String> teamIds : shares.values()) {
            total += teamIds.size();
        }
        return total;
    }

    // =====================================================================
    // 分享 / 取消分享
    // =====================================================================

    /**
     * 把一个对象分享给一个团队。
     *
     * <p><b>授权</b>（只有创建者能分享）由阶段 4 命令层负责；本方法只做数据层校验 /
     * 去重。<b>不校验 teamId 是否存在</b>——由调用方保证（加载后会统一清理孤儿）。</p>
     *
     * @param objectId 对象 id
     * @param teamId   团队 id
     * @return 新增了一条分享返回 true；已存在 / 非法参数返回 false
     */
    public boolean share(String objectId, String teamId) {
        if (objectId == null || objectId.isEmpty() || teamId == null || teamId.isEmpty()) {
            return false;
        }
        final boolean added;
        synchronized (lock) {
            final Set<String> teamIds = shares.computeIfAbsent(objectId, key -> ConcurrentHashMap.newKeySet());
            added = teamIds.add(teamId);
        }
        if (added) notifyChanged();
        return added;
    }

    /**
     * 取消“某对象 → 某团队”的分享。
     *
     * @return 确实删掉了一条分享才返回 true
     */
    public boolean unshare(String objectId, String teamId) {
        if (objectId == null || objectId.isEmpty() || teamId == null || teamId.isEmpty()) {
            return false;
        }
        final boolean removed;
        synchronized (lock) {
            final Set<String> teamIds = shares.get(objectId);
            if (teamIds == null || !teamIds.remove(teamId)) {
                removed = false;
            } else {
                if (teamIds.isEmpty()) {
                    shares.remove(objectId, teamIds);
                }
                removed = true;
            }
        }
        if (removed) notifyChanged();
        return removed;
    }

    // =====================================================================
    // 撤销（阶段 1 已约定的三个挂接点）
    // =====================================================================

    /**
     * 撤销“某玩家 P 作为创建者分享给团队 T”的全部对象。
     *
     * <p>触发时机：P 主动退队 / 被踢。只移除 shares[objectId] 里的 T；</p>
     * <ul>
     *   <li>不影响 P 分享给<b>其它团队</b>的对象；</li>
     *   <li>不影响<b>别人</b>（其它创建者）分享给 T 的对象。</li>
     * </ul>
     *
     * @param teamId    玩家离开的团队 id
     * @param playerUuid 离开的玩家（作为 objectId 的创建者判定）
     * @return 撤销掉的分享条数
     */
    public int revokeAllFromPlayer(String teamId, String playerUuid) {
        if (teamId == null || teamId.isEmpty() || playerUuid == null || playerUuid.isEmpty()) {
            return 0;
        }
        int count = 0;
        synchronized (lock) {
            for (Map.Entry<String, Set<String>> entry : shares.entrySet()) {
                final Set<String> teamIds = entry.getValue();
                if (!teamIds.contains(teamId)) {
                    continue;
                }
                // 只有“该对象确实由该玩家创建”才撤销，避免误删别人的分享
                if (!playerUuid.equals(creators.getCreator(entry.getKey()))) {
                    continue;
                }
                if (teamIds.remove(teamId)) {
                    count++;
                    if (teamIds.isEmpty()) {
                        shares.remove(entry.getKey(), teamIds);
                    }
                }
            }
        }
        if (count > 0) notifyChanged();
        return count;
    }

    /**
     * 撤销“指向某团队 T 的全部分享”（团队解散时调用）。
     *
     * @param teamId 解散的团队 id
     * @return 撤销掉的分享条数
     */
    public int revokeAllForTeam(String teamId) {
        if (teamId == null || teamId.isEmpty()) {
            return 0;
        }
        int count = 0;
        synchronized (lock) {
            for (Map.Entry<String, Set<String>> entry : shares.entrySet()) {
                final Set<String> teamIds = entry.getValue();
                if (teamIds.remove(teamId)) {
                    count++;
                    if (teamIds.isEmpty()) {
                        shares.remove(entry.getKey(), teamIds);
                    }
                }
            }
        }
        if (count > 0) notifyChanged();
        return count;
    }

    /**
     * 清理孤儿 teamId：移除 shares 里指向 {@link TeamData} 中已不存在的团队的分享。
     *
     * <p>由服务器启动（{@code TeamData.load()} 之后）调用，保证数据引用完整性。</p>
     *
     * @return 清理掉的分享条数
     */
    public int cleanupOrphanTeams() {
        int count = 0;
        synchronized (lock) {
            for (Map.Entry<String, Set<String>> entry : shares.entrySet()) {
                final Set<String> teamIds = entry.getValue();
                final int before = teamIds.size();
                teamIds.removeIf(teamId -> !teamExists.teamExists(teamId));
                count += before - teamIds.size();
                if (teamIds.isEmpty()) {
                    shares.remove(entry.getKey(), teamIds);
                }
            }
        }
        if (count > 0) notifyChanged();
        return count;
    }

    /** 变更监听器（S2C 推送用）。 */
    private static volatile Runnable changeListener = () -> {};
    public static void setChangeListener(Runnable listener) {
        changeListener = listener != null ? listener : () -> {};
    }
    private void notifyChanged() {
        try { changeListener.run(); } catch (Exception ignored) {}
    }

    /** 全部分享的快照（objectId → teamId 集合），S2C 同步用。 */
    public Map<String, Set<String>> getAllShares() {
        final Map<String, Set<String>> snapshot = new HashMap<>();
        synchronized (lock) {
            for (Map.Entry<String, Set<String>> e : shares.entrySet()) {
                snapshot.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            }
        }
        return snapshot;
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
     * 将内存中的分享数据写入 {@code config/mtrperm/shares.json}。
     *
     * <p>与其它数据类相同的两道保护：上次 load 失败 → 跳过；内存为空但文件非空 → 跳过。</p>
     */
    public void save() {
        synchronized (ioLock) {
            final Path f = resolveFile();
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载分享数据失败，跳过保存以避免覆盖损坏文件: {}", f);
                return;
            }
            try {
                if (shares.isEmpty() && Files.exists(f) && Files.size(f) > 0L) {
                    Mtrlock.LOGGER.warn("[mtrlock] 内存分享数据为空但文件非空，跳过保存以避免清空: {}", f);
                    return;
                }

                final Path dir = f.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }

                // 深拷贝快照，避免写盘期间其它线程改集合
                final Map<String, Set<String>> snapshot = new HashMap<>();
                for (Map.Entry<String, Set<String>> entry : shares.entrySet()) {
                    snapshot.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
                }

                try (BufferedWriter writer = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, MAP_TYPE, writer);
                }
                Mtrlock.LOGGER.info("[mtrlock] 已保存 {} 个对象的分享数据到 {}", snapshot.size(), f);
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存分享数据失败: {}", f, e);
            }
        }
    }

    /**
     * 从 {@code config/mtrperm/shares.json} 读取分享数据并替换内存内容。
     *
     * <p>文件不存在 → 重置失败标志后返回；文件损坏 → 保留原有内存、置 {@code loadFailed=true}，
     * 后续 save 跳过写盘。</p>
     *
     * <p>加载时过滤 null / 空 objectId、null teamId 与空集合（孤儿 teamId 由
     * {@link #cleanupOrphanTeams()} 在 TeamData 加载后统一清理）。</p>
     */
    public void load() {
        synchronized (ioLock) {
            final Path f = resolveFile();
            if (!Files.exists(f)) {
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 分享数据文件不存在，跳过加载: {}", f);
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                final Map<String, Set<String>> data = GSON.fromJson(reader, MAP_TYPE);

                synchronized (lock) {
                    shares.clear();
                    if (data != null) {
                        for (Map.Entry<String, Set<String>> entry : data.entrySet()) {
                            final String objectId = entry.getKey();
                            final Set<String> rawTeamIds = entry.getValue();
                            if (objectId == null || objectId.isEmpty() || rawTeamIds == null) {
                                continue;
                            }
                            final Set<String> teamIds = ConcurrentHashMap.newKeySet();
                            for (String teamId : rawTeamIds) {
                                if (teamId != null && !teamId.isEmpty()) {
                                    teamIds.add(teamId);
                                }
                            }
                            if (!teamIds.isEmpty()) {
                                shares.put(objectId, teamIds);
                            }
                        }
                    }
                }

                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 已加载 {} 个对象的分享数据", shares.size());
            } catch (IOException | JsonSyntaxException | JsonIOException e) {
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载分享数据失败，保留原有内存数据，后续 save 将跳过: {}", f, e);
            }
        }
    }
}
