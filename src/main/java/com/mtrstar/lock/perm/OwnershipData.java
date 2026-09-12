package com.mtrstar.lock.perm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.network.OwnershipSync;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 归属数据管理器（OwnershipData）。
 *
 * <p>MTR 的线路 / 车站 / 车厂对象没有“创建者”字段，因此本类在模组侧维护一张
 * {@code 对象ID -> 创建者UUID} 的映射表，用于权限判断（例如只有创建者能编辑）。</p>
 *
 * <p>数据结构：
 * <pre>
 * Map&lt;String, String&gt;
 *   key   = 对象ID，形如 "route:1a2b" / "station:abcd" / "depot:ff00"
 *   value = 创建者玩家 UUID 字符串（带不带连字符均可，由调用方统一约定）
 * </pre></p>
 *
 * <p>存储位置：{@code <游戏目录>/config/mtrperm/ownership.json}，使用 Gson 序列化。</p>
 *
 * <p>线程安全：
 * <ul>
 *   <li>内存容器使用 {@link ConcurrentHashMap}，单次 get/put/remove 天然线程安全；</li>
 *   <li>磁盘 IO（load/save）通过 {@link #ioLock} 互斥，保证不会并发读写同一文件。</li>
 * </ul></p>
 *
 * <p>生命周期：服务端启动时 {@link #load()}，关闭时 {@link #save()}，
 * 由 {@link Mtrlock} 中注册的服务器生命周期事件调用。</p>
 */
public final class OwnershipData {

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/ownership.json。 */
    private static final String FILE_NAME = "mtrperm/ownership.json";

    /**
     * 全局共享的 Gson 实例。
     * Gson 本身是线程安全的（无状态），可以复用，不必每次新建。
     * setPrettyPrinting 让 JSON 便于人工查看；disableHtmlEscaping 避免对 &gt; 等字符做多余转义。
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * {@code Map<String, String>} 的 Gson 类型令牌。
     * 由于 Java 泛型擦除，反序列化时必须显式提供完整泛型，否则会得到 Map&lt;String, Object&gt;。
     */
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    /**
     * 单例持有者。
     * 用 holder 延迟初始化：单元测试用 {@code new OwnershipData(Path)} 时不会触发
     * {@link #OwnershipData()} 里的 FabricLoader 调用。
     */
    private static final class InstanceHolder {
        private static final OwnershipData INSTANCE = new OwnershipData();
    }

    /** 获取全局唯一的归属数据实例。 */
    public static OwnershipData getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 内存数据本体：对象ID -> 创建者UUID。
     * 用 ConcurrentHashMap 保证单个操作的原子性与可见性。
     * 注意：ConcurrentHashMap 不允许 null key/value，因此 setCreator 会做非空校验。
     */
    private final Map<String, String> creators = new ConcurrentHashMap<>();

    /**
     * 磁盘 IO 互斥锁。
     * 只保护 load()/save()，不参与平时的 get/set，所以权限查询不会被写盘阻塞。
     */
    private final Object ioLock = new Object();

    /** 数据文件的绝对路径：{@code <游戏目录>/config/mtrperm/ownership.json}。 */
    private final Path file;

    /**
     * 上一次 {@link #load()} 是否失败（文件损坏）。
     * 为 true 时 {@link #save()} 会跳过写盘，避免用（可能为空的）内存覆盖坏文件。
     * 只在持有 {@link #ioLock} 的 load()/save() 内访问，故用普通 boolean 即可。
     */
    private boolean loadFailed;

    /** 生产构造：由服务器生命周期事件在合适的时机调用 load()/save() 完成持久化。 */
    private OwnershipData() {
        // FabricLoader.getConfigDir() 在开发环境指向 run/config，在生产环境指向 .minecraft/config
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    /** 包内可见构造：仅用于单元测试注入临时文件路径。 */
    OwnershipData(Path file) {
        this.file = file;
    }

    // ---------------------------------------------------------------------
    // 查询 / 修改（内存操作）
    // ---------------------------------------------------------------------

    /**
     * 查询某个对象的创建者。
     *
     * @param objectId 对象ID，例如 "route:1a2b"
     * @return 创建者 UUID 字符串；若该对象没有记录则返回 {@code null}
     */
    public String getCreator(String objectId) {
        if (objectId == null) {
            return null;
        }
        return creators.get(objectId);
    }

    /**
     * 记录某个对象的创建者（不存在则新增，已存在则覆盖）。
     *
     * @param objectId 对象ID
     * @param uuid     创建者 UUID 字符串
     */
    public void setCreator(String objectId, String uuid) {
        // ConcurrentHashMap 不接受 null，这里统一拦截，避免调用方传入脏数据时抛 NPE。
        if (objectId == null || objectId.isEmpty() || uuid == null) {
            return;
        }
        creators.put(objectId, uuid);
        // 功能 6：归属变更后向所有在线玩家推送全量快照（S2C）
        OwnershipSync.pushToAll();
    }

    /**
     * 移除某个对象的创建者记录（用于删除线路 / 车站 / 车厂时清理数据）。
     *
     * @param objectId 对象ID
     */
    public void removeCreator(String objectId) {
        if (objectId == null) {
            return;
        }
        creators.remove(objectId);
        // 功能 6：归属变更后向所有在线玩家推送全量快照（S2C）
        OwnershipSync.pushToAll();
    }

    /**
     * 判断某个对象是否已有创建者记录。
     *
     * @param objectId 对象ID
     * @return 存在返回 true
     */
    public boolean hasCreator(String objectId) {
        return objectId != null && creators.containsKey(objectId);
    }

    /**
     * 获取当前全部归属数据的只读快照（用于列表展示 / 反查某玩家的所有对象等）。
     * 返回的是拷贝，调用方修改它不会影响内部数据。
     *
     * @return 不可变的 对象ID -> UUID 映射
     */
    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(new HashMap<>(creators));
    }

    /** 当前记录条数。 */
    public int size() {
        return creators.size();
    }

    /** 仅供测试 / 调试：上一次 {@link #load()} 是否失败。 */
    boolean hasLoadFailed() {
        synchronized (ioLock) {
            return loadFailed;
        }
    }

    // ---------------------------------------------------------------------
    // 持久化（磁盘 IO）
    // ---------------------------------------------------------------------

    /**
     * 将内存中的归属数据写入 {@code config/mtrperm/ownership.json}。
     *
     * <p>整个写盘过程持有 {@link #ioLock}，因此多个线程同时 save、或 save 与 load 并发时不会互相破坏文件。
     * 写之前先做一份快照，避免写盘过程中其它线程修改 map 导致 ConcurrentModification。</p>
     */
    public void save() {
        synchronized (ioLock) {
            // 保护 1：上次 load 失败（文件损坏）→ 绝不能用当前内存覆盖坏文件
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载归属数据失败，跳过保存以避免覆盖损坏文件: {}", file);
                return;
            }

            try {
                // 保护 2：内存为空但磁盘上已有非空数据 → 跳过，防止误清空
                if (creators.isEmpty() && Files.exists(file) && Files.size(file) > 0L) {
                    Mtrlock.LOGGER.warn("[mtrlock] 内存归属数据为空但文件非空，跳过保存以避免清空: {}", file);
                    return;
                }

                // 1) 确保 config/mtrperm 目录存在（首次运行时目录可能还没创建）
                Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }

                // 2) 快照，保证转换 JSON 期间数据稳定
                Map<String, String> snapshot = new HashMap<>(creators);

                // 3) 用 UTF-8 写出；Gson 直接流式写到 Writer
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, MAP_TYPE, writer);
                }

                Mtrlock.LOGGER.info("[mtrlock] 已保存 {} 条归属数据到 {}", snapshot.size(), file);
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存归属数据失败: {}", file, e);
            }
        }
    }

    /**
     * 从 {@code config/mtrperm/ownership.json} 读取归属数据并替换内存内容。
     *
     * <p>文件不存在时直接返回（保持内存为空）并重置失败标志；文件损坏（JSON 语法错误）时记录日志、
     * 保留原有内存数据，并置 {@code loadFailed=true}，让后续 {@link #save()} 跳过写盘，
     * 避免坏文件被空数据覆盖。</p>
     */
    public void load() {
        synchronized (ioLock) {
            // 文件不存在：首次启动的正常情况，直接跳过（并清掉失败标志）
            if (!Files.exists(file)) {
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 归属数据文件不存在，跳过加载: {}", file);
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                // 先反序列化成功，再替换内存，避免解析失败时破坏现有数据
                Map<String, String> data = GSON.fromJson(reader, MAP_TYPE);

                creators.clear();
                if (data != null) {
                    for (Map.Entry<String, String> entry : data.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        // 过滤掉非法条目（JSON 里 null key/value 或空 key）
                        if (key != null && !key.isEmpty() && value != null) {
                            creators.put(key, value);
                        }
                    }
                }

                loadFailed = false; // 解析成功 → 恢复正常保存
                Mtrlock.LOGGER.info("[mtrlock] 已加载 {} 条归属数据", creators.size());
            } catch (IOException | JsonSyntaxException | JsonIOException e) {
                loadFailed = true;  // 解析失败 → 打标，后续 save() 跳过，避免覆盖坏文件
                Mtrlock.LOGGER.error("[mtrlock] 加载归属数据失败，保留原有内存数据，后续 save 将跳过: {}", file, e);
            }
        }
    }
}
