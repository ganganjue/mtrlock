package com.mtrstar.lock.team;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.Locale;

/**
 * 称号颜色的按上下文输出（1.2.4）。
 *
 * <p>三种出口：</p>
 * <ul>
 *   <li>{@link #component(String, String)} —— 聊天栏 / tab / 头顶 / 任何用 Minecraft {@link Text}
 *       渲染的地方，直接给文本上 RGB 色（不拼 legacy 字符串）；</li>
 *   <li>{@link #toMiniMessage(String, String)} —— 给 StyledChat / StyledPlayerList 的 MiniMessage
 *       上下文（{@code %mtrlock:title_colored%} 默认格式）；会对 {@code <} / {@code \} 做转义，
 *       防止称号文本注入 MiniMessage 标签；</li>
 *   <li>{@link #toLegacy(String, String)} —— 传统 {@code §x§r§r§g§g§b§b} 形式。</li>
 * </ul>
 *
 * <p>颜色参数可以是用户原始输入（{@code red} / {@code &a} / {@code #RRGGBB} / {@code &x...}），
 * 内部统一走 {@link ColorParser#normalize(String)}；无色（null / 空）时原样返回文本。</p>
 */
public final class ColorFormatter {

    private ColorFormatter() {
    }

    /** 给文本上色的 Minecraft Component；无色时返回纯文本。 */
    public static Text component(String text, String color) {
        final MutableText result = Text.literal(text == null ? "" : text);
        final String normalized = ColorParser.normalize(color);
        if (normalized == null) {
            return result;
        }
        return result.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(ColorParser.rgb(normalized))));
    }

    /** MiniMessage 形式（{@code <#RRGGBB>文本}）；无色时返回转义后的纯文本。 */
    public static String toMiniMessage(String text, String color) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final String safe = escapeMiniMessage(text);
        final String normalized = ColorParser.normalize(color);
        return normalized == null ? safe : "<" + normalized + ">" + safe;
    }

    /** 传统 legacy 形式（{@code §x§r§r§g§g§b§b文本}）；无色时返回纯文本。 */
    public static String toLegacy(String text, String color) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final String safe = text;
        final String normalized = ColorParser.normalize(color);
        return normalized == null ? safe : legacyPrefix(normalized) + safe;
    }

    /**
     * {@code #RRGGBB} → {@code §x§r§r§g§g§b§b}。
     *
     * @param color 规范颜色（{@code #RRGGBB}）；非法 / 空返回空串
     */
    public static String legacyPrefix(String color) {
        final String normalized = ColorParser.normalize(color);
        if (normalized == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("§x");
        for (char digit : normalized.substring(1).toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append('§').append(digit);
        }
        return sb.toString();
    }

    /**
     * 转义 MiniMessage 特殊字符，避免称号文本被当成标签解析。
     * {@code \} → {@code \\}，{@code <} → {@code \<}。
     */
    public static String escapeMiniMessage(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\\' || c == '<') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
