package com.mtrstar.lock.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OperatorUpdateNotifier} 单测（1.5.3）。
 *
 * <p>生产上 addToOperators / removeFromOperators RETURN 只调用一次 notifyChange；
 * 这里用泛型假玩家验证 lookup / push 的调用次数与目标玩家。</p>
 */
class OperatorUpdateNotifierTest {

    @Test
    @DisplayName("模拟 OP 变化：目标玩家在线 → pushTo 等价动作恰好调用一次")
    void operatorChangePushesExactlyOnce() {
        final UUID playerUuid = UUID.randomUUID();
        final Object player = new Object();
        final AtomicInteger pushCalls = new AtomicInteger();
        final Object[] pushedPlayer = new Object[1];

        final boolean handled = OperatorUpdateNotifier.notifyChange(
                playerUuid,
                id -> {
                    assertSame(playerUuid, id);
                    return player;
                },
                value -> {
                    pushCalls.incrementAndGet();
                    pushedPlayer[0] = value;
                });

        assertTrue(handled);
        assertEquals(1, pushCalls.get(), "一次 OP 变化只能追加一次推送");
        assertSame(player, pushedPlayer[0]);
    }

    @Test
    @DisplayName("目标玩家不在线 / UUID 为 null → 不推送")
    void missingPlayerDoesNotPush() {
        final AtomicInteger pushCalls = new AtomicInteger();

        assertFalse(OperatorUpdateNotifier.notifyChange(UUID.randomUUID(), id -> null,
                player -> pushCalls.incrementAndGet()));
        assertFalse(OperatorUpdateNotifier.notifyChange(null, id -> new Object(),
                player -> pushCalls.incrementAndGet()));
        assertEquals(0, pushCalls.get());
    }
}
