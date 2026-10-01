package com.mtrstar.lock.network.payload;

import com.mtrstar.lock.gui.TitleActionType;

/**
 * C2S：{@code TitleGuiActionC2S} 的载荷（1.2.3）。
 *
 * <p>纯数据 record，不含 Minecraft 类型。</p>
 *
 * @param protocolVersion GUI 协议版本
 * @param type            操作类型
 * @param targetUuid      目标玩家 UUID
 * @param title           称号文本（{@code SET} 时使用；≤ 16 个 code point）
 * @param color           颜色输入（{@code SET} 时使用；null / 空 = 无色）；1.2.4 新增
 */
public record TitleGuiAction(int protocolVersion,
                             TitleActionType type,
                             String targetUuid,
                             String title,
                             String color) {

    /** 用当前协议版本构造（不带颜色）。 */
    public static TitleGuiAction of(TitleActionType type, String targetUuid, String title) {
        return of(type, targetUuid, title, null);
    }

    /** 用当前协议版本构造。 */
    public static TitleGuiAction of(TitleActionType type, String targetUuid, String title, String color) {
        return new TitleGuiAction(com.mtrstar.lock.network.GuiProtocol.VERSION, type, targetUuid, title, color);
    }

    /** 只请求同步。 */
    public static TitleGuiAction sync() {
        return of(TitleActionType.REQUEST_SYNC, null, null, null);
    }
}
