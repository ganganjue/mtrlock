package com.mtrstar.lock.network;

import java.util.UUID;

/**
 * /op、/deop 后进行客户端快照重发的可测试胶水（1.5.3）。
 *
 * <p>只负责“按 UUID 找回在线目标玩家，存在则调用一次 push 动作”。生产调用方是
 * {@code PlayerManagerOperatorSyncMixin}，push 动作固定为 {@link OwnershipSync#pushTo}。</p>
 */
public final class OperatorUpdateNotifier {

    @FunctionalInterface
    public interface PlayerLookup<T> {
        T find(UUID playerUuid);
    }

    @FunctionalInterface
    public interface PlayerAction<T> {
        void accept(T player);
    }

    private OperatorUpdateNotifier() {
    }

    /**
     * 目标玩家在线时执行一次 push。
     *
     * @return 找到玩家并执行 push 返回 true；UUID 无效 / 玩家离线 / 参数缺失返回 false
     */
    public static <T> boolean notifyChange(UUID playerUuid, PlayerLookup<T> lookup, PlayerAction<T> push) {
        if (playerUuid == null || lookup == null || push == null) {
            return false;
        }
        final T player = lookup.find(playerUuid);
        if (player == null) {
            return false;
        }
        push.accept(player);
        return true;
    }
}
