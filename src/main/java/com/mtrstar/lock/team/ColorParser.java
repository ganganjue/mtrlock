package com.mtrstar.lock.team;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 称号颜色解析（1.2.4）。<b>纯逻辑，不依赖 Minecraft / Fabric</b>，可纯 JVM 单测。
 *
 * <p>支持的输入形式（大小写不敏感，首尾空白会被去掉）：</p>
 * <ul>
 *   <li>原版 16 色代码：{@code &a} / {@code §a}（也接受裸的 {@code a}）；</li>
 *   <li>十六进制：{@code #RRGGBB}；</li>
 *   <li>原版 hex 形式：{@code &x&r&r&g&g&b&b}（{@code §x§r§r§g§g§b§b} 同样接受）；</li>
 *   <li>颜色名：{@code red} / {@code gold} / {@code aqua} 等 16 个原版色名
 *       （另外接受去掉下划线的写法，如 {@code darkblue}）。</li>
 * </ul>
 *
 * <p>输出统一为 {@code #rrggbb} 小写形式；空输入视为「无色」（{@code color = null}），
 * 非法输入返回 {@code valid = false}，两者由调用方区分。</p>
 *
 * <p>灰色命名沿用原版代码顺序：{@code gray = #aaaaaa}、{@code dark_gray = #555555}；
 * 另接受 {@code light_gray} 作为 {@code #AAAAAA} 的别名。</p>
 */
public final class ColorParser {

    /** 原版颜色数量（16 原版色）。 */
    public static final int COLOR_COUNT = 16;

    /** 按原版代码 0-f 顺序的颜色名。 */
    public static final String[] COLOR_NAMES = {
            "black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white"
    };

    /** 按原版代码 0-f 顺序的规范 HEX。 */
    public static final String[] COLOR_HEX = {
            "#000000", "#0000aa", "#00aa00", "#00aaaa",
            "#aa0000", "#aa00aa", "#ffaa00", "#aaaaaa",
            "#555555", "#5555ff", "#55ff55", "#55ffff",
            "#ff5555", "#ff55ff", "#ffff55", "#ffffff"
    };

    private static final Map<String, Integer> NAME_TO_INDEX = new HashMap<>();

    static {
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            NAME_TO_INDEX.put(COLOR_NAMES[i], i);
            NAME_TO_INDEX.put(COLOR_NAMES[i].replace("_", ""), i);
        }
        // 灰色别名：light_gray / lightgray 都指 #AAAAAA（与原版代码 7 一致）
        NAME_TO_INDEX.put("light_gray", 7);
        NAME_TO_INDEX.put("lightgray", 7);
        NAME_TO_INDEX.put("grey", 7);
        NAME_TO_INDEX.put("dark_grey", 8);
        // 常见口语写法
        NAME_TO_INDEX.put("purple", 13);
        NAME_TO_INDEX.put("pink", 13);
        NAME_TO_INDEX.put("orange", 6);
    }

    private ColorParser() {
    }

    /**
     * 解析结果。
     *
     * @param valid 输入是否合法；空输入也算合法（表示「无色」）
     * @param color 规范化后的 {@code #rrggbb}；空输入 / 无色为 null，非法时为 null
     */
    public record Result(boolean valid, String color) {

        /** 是否表示「没有颜色」（合法且 color 为 null）。 */
        public boolean isNone() {
            return valid && color == null;
        }
    }

    private static final Result NONE = new Result(true, null);
    private static final Result INVALID = new Result(false, null);

    /**
     * 解析颜色输入。
     *
     * @param raw 原始输入，可为 null / 空
     * @return 解析结果，永不为 null；空输入 → {@code (valid=true, color=null)}
     */
    public static Result parse(String raw) {
        if (raw == null) {
            return NONE;
        }
        final String s = raw.trim();
        if (s.isEmpty()) {
            return NONE;
        }

        // #RRGGBB
        if (s.charAt(0) == '#') {
            final String hex = s.substring(1);
            return isHex6(hex) ? ok(hex) : INVALID;
        }

        // & / § 前缀
        if (s.length() >= 2 && (s.charAt(0) == '&' || s.charAt(0) == '§')) {
            final char second = s.charAt(1);
            if (second == 'x' || second == 'X') {
                return parseLegacyHex(s);
            }
            if (s.length() == 2) {
                final int index = legacyIndex(second);
                return index >= 0 ? ok(COLOR_HEX[index]) : INVALID;
            }
            return INVALID;
        }

        // 裸的原版代码（单个字符 0-9 a-f）
        if (s.length() == 1) {
            final int index = legacyIndex(s.charAt(0));
            return index >= 0 ? ok(COLOR_HEX[index]) : INVALID;
        }

        // 颜色名
        final Integer named = NAME_TO_INDEX.get(s.toLowerCase(Locale.ROOT));
        return named != null ? ok(COLOR_HEX[named]) : INVALID;
    }

    /** 解析并规范化；非法 / 空 → null。 */
    public static String normalize(String raw) {
        final Result result = parse(raw);
        return result.valid() ? result.color() : null;
    }

    /** 输入是否合法（空输入也算合法）。 */
    public static boolean isValid(String raw) {
        return parse(raw).valid();
    }

    /** 颜色名（如 {@code red}）；非 16 原版色返回 null。 */
    public static String nameOf(String color) {
        final String normalized = normalize(color);
        if (normalized == null) {
            return null;
        }
        for (int i = 0; i < COLOR_HEX.length; i++) {
            if (COLOR_HEX[i].equals(normalized)) {
                return COLOR_NAMES[i];
            }
        }
        return null;
    }

    /** 规范颜色的 RGB 整数；null / 非法 → -1。 */
    public static int rgb(String color) {
        final String normalized = normalize(color);
        if (normalized == null) {
            return -1;
        }
        return Integer.parseInt(normalized.substring(1), 16);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private static Result parseLegacyHex(String s) {
        // &x&r&r&g&g&b&b —— 总长 14，第 2/4/6/8/10/12 位必须是 & 或 §
        if (s.length() != 14) {
            return INVALID;
        }
        final StringBuilder hex = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            final int marker = 2 + i * 2;
            final char prefix = s.charAt(marker);
            if (prefix != '&' && prefix != '§') {
                return INVALID;
            }
            final char digit = s.charAt(marker + 1);
            if (!isHexChar(digit)) {
                return INVALID;
            }
            hex.append(digit);
        }
        return ok(hex.toString());
    }

    private static Result ok(String hex) {
        // 调用方可能传 6 位数字（#RRGGBB 分支）或已带 # 的颜色表值（颜色名 / 原版代码分支）
        final String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        return new Result(true, "#" + digits.toLowerCase(Locale.ROOT));
    }

    private static boolean isHex6(String hex) {
        if (hex.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (!isHexChar(hex.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int legacyIndex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        final char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'f') {
            return 10 + (lower - 'a');
        }
        return -1;
    }
}
