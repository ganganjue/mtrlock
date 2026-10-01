package com.mtrstar.lock.network;

/**
 * GUI 网络协议版本（1.2.3）。
 *
 * <p>客户端与服务端各自在自己的包里编译进 {@link #VERSION}；打开 GUI、发送操作、回执
 * 都带上版本号，任一侧不匹配就拒绝并提示（{@code PROTOCOL_MISMATCH}），
 * 避免 1.2.2 与 1.2.3 混用时按错误布局解析数据包。</p>
 *
 * <p>注意：本类只放<b>纯常量</b>，不依赖 Minecraft，便于单测；通道 {@code Identifier}
 * 在 {@link GuiChannels} 里。</p>
 */
public final class GuiProtocol {

    /**
     * 当前 GUI 协议版本。
     *
     * <p>1.2.3 首次引入，固定为 1；以后只要 GUI 包布局有变就 +1。</p>
     */
    public static final int VERSION = 1;

    private GuiProtocol() {
    }

    /** 版本是否兼容。 */
    public static boolean isCompatible(int version) {
        return version == VERSION;
    }
}
