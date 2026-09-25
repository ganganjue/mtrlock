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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员自定义称呼数据管理器（TitleData）。
 *
 * <p>语义：OP 3+ 可给任意玩家设置一段自定义称呼（title）。显示名字前缀的优先级为
 * <b>自定义称呼 &gt; 团队前缀 &gt; {@value TeamPrefix#NO_TEAM}</b>；称呼是<b>完整显示</b>的
 * （不截断），团队名前缀才截 {@value TeamPrefix#PREFIX_CHARS} 个 code point。</p>
 *
 * <p>数据结构：{@code ConcurrentHashMap<String, String>}（playerUuid → title），
 * 持久化到 {@code config/mtrperm/titles.json}。</p>
 *
 * <p>与 {@link TeamData} / {@link ShareData} 同级模式：单例 + 懒加载 holder +
 * 包内 {@link #TitleData(Path)} 测试构造 + {@code loadFailed} 坏文件保护 +
 * 变更监听器（S2C 推送用）。</p>
 */
public final class TitleData {

    /** 称呼最大长度（按 Unicode code point 计，中文 / emoji 都按“字符”算）。 */
    public static final int MAX_TITLE_LENGTH = 16;

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/titles.json。 */
    private static final String FILE_NAME = "mtrperm/titles.json";

    /** 与其它数据类同款 Gson 配置。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** {@code Map<String, String>} 的 Gson 类型令牌。 */
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    /** 懒加载 holder：单元测试用 {@code new TitleData(Path)} 时不会触发 FabricLoader。 */
    private static final class InstanceHolder {
        private static final TitleData INSTANCE = new TitleData();
    }

    /** 全局唯一的称呼数据实例。 */
    public static TitleData getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /** playerUuid → title。 */
    private final Map<String, String> titles = new ConcurrentHashMap<>();

    /** 磁盘 IO 互斥锁，只保护 load()/save()。 */
    private final Object ioLock = new Object();

    /** 数据文件绝对路径。 */
    private final Path file;

    /** 上一次 load() 是否失败（坏文件保护，语义同 OwnershipData / TeamData）。 */
    private boolean loadFailed;

    /** 生产构造：延迟到真正调用时才解析 FabricLoader 配置目录。 */
    private TitleData() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    /** 包内可见构造：仅用于单元测试注入临时文件路径。 */
    TitleData(Path file) {
        this.file = file;
    }

    // =====================================================================
    // 校验（纯函数，可单测）
    // =====================================================================

    /**
     * 校验并规范化称呼。
     *
     * <p>规则：null / 空 / 全空白 → null；去掉首尾空白后按 code point 计长度 &gt;
     * {@value #MAX_TITLE_LENGTH} → null；含 ISO 控制字符 → null；否则返回去掉首尾空白后的称呼。</p>
     *
     * @param raw 原始称呼
     * @return 规范化后的称呼；非法时返回 {@code null}
     */
    public static String validateTitle(String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_TITLE_LENGTH) {
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

    /** 称呼是否合法（{@link #validateTitle(String)} 的布尔版）。 */
    public static boolean isValidTitle(String raw) {
        return validateTitle(raw) != null;
    }

    // =====================================================================
    // 读写
    // =====================================================================

    /** 某玩家的自定义称呼；没有 / 非法参数返回 null。 */
    public String getTitle(String playerUuid) {
        return playerUuid == null ? null : titles.get(playerUuid);
    }

    /**
     * 设置称呼（覆盖旧值）。
     *
     * @param playerUuid 目标玩家 UUID
     * @param title      新称呼（≤ {@value #MAX_TITLE_LENGTH} code point、无控制字符、非空）
     * @return 校验通过并写入才返回 true
     */
    public boolean setTitle(String playerUuid, String title) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final String valid = validateTitle(title);
        if (valid == null) {
            return false;
        }
        titles.put(playerUuid, valid);
        notifyChanged();
        return true;
    }

    /**
     * 清除称呼。
     *
     * @param playerUuid 目标玩家 UUID
     * @return 确实删掉了一条称呼才返回 true
     */
    public boolean clearTitle(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final boolean removed = titles.remove(playerUuid) != null;
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    /** 全部称呼的不可变快照。 */
    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(titles));
    }

    /** 称呼总数（调试 / 测试用）。 */
    public int size() {
        return titles.size();
    }

    // =====================================================================
    // 变更监听器（S2C 推送用）
    // =====================================================================

    /** 变更监听器。 */
    private static volatile Runnable changeListener = () -> {
    };

    public static void setChangeListener(Runnable listener) {
        changeListener = listener != null ? listener : () -> {
        };
    }

    private static void notifyChanged() {
        try {
            changeListener.run();
        } catch (Exception ignored) {
        }
    }

    /** 仅供测试 / 调试：上一次 load() 是否失败。 */
    public boolean hasLoadFailed() {
        synchronized (ioLock) {
            return loadFailed;
        }
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    /**
     * 将内存中的称呼写入 {@code config/mtrperm/titles.json}。
     *
     * <p>与 {@link TeamData} 相同的两道保护：</p>
     * <ol>
     *   <li>上次 load 失败（坏文件）→ 跳过写盘，绝不覆盖坏文件；</li>
     *   <li>内存为空但文件非空 → 跳过，避免空内存误清空文件。</li>
     * </ol>
     */
    public void save() {
        synchronized (ioLock) {
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载称呼数据失败，跳过保存以避免覆盖损坏文件: {}", file);
                return;
            }
            try {
                if (titles.isEmpty() && Files.exists(file) && Files.size(file) > 0L) {
                    Mtrlock.LOGGER.warn("[mtrlock] 内存称呼数据为空但文件非空，跳过保存以避免清空: {}", file);
                    return;
                }

                final Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }

                final Map<String, String> snapshot = new LinkedHashMap<>(titles);
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, MAP_TYPE, writer);
                }
                Mtrlock.LOGGER.info("[mtrlock] 已保存 {} 条称呼到 {}", snapshot.size(), file);
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存称呼数据失败: {}", file, e);
            }
        }
    }

    /**
     * 从 {@code config/mtrperm/titles.json} 读取称呼数据并替换内存内容。
     *
     * <p>文件不存在 → 重置失败标志后返回；文件损坏 → 记录日志、保留原有内存、置
     * {@code loadFailed=true}，后续 save 跳过写盘。加载时过滤 key / value 非法（空、
     * 超长、含控制字符）的条目。</p>
     */
    public void load() {
        synchronized (ioLock) {
            if (!Files.exists(file)) {
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 称呼数据文件不存在，跳过加载: {}", file);
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                final Map<String, String> data = GSON.fromJson(reader, MAP_TYPE);

                titles.clear();
                if (data != null) {
                    for (Map.Entry<String, String> entry : data.entrySet()) {
                        final String key = entry.getKey();
                        final String valid = validateTitle(entry.getValue());
                        if (key == null || key.isEmpty() || valid == null) {
                            Mtrlock.LOGGER.warn("[mtrlock] 跳过非法的称呼条目: uuid={}, title={}", key, entry.getValue());
                            continue;
                        }
                        titles.put(key, valid);
                    }
                }

                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 已加载 {} 条称呼", titles.size());
            } catch (IOException | JsonSyntaxException | JsonIOException e) {
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载称呼数据失败，保留原有内存数据，后续 save 将跳过: {}", file, e);
            }
        }
    }
}
