package com.mtrstar.lock.network.payload;

import java.util.List;

/**
 * S2C：称号 GUI 全量快照（1.2.3）。
 *
 * <p>客户端只渲染这张快照，不做乐观更新。</p>
 *
 * @param targetUuid    当前选中的目标玩家 UUID（未选为 null）
 * @param targetName    当前选中的目标玩家显示名（未选为 null）
 * @param currentTitle  目标玩家当前称号（无称号为 null）
 * @param currentColor  目标玩家当前称号颜色（{@code #rrggbb}，无色为 null）；1.2.4 新增
 * @param onlinePlayers 当前在线玩家（搜索 / 选择用）
 */
public record TitleGuiSnapshot(String targetUuid,
                               String targetName,
                               String currentTitle,
                               String currentColor,
                               List<PlayerEntry> onlinePlayers) {

    public TitleGuiSnapshot {
        onlinePlayers = onlinePlayers == null ? List.of() : List.copyOf(onlinePlayers);
    }

    /** 空快照。 */
    public static TitleGuiSnapshot empty() {
        return new TitleGuiSnapshot(null, null, null, null, List.of());
    }
}
