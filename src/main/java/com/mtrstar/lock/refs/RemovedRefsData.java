package com.mtrstar.lock.refs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mtrstar.lock.Mtrlock;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 被移除的线路引用账本（1.4.0）。
 *
 * <p>当线路 owner 对线路引用的某个站台失去权限时，{@code RouteRefReconciler} 会把该站台
 * 从线路的 {@code routePlatformData} 临时移除，并在这里记一笔；权限恢复后自动加回并删记录。
 * MTR 会在 autosave 时把「少了一个站台」的线路写进存档，因此本文件是这些引用的
 * <b>唯一恢复源</b>，坏文件保护 / 节流落盘都是围绕这一点设计的。</p>
 *
 * <p>数据结构（{@code Map<String, RemovedRefEntry>}）：</p>
 * <pre>
 *   key   = routeId，形如 "route:0B0829457F350DE9"
 *   value = { routeId, refs: [ { platformId, stationObjectId, removedAt }, ... ] }
 * </pre>
 *
 * <p>写法与 {@code ShareData} 同构，坏文件保护与 {@code OwnershipData} 同构；额外两条：</p>
 * <ul>
 *   <li><b>覆盖不追加</b>：同一 (route, platform) 反复失权 / 恢复只保留一条记录，记录不会膨胀；</li>
 *   <li><b>节流落盘</b>：变更后 {@value #SAVE_DEBOUNCE_MILLIS} 毫秒 debounce
 *       <b>或</b> {@value #SAVE_EVERY_N_RECONCILES} 次对账先到先落，避免每次 {@code Data#sync()}
 *       都写盘（对账会被开 dashboard / 拉列表这类只读路径触发）。</li>
 * </ul>
 *
 * <p>线程安全：内存读写用 {@link #lock}，磁盘 IO 用 {@link #ioLock}，两者不互相嵌套加锁。</p>
 */
public final class RemovedRefsData {

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/removed_refs.json。 */
    private static final String FILE_NAME = "mtrperm/removed_refs.json";

    /** 变更后的落盘 debounce 窗口（毫秒）。 */
    public static final long SAVE_DEBOUNCE_MILLIS = 5_000L;

    /** 最多累计多少次对账就必须落一次盘（即使 debounce 还没到）。 */
    public static final int SAVE_EVERY_N_RECONCILES = 10;

    /** 记录保留天数：超过这个天数的记录在启动时清理（站台从此视为永久移除）。 */
    public static final int RETENTION_DAYS = 30;

    /** {@value #RETENTION_DAYS} 天对应的毫秒数。 */
    public static final long RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 懒加载 holder：单测用包内构造时不会触发 FabricLoader。 */
    private static final class InstanceHolder {
        private static final RemovedRefsData INSTANCE = new RemovedRefsData(null, System::currentTimeMillis);
    }

    /** 全局唯一的引用账本实例。 */
    public static RemovedRefsData getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 一条被移除的引用。
     *
     * <p>{@code stationObjectId} 允许为 null：车站已被删除时没有可记录的父车站 id。</p>
     */
    public static final class RemovedRefEntry {

        /** 被移除的站台 id（MTR 数值 id）。 */
        public long platformId;

        /** 父车站 objectId（{@code station:<HEX>}）；父车站已删时为 null。 */
        public String stationObjectId;

        /** 移除时刻（epoch 毫秒），用于 30 天清理。 */
        public long removedAt;

        /** Gson 反序列化用。 */
        public RemovedRefEntry() {
        }

        public RemovedRefEntry(long platformId, String stationObjectId, long removedAt) {
            this.platformId = platformId;
            this.stationObjectId = stationObjectId;
            this.removedAt = removedAt;
        }

        @Override
        public String toString() {
            return "platform=" + platformId + ", station=" + stationObjectId + ", removedAt=" + removedAt;
        }
    }

    /** 一条线路的记录：避免 JSON 里出现孤立的 {refs:[...]} 形状，读起来更直观。 */
    public static final class RemovedRefEntryList {

        public String routeId;
        public List<RemovedRefEntry> refs = new ArrayList<>();

        public RemovedRefEntryList() {
        }

        public RemovedRefEntryList(String routeId) {
            this.routeId = routeId;
        }
    }

    /** routeId → 被移除引用列表。 */
    private final Map<String, List<RemovedRefEntry>> removed = new LinkedHashMap<>();

    /** 复合内存操作的互斥锁。 */
    private final Object lock = new Object();

    /** 磁盘 IO 互斥锁，只保护 load()/save()。 */
    private final Object ioLock = new Object();

    /** 数据文件绝对路径；null 表示生产单例，延迟到第一次 IO 才解析 FabricLoader。 */
    private final Path file;

    /** 时间源（测试注入假时钟）。 */
    private final LongSupplier clock;

    /** 上一次 load() 是否失败（坏文件保护）。为 true 时本轮对账完全跳过、save 也跳过。 */
    private boolean loadFailed;

    /** 自上次落盘以来是否有变更。 */
    private boolean dirty;

    /** 本轮变更窗口的起始时刻（毫秒）；无变更时为 0。 */
    private long dirtySinceMillis;

    /** 自上次落盘以来经过了多少次对账。 */
    private int reconcilesSinceSave;

    /**
     * 包内可见构造：生产单例用 {@code (null, System::currentTimeMillis)} 延迟解析 config 目录，
     * 单元测试注入临时文件与假时钟。
     */
    RemovedRefsData(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    /** 解析数据文件路径；生产单例延迟到第一次 IO 时才触碰 FabricLoader。 */
    private Path resolveFile() {
        final Path current = file;
        return current != null ? current : FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /** 上一次 {@link #load()} 是否失败；为 true 时本轮对账必须完全跳过。 */
    public boolean isLoadFailed() {
        synchronized (ioLock) {
            return loadFailed;
        }
    }

    /** 当前有多少条线路带被移除的引用（调试 / 测试 / 命令用）。 */
    public int routeCount() {
        synchronized (lock) {
            return removed.size();
        }
    }

    /** 被移除引用总条数（调试 / 测试 / 命令用）。 */
    public int size() {
        synchronized (lock) {
            int total = 0;
            for (List<RemovedRefEntry> refs : removed.values()) {
                total += refs.size();
            }
            return total;
        }
    }

    /** 全部记录的不可变快照：routeId → 引用列表（S2C / 命令展示用）。 */
    public Map<String, List<RemovedRefEntry>> getAll() {
        synchronized (lock) {
            final Map<String, List<RemovedRefEntry>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<RemovedRefEntry>> entry : removed.entrySet()) {
                snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Collections.unmodifiableMap(snapshot);
        }
    }

    /** 某条线路被移除的引用（不可变快照）；没有记录时返回空列表。 */
    public List<RemovedRefEntry> getRemoved(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return List.of();
        }
        synchronized (lock) {
            final List<RemovedRefEntry> refs = removed.get(routeId);
            return refs == null ? List.of() : List.copyOf(refs);
        }
    }

    /** 某条线路的某站台是否有被移除记录。 */
    public boolean hasRemoved(String routeId, long platformId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            final List<RemovedRefEntry> refs = removed.get(routeId);
            if (refs == null) {
                return false;
            }
            for (RemovedRefEntry entry : refs) {
                if (entry.platformId == platformId) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 数据文件路径（命令展示用；生产环境会触碰 FabricLoader）。 */
    public Path file() {
        return resolveFile();
    }

    /**
     * 强制落盘（服务器正常关闭 / shutdown hook 用）。
     *
     * <p>与 {@link #save()} 的区别只是语义：不存在「没变更就不写」的判断，
     * 无条件写一次（仍然受 loadFailed 保护）。</p>
     */
    public void flush() {
        save();
    }

    // =====================================================================
    // 记录 / 撤销记录（只由对账器与命令调用）
    // =====================================================================

    /**
     * 记录一条「线路对某站台失去权限」的引用（覆盖式，不追加）。
     *
     * <p>同一 (routeId, platformId) 反复失权时只更新 {@code stationObjectId} 与 {@code removedAt}，
     * 列表长度不变，因此反复横跳不会让记录膨胀。</p>
     *
     * @return 新增或内容有变化返回 true；完全重复返回 false
     */
    boolean recordRemoved(String routeId, long platformId, String stationObjectId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }
        final long now = clock.getAsLong();
        final boolean changed;
        synchronized (lock) {
            final List<RemovedRefEntry> refs = removed.computeIfAbsent(routeId, key -> new ArrayList<>());
            RemovedRefEntry target = null;
            for (RemovedRefEntry entry : refs) {
                if (entry.platformId == platformId) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                refs.add(new RemovedRefEntry(platformId, stationObjectId, now));
                changed = true;
            } else if (!Objects.equals(target.stationObjectId, stationObjectId)) {
                target.stationObjectId = stationObjectId;
                target.removedAt = now;
                changed = true;
            } else {
                changed = false;
            }
        }
        if (changed) {
            markDirty();
        }
        return changed;
    }

    /**
     * 删除某条线路某站台的记录（权限恢复 / 手动 restore 后调用）。
     *
     * @return 确实删掉了一条记录返回 true
     */
    boolean removeRemoved(String routeId, long platformId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }
        final boolean removedOne;
        synchronized (lock) {
            final List<RemovedRefEntry> refs = removed.get(routeId);
            if (refs == null) {
                removedOne = false;
            } else {
                removedOne = refs.removeIf(entry -> entry.platformId == platformId);
                if (refs.isEmpty()) {
                    removed.remove(routeId);
                }
            }
        }
        if (removedOne) {
            markDirty();
        }
        return removedOne;
    }

    /** 删掉整条线路的记录（幽灵清理用），返回删掉的条数。 */
    int removeRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return 0;
        }
        final int count;
        synchronized (lock) {
            final List<RemovedRefEntry> refs = removed.remove(routeId);
            count = refs == null ? 0 : refs.size();
        }
        if (count > 0) {
            markDirty();
        }
        return count;
    }

    // =====================================================================
    // 30 天清理 / 节流落盘
    // =====================================================================

    /**
     * 清理 {@code removedAt} 超过 {@value #RETENTION_DAYS} 天的记录。
     *
     * <p>由服务器启动（{@link #load()} 之后）调用。被清掉的站台从此视为永久移除，
     * 不再自动加回。</p>
     *
     * @return 清理掉的记录条数
     */
    public int cleanupExpired() {
        final long cutoff = clock.getAsLong() - RETENTION_MILLIS;
        int count = 0;
        synchronized (lock) {
            final java.util.Iterator<Map.Entry<String, List<RemovedRefEntry>>> routes = removed.entrySet().iterator();
            while (routes.hasNext()) {
                final List<RemovedRefEntry> refs = routes.next().getValue();
                final int before = refs.size();
                refs.removeIf(entry -> entry.removedAt > 0L && entry.removedAt < cutoff);
                count += before - refs.size();
                if (refs.isEmpty()) {
                    routes.remove();
                }
            }
        }
        if (count > 0) {
            markDirty();
            Mtrlock.LOGGER.info("[mtrlock] 已清理 {} 条超过 {} 天的线路引用记录", count, RETENTION_DAYS);
        }
        return count;
    }

    /** 标记「有变更」，开始/继续一个落盘窗口。 */
    private void markDirty() {
        synchronized (lock) {
            if (!dirty) {
                dirty = true;
                dirtySinceMillis = clock.getAsLong();
            }
        }
    }

    /**
     * 记录一次对账；若满足节流条件则落盘。
     *
     * <p>条件（先到先落）：距本轮首次变更 ≥ {@value #SAVE_DEBOUNCE_MILLIS} 毫秒，
     * 或累计对账次数 ≥ {@value #SAVE_EVERY_N_RECONCILES}。</p>
     */
    void afterReconcile() {
        final boolean due;
        synchronized (lock) {
            reconcilesSinceSave++;
            due = dirty && (reconcilesSinceSave >= SAVE_EVERY_N_RECONCILES
                    || clock.getAsLong() - dirtySinceMillis >= SAVE_DEBOUNCE_MILLIS);
        }
        if (due) {
            save();
        }
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    /** 供测试断言用：某路径文件当前内容。 */
    static String readFile(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }

    /**
     * 把内存记录写入 {@code config/mtrperm/removed_refs.json}。
     *
     * <p>保护 1：上次 load 失败 → 跳过写盘（避免覆盖损坏文件，否则恢复源就永久丢了）。
     * <b>不做</b>「内存为空但文件非空就跳过」——账本为空是合法的正常状态（都恢复了 / 都清理了），
     * 若照搬 ShareData 的守卫会让「最后一条记录被清掉」永远落不了盘。</p>
     */
    public void save() {
        synchronized (ioLock) {
            final Path f = resolveFile();
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载引用账本失败，跳过保存以避免覆盖损坏文件: {}", f);
                return;
            }
            try {
                final Path dir = f.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }

                final Map<String, RemovedRefEntryList> snapshot;
                synchronized (lock) {
                    snapshot = new LinkedHashMap<>();
                    for (Map.Entry<String, List<RemovedRefEntry>> entry : removed.entrySet()) {
                        final RemovedRefEntryList list = new RemovedRefEntryList(entry.getKey());
                        for (RemovedRefEntry ref : entry.getValue()) {
                            list.refs.add(new RemovedRefEntry(ref.platformId, ref.stationObjectId, ref.removedAt));
                        }
                        snapshot.put(entry.getKey(), list);
                    }
                }

                try (BufferedWriter writer = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, writer);
                }

                synchronized (lock) {
                    dirty = false;
                    dirtySinceMillis = 0L;
                    reconcilesSinceSave = 0;
                }
                Mtrlock.LOGGER.info("[mtrlock] 已保存 {} 条线路的 {} 条引用移除记录到 {}",
                        snapshot.size(), countRefs(snapshot), f);
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存引用账本失败: {}", f, e);
            }
        }
    }

    private static int countRefs(Map<String, RemovedRefEntryList> snapshot) {
        int total = 0;
        for (RemovedRefEntryList list : snapshot.values()) {
            total += list.refs.size();
        }
        return total;
    }

    /**
     * 从 {@code config/mtrperm/removed_refs.json} 读取记录并替换内存内容。
     *
     * <p>文件不存在 → 视为空账本（首次启动），重置失败标志；文件损坏 → 保留原有内存、
     * 置 {@code loadFailed=true}，本轮对账完全跳过、后续 save 也跳过。</p>
     */
    public void load() {
        synchronized (ioLock) {
            final Path f = resolveFile();
            if (!Files.exists(f)) {
                synchronized (lock) {
                    removed.clear();
                }
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 引用账本不存在（首次启动），按空账本处理: {}", f);
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                // 先解析成功，再替换内存，避免坏文件破坏现有数据
                final Map<String, RemovedRefEntryList> data = parse(GSON.fromJson(reader, JsonElement.class));

                synchronized (lock) {
                    removed.clear();
                    removed.putAll(toMap(data));
                }

                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 已加载 {} 条线路的 {} 条引用移除记录", routeCount(), size());
            } catch (IOException | JsonSyntaxException | JsonIOException | IllegalStateException e) {
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载引用账本失败，本轮对账将跳过，后续 save 也将跳过: {}", f, e);
            }
        }
    }

    /**
     * 逐字段解析：坏条目单独丢弃，不影响整份文件；但根不是 JSON 对象视为坏文件。
     *
     * @throws IllegalStateException 根存在但不是 JSON 对象（由 {@link #load()} 捕获后置 loadFailed）
     */
    private static Map<String, RemovedRefEntryList> parse(JsonElement root) {
        final Map<String, RemovedRefEntryList> result = new LinkedHashMap<>();
        if (root == null || root.isJsonNull()) {
            return result;
        }
        if (!root.isJsonObject()) {
            // 数组 / 字符串 / 数字：不是本文件的结构 → 当坏文件处理，绝不覆盖
            throw new IllegalStateException("引用账本根节点不是 JSON 对象: " + root);
        }
        for (Map.Entry<String, JsonElement> routeEntry : root.getAsJsonObject().entrySet()) {
            final String routeId = routeEntry.getKey();
            if (routeId == null || routeId.isEmpty() || !routeEntry.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject routeObject = routeEntry.getValue().getAsJsonObject();
            final JsonElement refsElement = routeObject.get("refs");
            if (refsElement == null || !refsElement.isJsonArray()) {
                continue;
            }
            final RemovedRefEntryList list = new RemovedRefEntryList(routeId);
            for (JsonElement refElement : refsElement.getAsJsonArray()) {
                if (!refElement.isJsonObject()) {
                    continue;
                }
                final JsonObject refObject = refElement.getAsJsonObject();
                final JsonElement platformElement = refObject.get("platformId");
                if (platformElement == null || !platformElement.isJsonPrimitive()) {
                    continue;
                }
                try {
                    final long platformId = platformElement.getAsLong();
                    final String stationObjectId = refObject.has("stationObjectId")
                            && refObject.get("stationObjectId").isJsonPrimitive()
                            ? refObject.get("stationObjectId").getAsString() : null;
                    final long removedAt = refObject.has("removedAt")
                            && refObject.get("removedAt").isJsonPrimitive()
                            ? refObject.get("removedAt").getAsLong() : 0L;
                    list.refs.add(new RemovedRefEntry(platformId, stationObjectId, removedAt));
                } catch (NumberFormatException | UnsupportedOperationException ignored) {
                    // 单条坏记录直接跳过
                }
            }
            if (!list.refs.isEmpty()) {
                result.put(routeId, list);
            }
        }
        return result;
    }

    /** 把解析结果转成内存结构，并按 (route, platform) 去重（保留最后一条）。 */
    private static Map<String, List<RemovedRefEntry>> toMap(Map<String, RemovedRefEntryList> parsed) {
        final Map<String, List<RemovedRefEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<String, RemovedRefEntryList> entry : parsed.entrySet()) {
            final Map<Long, RemovedRefEntry> byPlatform = new LinkedHashMap<>();
            for (RemovedRefEntry ref : entry.getValue().refs) {
                byPlatform.put(ref.platformId, ref);
            }
            result.put(entry.getKey(), new ArrayList<>(byPlatform.values()));
        }
        return result;
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** MTR 数值 id → objectId 的 objectId 后缀（16 位大写 HEX）。 */
    public static String objectIdOf(String prefix, long id) {
        return prefix + ":" + org.mtr.core.tool.Utilities.numberToPaddedHexString(id);
    }

    /** 站台 id → platform objectId。 */
    public static String platformObjectId(long platformId) {
        return objectIdOf("platform", platformId);
    }

    /** 车站 id → station objectId。 */
    public static String stationObjectId(long stationId) {
        return objectIdOf("station", stationId);
    }
}
