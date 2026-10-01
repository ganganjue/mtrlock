package com.mtrstar.lock.network.payload;

import com.mtrstar.lock.team.ResultCode;

import java.util.Objects;

/**
 * S2C：一次 GUI 操作的校验结果（1.2.3）。
 *
 * <p>只带结果码，不带服务端语言文案；客户端按玩家语言从 lang 取
 * {@code gui.mtrlock.result.*}，保证 GUI 文案可本地化。</p>
 */
public record GuiResult(int protocolVersion, boolean ok, ResultCode code) {

    public GuiResult {
        Objects.requireNonNull(code, "code");
    }

    public static GuiResult of(boolean ok, ResultCode code) {
        return new GuiResult(com.mtrstar.lock.network.GuiProtocol.VERSION, ok, code);
    }
}
