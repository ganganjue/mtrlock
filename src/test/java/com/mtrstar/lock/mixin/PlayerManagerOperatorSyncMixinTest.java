package com.mtrstar.lock.mixin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link PlayerManagerOperatorSyncMixin} 注入目标回归测试（1.5.3）。
 *
 * <p>只允许 /op、/deop 对应的 addToOperators / removeFromOperators 两个 RETURN 注入点；
 * 普通玩家 JOIN 不得触发额外 OwnershipSync 推送。</p>
 */
class PlayerManagerOperatorSyncMixinTest {

    @Test
    @DisplayName("非 OP 玩家加入：Mixin 无 JOIN 注入，只处理 OP 变化")
    void nonOperatorJoinDoesNotTriggerOperatorPush() {
        final Set<String> injectedTargets = Arrays.stream(PlayerManagerOperatorSyncMixin.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Inject.class))
                .filter(Objects::nonNull)
                .flatMap(inject -> Arrays.stream(inject.method()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                        "addToOperators(Lcom/mojang/authlib/GameProfile;)V",
                        "removeFromOperators(Lcom/mojang/authlib/GameProfile;)V"),
                injectedTargets,
                "只允许 /op、/deop 两个 OP 变化注入点");
        assertFalse(injectedTargets.stream().anyMatch(target ->
                        target.contains("join") || target.contains("Join") || target.contains("PlayerConnect")),
                "普通玩家加入不得有额外推送入口");
    }
}
