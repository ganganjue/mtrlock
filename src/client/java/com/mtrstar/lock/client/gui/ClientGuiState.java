package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.network.payload.GuiResult;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;

/**
 * 客户端 GUI 数据缓存（1.2.3）。
 *
 * <p>由 S2C 包填充（{@code MtrlockClient}），只用于渲染；<b>不是权威</b>，
 * 真正的校验全在服务端。所有写入都发生在客户端主线程（接收器里 {@code client.execute}）。</p>
 */
public final class ClientGuiState {

    private static volatile TeamGuiSnapshot teamSnapshot = TeamGuiSnapshot.empty();
    private static volatile TitleGuiSnapshot titleSnapshot = TitleGuiSnapshot.empty();
    private static volatile GuiResult lastResult;

    private ClientGuiState() {
    }

    public static TeamGuiSnapshot teamSnapshot() {
        return teamSnapshot;
    }

    public static void setTeamSnapshot(TeamGuiSnapshot snapshot) {
        teamSnapshot = snapshot == null ? TeamGuiSnapshot.empty() : snapshot;
    }

    public static TitleGuiSnapshot titleSnapshot() {
        return titleSnapshot;
    }

    public static void setTitleSnapshot(TitleGuiSnapshot snapshot) {
        titleSnapshot = snapshot == null ? TitleGuiSnapshot.empty() : snapshot;
    }

    /** 最近一次操作结果；没有则为 null。 */
    public static GuiResult lastResult() {
        return lastResult;
    }

    public static void setLastResult(GuiResult result) {
        lastResult = result;
    }

    public static void clear() {
        teamSnapshot = TeamGuiSnapshot.empty();
        titleSnapshot = TitleGuiSnapshot.empty();
        lastResult = null;
    }
}
