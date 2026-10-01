package com.mtrstar.lock.team;

import java.util.Objects;

/**
 * 一条自定义称呼（1.2.4）：文本 + 可选颜色。
 *
 * <p>颜色统一为规范化后的 {@code #RRGGBB}（大写），无色为 {@code null}。
 * 构造时会走 {@link ColorParser#normalize(String)}：非法颜色一律落回 {@code null}，
 * 保证内存里只有「规范色」或「无色」两种状态。</p>
 *
 * <p>纯数据 record，可纯 JVM 单测；Gson 序列化由 {@link TitleData} 手工控制，
 * 以同时兼容 1.2.3 的「uuid → 字符串」旧格式。</p>
 *
 * @param text  称呼文本（非 null）
 * @param color 规范化颜色 {@code #RRGGBB}；无色为 null
 */
public record TitleEntry(String text, String color) {

    public TitleEntry {
        Objects.requireNonNull(text, "text");
        color = ColorParser.normalize(color);
    }

    /** 是否带颜色。 */
    public boolean hasColor() {
        return color != null;
    }

    /** 无色版本。 */
    public TitleEntry withoutColor() {
        return color == null ? this : new TitleEntry(text, null);
    }
}
