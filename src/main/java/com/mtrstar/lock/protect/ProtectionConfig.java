package com.mtrstar.lock.protect;

import com.mtrstar.lock.Mtrlock;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 区域方块保护配置（1.3.0）。
 *
 * <p>持久化到 {@code config/mtrperm/protection.properties}：</p>
 * <pre>
 * enabled=true                 # 总开关
 * protectStations=true         # 保护车站矩形
 * protectDepots=true           # 保护车厂矩形
 * expandBlocks=0               # 范围向外扩张的方块数
 * notifyPlayer=true            # 拒绝时给玩家发提示
 * protectDepotOperations=true  # 1.4.1：拦截别人的车厂操作（生成列车 / 即时部署 / 清空车辆）
 * </pre>
 *
 * <p>沿用与其它数据文件一致的坏文件保护：结构损坏（IO 异常 / uXXXX 形式的 Unicode 转义非法）
 * 时保留当前内存值并置 {@code loadFailed}，之后 {@link #save()} 跳过，避免覆盖坏文件。
 * 单个键的值非法（例如 {@code enabled=maybe}）只让该键回退默认值，不算坏文件。</p>
 */
public final class ProtectionConfig {

    /** 数据文件相对于游戏 config 目录的路径。最终为 config/mtrperm/protection.properties。 */
    private static final String FILE_NAME = "mtrperm/protection.properties";

    /** 默认值。 */
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_PROTECT_STATIONS = true;
    public static final boolean DEFAULT_PROTECT_DEPOTS = true;
    public static final int DEFAULT_EXPAND_BLOCKS = 0;
    public static final boolean DEFAULT_NOTIFY_PLAYER = true;
    /** 1.4.1：是否拦截「非归属者」对车厂执行生成列车 / 即时部署 / 清空车辆。 */
    public static final boolean DEFAULT_PROTECT_DEPOT_OPERATIONS = true;

    /**
     * {@code expandBlocks} 上限。
     *
     * <p>范围每向外扩张 1 格，索引要覆盖的 chunk 数会平方级增长；
     * 卡在 256 既够用又不会让一个畸形配置撑爆内存。</p>
     */
    public static final int MAX_EXPAND_BLOCKS = 256;

    private static final class InstanceHolder {
        private static final ProtectionConfig INSTANCE = new ProtectionConfig();
    }

    /** 全局唯一实例。 */
    public static ProtectionConfig getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final Path file;

    /** 磁盘 IO 互斥锁，只保护 load()/save()。 */
    private final Object ioLock = new Object();

    private volatile boolean enabled = DEFAULT_ENABLED;
    private volatile boolean protectStations = DEFAULT_PROTECT_STATIONS;
    private volatile boolean protectDepots = DEFAULT_PROTECT_DEPOTS;
    private volatile int expandBlocks = DEFAULT_EXPAND_BLOCKS;
    private volatile boolean notifyPlayer = DEFAULT_NOTIFY_PLAYER;
    private volatile boolean protectDepotOperations = DEFAULT_PROTECT_DEPOT_OPERATIONS;

    /** 上一次 {@link #load()} 是否因结构损坏失败；true 时 save() 跳过。 */
    private boolean loadFailed;

    /** 生产构造：延迟到真正调用时才解析 FabricLoader 配置目录。 */
    private ProtectionConfig() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    /** 包内可见构造：仅用于单元测试注入临时文件路径。 */
    ProtectionConfig(Path file) {
        this.file = file;
    }

    // =====================================================================
    // 取值
    // =====================================================================

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isProtectStations() {
        return protectStations;
    }

    public boolean isProtectDepots() {
        return protectDepots;
    }

    public int getExpandBlocks() {
        return expandBlocks;
    }

    public boolean isNotifyPlayer() {
        return notifyPlayer;
    }

    /** 1.4.1：是否拦截别人的车厂操作（生成列车 / 即时部署 / 清空车辆）。 */
    public boolean isProtectDepotOperations() {
        return protectDepotOperations;
    }

    // =====================================================================
    // 设值（测试 / 未来的配置命令）
    // =====================================================================

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public void setProtectStations(boolean value) {
        protectStations = value;
    }

    public void setProtectDepots(boolean value) {
        protectDepots = value;
    }

    public void setExpandBlocks(int value) {
        expandBlocks = clampExpandBlocks(value);
    }

    public void setNotifyPlayer(boolean value) {
        notifyPlayer = value;
    }

    public void setProtectDepotOperations(boolean value) {
        protectDepotOperations = value;
    }

    /** 仅供测试 / 调试：上一次 load() 是否失败。 */
    public boolean hasLoadFailed() {
        synchronized (ioLock) {
            return loadFailed;
        }
    }

    /** reset 到默认值（测试 / {@code protect reload} 找不到文件时）。 */
    public void resetToDefaults() {
        enabled = DEFAULT_ENABLED;
        protectStations = DEFAULT_PROTECT_STATIONS;
        protectDepots = DEFAULT_PROTECT_DEPOTS;
        expandBlocks = DEFAULT_EXPAND_BLOCKS;
        notifyPlayer = DEFAULT_NOTIFY_PLAYER;
        protectDepotOperations = DEFAULT_PROTECT_DEPOT_OPERATIONS;
    }

    /** 供 {@code /mtrlock protect status} 展示的多行文本。 */
    public List<String> statusLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("区域方块保护：" + (enabled ? "已启用" : "已禁用"));
        lines.add("  车站范围：" + (protectStations ? "保护" : "不保护"));
        lines.add("  车厂范围：" + (protectDepots ? "保护" : "不保护"));
        lines.add("  向外扩张：" + expandBlocks + " 格");
        lines.add("  拒绝时提示玩家：" + (notifyPlayer ? "是" : "否"));
        lines.add("  车厂操作拦截（生成 / 即时部署 / 清空）：" + (protectDepotOperations ? "是" : "否"));
        lines.add("  配置文件：" + file);
        lines.add("  加载状态：" + (hasLoadFailed() ? "上次加载失败（已保留内存值，不会覆盖坏文件）" : "正常"));
        return lines;
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    /** 从 {@code config/mtrperm/protection.properties} 读取；文件不存在 / 空 → 默认值。 */
    public void load() {
        synchronized (ioLock) {
            if (!Files.exists(file)) {
                resetToDefaults();
                loadFailed = false;
                Mtrlock.LOGGER.info("[mtrlock] 保护配置不存在，使用默认值: {}", file);
                return;
            }

            final Properties properties = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                // IllegalArgumentException：uXXXX 形式的 Unicode 转义非法等结构损坏
                loadFailed = true;
                Mtrlock.LOGGER.error("[mtrlock] 加载保护配置失败，保留当前设置，后续 save 将跳过: {}", file, e);
                return;
            }

            // 逐键读取：值非法只回退该键的默认值，不影响其它键，也不标记坏文件。
            enabled = readBoolean(properties, "enabled", DEFAULT_ENABLED);
            protectStations = readBoolean(properties, "protectStations", DEFAULT_PROTECT_STATIONS);
            protectDepots = readBoolean(properties, "protectDepots", DEFAULT_PROTECT_DEPOTS);
            expandBlocks = readInt(properties, "expandBlocks", DEFAULT_EXPAND_BLOCKS);
            notifyPlayer = readBoolean(properties, "notifyPlayer", DEFAULT_NOTIFY_PLAYER);
            protectDepotOperations = readBoolean(properties, "protectDepotOperations",
                    DEFAULT_PROTECT_DEPOT_OPERATIONS);

            loadFailed = false;
            Mtrlock.LOGGER.info("[mtrlock] 区域方块保护: {} (车站 {} / 车厂 {} / 扩张 {} 格)",
                    enabled ? "启用" : "禁用",
                    protectStations ? "保护" : "不保护",
                    protectDepots ? "保护" : "不保护",
                    expandBlocks);
        }
    }

    /** 写回配置文件；上次 load 失败时跳过，避免覆盖坏文件。 */
    public void save() {
        synchronized (ioLock) {
            if (loadFailed) {
                Mtrlock.LOGGER.warn("[mtrlock] 上次加载保护配置失败，跳过保存以避免覆盖损坏文件: {}", file);
                return;
            }
            try {
                final Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    writer.write("# mtrlock 区域方块保护配置 (1.3.0)");
                    writer.newLine();
                    writer.write("# 车站 / 车厂矩形范围内，只有归属者、其团队成员与 OP 3+ 能破坏 / 放置方块。");
                    writer.newLine();
                    writer.write("enabled=" + enabled);
                    writer.newLine();
                    writer.write("protectStations=" + protectStations);
                    writer.newLine();
                    writer.write("protectDepots=" + protectDepots);
                    writer.newLine();
                    writer.write("expandBlocks=" + expandBlocks);
                    writer.newLine();
                    writer.write("notifyPlayer=" + notifyPlayer);
                    writer.newLine();
                    writer.write("protectDepotOperations=" + protectDepotOperations);
                    writer.newLine();
                }
            } catch (IOException e) {
                Mtrlock.LOGGER.error("[mtrlock] 保存保护配置失败: {}", file, e);
            }
        }
    }

    // =====================================================================
    // 解析工具
    // =====================================================================

    /** 读 boolean；缺失 / 非法回退 fallback。接受 true/false（大小写不敏感）。 */
    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        final String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        final String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        Mtrlock.LOGGER.warn("[mtrlock] 保护配置项 {}={} 不是布尔值，回退默认 {}", key, raw, fallback);
        return fallback;
    }

    /** 读 int；缺失 / 非法 / 越界时回退或收敛到合法区间。 */
    private static int readInt(Properties properties, String key, int fallback) {
        final String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return clampExpandBlocks(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            Mtrlock.LOGGER.warn("[mtrlock] 保护配置项 {}={} 不是整数，回退默认 {}", key, raw, fallback);
            return fallback;
        }
    }

    /** 负数收敛为 0，超过上限收敛为上限。 */
    private static int clampExpandBlocks(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, MAX_EXPAND_BLOCKS);
    }
}
