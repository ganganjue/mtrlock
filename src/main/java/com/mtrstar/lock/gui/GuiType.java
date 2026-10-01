package com.mtrstar.lock.gui;

/**
 * 可打开的 GUI 类型（1.2.3）。
 *
 * <p>纯枚举，客户端与服务端共用；{@code OpenGuiS2C} 里按 {@code ordinal()} 传输。</p>
 */
public enum GuiType {

    /** 团队系统 GUI（普通玩家可用）。 */
    TEAM,

    /** 称号系统 GUI（仅 OP 3+）。 */
    TITLE
}
