package com.mtrstar.lock.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mtrstar.lock.Mtrlock;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 显示相关配置（1.2.4）。
 *
 * <p>目前只有一个开关：{@code %mtrlock:title_colored%} 的输出格式，
 * 持久化到 {@code config/mtrperm/display.json}：</p>
 * <pre>
 * { "placeholderFormat": "minimessage" }   // 或 "legacy"
 * </pre>
 *
 * <p>默认 {@code minimessage}（StyledChat / StyledPlayerList 原生支持）。
 * 沿用与其它数据文件一致的坏文件保护：load 失败置 {@code loadFailed}，save 跳过、不覆盖坏文件。</p>
 */
public final class DisplayConfig {

    /** 占位符输出格式。 */
    public enum Format {
        /** MiniMessage（{@code <#rrggbb>称号}）。 */
        MINIMESSAGE,
        /** 传统颜色代码（{@code §x§r§r§g§g§b§b称号}）。 */
        LEGACY;

        /** 解析格式名（大小写不敏感）；无法识别返回 null。 */
        public static Format parse(String raw) {
            if (raw == null) {
                return null;
            }
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "minimessage":
                case "mm":
                    return MINIMESSAGE;
                case "legacy":
                case "section":
                    return LEGACY;
                default:
                    return null;
            }
        }

        /** 写回配置文件的标识。 */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 默认格式。 */
    public static final Format DEFAULT_FORMAT = Format.MINIMESSAGE;

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/display.json。 */
    private static final String FILE_NAME = "mtrperm/display.json";

    /** 与其它数据类同款 Gson 配置。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final class InstanceHolder {
        private static final DisplayConfig INSTANCE = new DisplayConfig();
    }

    /** 全局唯一实例。 */
    public static DisplayConfig getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final Path file;
    private final Object ioLock = new Object();
    private volatile Format format = DEFAULT_FORMAT;
    private boolean loadFailed;

    /** 生产构造：延迟到真正调用时才解析 FabricLoader 配置目录。 */
    private DisplayConfig() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    /** 包内可见构造：仅用于单元测试注入临时文件路径。 */
    DisplayConfig(Path file) {
        this.file = file;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format value) {
        if (value != null) {
            format = value;
        }
    }

    /** 仅供测试 / 调试：上一次 load() 是否失败。 */
    public boolean hasLoadFailed() {
        synchronized (ioLock) {
            return loadFailed;
        }
    }

    /** 从 {@code config/mtrperm/display.json} 读取；文件不存在 / 空 → 默认值。 */
    public void load() {
        synchronized (ioLock) {
            if (!Files.exists(file)) {
                format = DEFAULT_FORMAT;
                loadFailed = false;
                return;
            }
            try {
                final String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (json.trim().isEmpty()) {
                    format = DEFAULT_FORMAT;
                    loadFailed = false;
                    return;
                }
                final JsonElement parsed = JsonParser.parseString(json);
                if (parsed == null || parsed.isJsonNull()) {
                    format = DEFAULT_FORMAT;
                    loadFailed = false;
                    return;
                }
                if (!parsed.isJsonObject()) {
                    loadFailed = true;
                    Mtrlock.LOGGER.error("[mtrlock] 显示配置不是 JSON 对象，保留当前设置，后续 save 将跳过: {}", file);
                    return;
                }
                final JsonObject root = parsed.getAsJsonObject();
                final JsonElement element = root.get("placeholderFormat");
                final Format parsedFormat = element != null && element.isJsonPrimitive()
                        && element.getAsJsonPrimitive().isString()
                        ? Format.parse(element.getAsString())
                        : null;
                format = parsedFormat != null ? parsedFormat : DEFAULT_FORMAT;
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 占位符输出格式: {}", format.id());
            } catch (IOException | JsonSyntaxException | JsonIOException | IllegalStateException e) {
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载显示配置失败，保留当前设置，后续 save 将跳过: {}", file, e);
            }
        }
    }

    /** 写回 {@code config/mtrperm/display.json}；load 失败时跳过，避免覆盖坏文件。 */
    public void save() {
        synchronized (ioLock) {
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载显示配置失败，跳过保存以避免覆盖损坏文件: {}", file);
                return;
            }
            try {
                final Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                final JsonObject root = new JsonObject();
                root.addProperty("placeholderFormat", format.id());
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                }
            } catch (IOException | JsonIOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存显示配置失败: {}", file, e);
            }
        }
    }
}
