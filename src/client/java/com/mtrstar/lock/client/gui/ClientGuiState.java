package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.network.payload.GuiResult;
import com.mtrstar.lock.team.ColorParser;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;

import java.util.ArrayList;
import java.util.List;

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

    /** GUI「最近使用」的颜色（仅客户端内存，最多 {@value #MAX_RECENT_COLORS} 个，最新在前）。 */
    private static final List<String> RECENT_COLORS = new ArrayList<>();

    /** 「最近使用」最多保留的颜色数。 */
    public static final int MAX_RECENT_COLORS = 8;

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

    /** 「最近使用」颜色的只读快照（最新在前）。 */
    public static List<String> recentColors() {
        return List.copyOf(RECENT_COLORS);
    }

    /** 记住一个颜色（规范化后去重、最新在前、最多 {@value #MAX_RECENT_COLORS} 个）。 */
    public static void rememberColor(String color) {
        final String normalized = ColorParser.normalize(color);
        if (normalized == null) {
            return;
        }
        RECENT_COLORS.remove(normalized);
        RECENT_COLORS.add(0, normalized);
        while (RECENT_COLORS.size() > MAX_RECENT_COLORS) {
            RECENT_COLORS.remove(RECENT_COLORS.size() - 1);
        }
    }

    public static void clear() {
        teamSnapshot = TeamGuiSnapshot.empty();
        titleSnapshot = TitleGuiSnapshot.empty();
        lastResult = null;
        RECENT_COLORS.clear();
    }
}
