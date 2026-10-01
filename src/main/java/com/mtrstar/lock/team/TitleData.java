package com.mtrstar.lock.team;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员自定义称呼数据管理器（TitleData）。
 *
 * <p>语义：OP 3+ 可给任意玩家设置一段自定义称呼（title）与可选颜色。显示名字前缀的优先级为
 * <b>自定义称呼 &gt; 团队前缀 &gt; {@value TeamPrefix#NO_TEAM}</b>；称呼是<b>完整显示</b>的
 * （不截断），团队名前缀才截 {@value TeamPrefix#PREFIX_CHARS} 个 code point。
 * <b>颜色只影响样式，不影响优先级</b>。</p>
 *
 * <p>数据结构（1.2.4）：{@code ConcurrentHashMap<String, TitleEntry>}（playerUuid → 文本 + 颜色），
 * 持久化到 {@code config/mtrperm/titles.json}。颜色统一为 {@code #RRGGBB} 大写或 null。</p>
 *
 * <p><b>旧数据兼容</b>：1.2.3 及更早的文件是 {@code {"uuid": "称呼"}} 的字符串映射；
 * {@link #load()} 同时接受旧字符串与新对象 {@code {"uuid": {"text": "...", "color": "#RRGGBB"}}}
 * 两种形态，旧称号读入后 {@code color = null}；{@link #save()} 一律写新格式。</p>
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

    /** 与其它数据类同款 Gson 配置（只用于序列化 JsonObject，反序列化手工解析以兼容旧格式）。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 懒加载 holder：单元测试用 {@code new TitleData(Path)} 时不会触发 FabricLoader。 */
    private static final class InstanceHolder {
        private static final TitleData INSTANCE = new TitleData();
    }

    /** 全局唯一的称呼数据实例。 */
    public static TitleData getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /** playerUuid → TitleEntry。 */
    private final Map<String, TitleEntry> titles = new ConcurrentHashMap<>();

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

    /** 某玩家的自定义称呼文本；没有 / 非法参数返回 null。 */
    public String getTitle(String playerUuid) {
        final TitleEntry entry = playerUuid == null ? null : titles.get(playerUuid);
        return entry == null ? null : entry.text();
    }

    /** 某玩家的自定义称呼颜色（{@code #RRGGBB}）；没有 / 无色返回 null。 */
    public String getColor(String playerUuid) {
        final TitleEntry entry = playerUuid == null ? null : titles.get(playerUuid);
        return entry == null ? null : entry.color();
    }

    /** 某玩家的完整称呼条目；没有返回 null。 */
    public TitleEntry getEntry(String playerUuid) {
        return playerUuid == null ? null : titles.get(playerUuid);
    }

    /** 是否有称呼记录。 */
    public boolean hasTitle(String playerUuid) {
        return playerUuid != null && titles.containsKey(playerUuid);
    }

    /**
     * 设置称呼文本（<b>保留</b>已有颜色）。
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
        final TitleEntry existing = titles.get(playerUuid);
        final String color = existing == null ? null : existing.color();
        titles.put(playerUuid, new TitleEntry(valid, color));
        notifyChanged();
        return true;
    }

    /**
     * 设置称呼文本 + 颜色（颜色为 null / 空表示清除颜色）。
     *
     * @param playerUuid 目标玩家 UUID
     * @param title      新称呼文本
     * @param color      颜色输入（{@code red} / {@code &a} / {@code #RRGGBB} / {@code &x...}）；null / 空 = 无色
     * @return 文本与颜色都合法并写入才返回 true；颜色非法返回 false
     */
    public boolean setTitle(String playerUuid, String title, String color) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final String valid = validateTitle(title);
        if (valid == null) {
            return false;
        }
        final ColorParser.Result parsed = ColorParser.parse(color);
        if (!parsed.valid()) {
            return false;
        }
        titles.put(playerUuid, new TitleEntry(valid, parsed.color()));
        notifyChanged();
        return true;
    }

    /**
     * 只设置颜色（保留文本）。
     *
     * @param playerUuid 目标玩家 UUID（必须已有称呼）
     * @param color      颜色输入；null / 空 = 清除颜色
     * @return 已有称呼且颜色合法才返回 true
     */
    public boolean setColor(String playerUuid, String color) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final TitleEntry existing = titles.get(playerUuid);
        if (existing == null) {
            return false;
        }
        final ColorParser.Result parsed = ColorParser.parse(color);
        if (!parsed.valid()) {
            return false;
        }
        titles.put(playerUuid, new TitleEntry(existing.text(), parsed.color()));
        notifyChanged();
        return true;
    }

    /**
     * 清除颜色（保留文本）。
     *
     * @return 已有称呼才返回 true（已经是无色时返回 false）
     */
    public boolean resetColor(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        final TitleEntry existing = titles.get(playerUuid);
        if (existing == null || existing.color() == null) {
            return false;
        }
        titles.put(playerUuid, existing.withoutColor());
        notifyChanged();
        return true;
    }

    /**
     * 清除称呼（连同颜色）。
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

    /** 全部称呼文本的不可变快照（向后兼容：1.2.3 的 {@code Map<String,String>} 语义）。 */
    public Map<String, String> getAll() {
        final Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, TitleEntry> entry : titles.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().text());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** 全部称呼条目的不可变快照（文本 + 颜色）。 */
    public Map<String, TitleEntry> getAllEntries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(titles));
    }

    /** 只含有颜色称呼的不可变快照（playerUuid → {@code #RRGGBB}）。 */
    public Map<String, String> getAllColors() {
        final Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, TitleEntry> entry : titles.entrySet()) {
            if (entry.getValue().color() != null) {
                snapshot.put(entry.getKey(), entry.getValue().color());
            }
        }
        return Collections.unmodifiableMap(snapshot);
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
     * 将内存中的称呼写入 {@code config/mtrperm/titles.json}（1.2.4 新格式，对象形式）。
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

                final JsonObject root = new JsonObject();
                for (Map.Entry<String, TitleEntry> entry : new LinkedHashMap<>(titles).entrySet()) {
                    final JsonObject value = new JsonObject();
                    value.addProperty("text", entry.getValue().text());
                    if (entry.getValue().color() != null) {
                        value.addProperty("color", entry.getValue().color());
                    }
                    root.add(entry.getKey(), value);
                }

                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                }
                Mtrlock.LOGGER.info("[mtrlock] 已保存 {} 条称呼到 {}", root.size(), file);
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存称呼数据失败: {}", file, e);
            }
        }
    }

    /**
     * 从 {@code config/mtrperm/titles.json} 读取称呼数据并替换内存内容。
     *
     * <p>文件不存在 / 空文件 → 重置失败标志；JSON 损坏 → 记录日志、保留原有内存、置
     * {@code loadFailed=true}，后续 save 跳过写盘。加载时同时接受 1.2.3 的字符串格式与
     * 1.2.4 的对象格式，并过滤 key / 文本非法的条目。</p>
     */
    public void load() {
        synchronized (ioLock) {
            if (!Files.exists(file)) {
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 称呼数据文件不存在，跳过加载: {}", file);
                return;
            }

            try {
                final String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (json.trim().isEmpty()) {
                    titles.clear();
                    loadFailed = false;
                    Mtrlock.LOGGER.info("[mtrlock] 称呼数据文件为空，按空数据加载: {}", file);
                    return;
                }

                final JsonElement parsed = JsonParser.parseString(json);
                if (parsed == null || parsed.isJsonNull()) {
                    titles.clear();
                    loadFailed = false;
                    return;
                }
                if (!parsed.isJsonObject()) {
                    loadFailed = true;
                    Mtrlock.LOGGER.error("[mtrlock] 称呼数据不是 JSON 对象，保留原有内存数据，后续 save 将跳过: {}", file);
                    return;
                }

                final JsonObject root = parsed.getAsJsonObject();
                final Map<String, TitleEntry> loaded = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    final String key = entry.getKey();
                    final TitleEntry value = readEntry(entry.getValue());
                    if (key == null || key.isEmpty() || value == null) {
                        Mtrlock.LOGGER.warn("[mtrlock] 跳过非法的称呼条目: uuid={}, value={}", key, entry.getValue());
                        continue;
                    }
                    loaded.put(key, value);
                }

                titles.clear();
                titles.putAll(loaded);
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 已加载 {} 条称呼", titles.size());
            } catch (IOException | JsonSyntaxException | JsonIOException | IllegalStateException e) {
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载称呼数据失败，保留原有内存数据，后续 save 将跳过: {}", file, e);
            }
        }
    }

    /**
     * 读取单条称呼：兼容 1.2.3 的字符串与 1.2.4 的对象。
     *
     * @return 合法条目；无法识别 / 文本非法返回 null
     */
    private static TitleEntry readEntry(JsonElement element) {
        if (element == null) {
            return null;
        }
        String text = null;
        String color = null;

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            // 1.2.3 旧格式：uuid → "称呼"
            text = element.getAsString();
        } else if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            final JsonElement textElement = object.get("text");
            if (textElement != null && textElement.isJsonPrimitive() && textElement.getAsJsonPrimitive().isString()) {
                text = textElement.getAsString();
            }
            final JsonElement colorElement = object.get("color");
            if (colorElement != null && colorElement.isJsonPrimitive() && colorElement.getAsJsonPrimitive().isString()) {
                color = colorElement.getAsString();
            }
        } else {
            return null;
        }

        final String valid = validateTitle(text);
        if (valid == null) {
            return null;
        }
        // 非法颜色按无色处理（不因一个坏颜色丢掉整条称呼）
        return new TitleEntry(valid, ColorParser.normalize(color));
    }
}
