package com.mtrstar.lock.client.gui;

/**
 * 收到服务端新快照 / 操作结果后需要重绘的 Screen（1.2.3）。
 *
 * <p>客户端不做乐观更新：操作发出后界面进入“处理中”，只有收到服务端的
 * {@code GuiResult} 与全量快照才刷新。</p>
 */
public interface GuiRefreshable {

    /** 服务端数据 / 结果变化时调用（在主线程）。 */
    void onGuiDataChanged();
}
