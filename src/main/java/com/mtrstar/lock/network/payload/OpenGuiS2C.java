package com.mtrstar.lock.network.payload;

import com.mtrstar.lock.gui.GuiType;

/**
 * S2C：告诉客户端打开哪个 GUI（1.2.3）。
 *
 * <p>带协议版本；客户端版本不匹配时拒绝打开并提示，
 * 绝不按错误布局解析后续同步包。</p>
 */
public record OpenGuiS2C(int protocolVersion, GuiType gui) {
}
