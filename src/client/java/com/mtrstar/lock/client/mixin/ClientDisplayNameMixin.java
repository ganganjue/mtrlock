package com.mtrstar.lock.client.mixin;

import com.mtrstar.lock.client.ClientTeamPrefix;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端玩家显示名前缀（让<b>头顶名字</b>也带团队前缀）。
 *
 * <p>头顶 nametag 由客户端实体渲染：{@code EntityRenderer} 调
 * {@code entity.getDisplayName()}。客户端玩家是
 * {@link ClientPlayerEntity}（extends {@link PlayerEntity}），走 PlayerEntity 的实现，
 * 所以这里注 {@code PlayerEntity.getDisplayName()} 的 RETURN。</p>
 *
 * <p>与服务端 {@code EntityDisplayNameMixin} 的关系：两者注入同一个方法，但守卫互斥——
 * 服务端那份用 {@code instanceof ServerPlayerEntity}（只在服务端生效），
 * 本份用 {@code instanceof ClientPlayerEntity}（只在客户端生效）。
 * 同一侧只会命中一个，不会出现双重前缀。</p>
 *
 * <p>前缀由 {@link ClientTeamPrefix} 基于 S2C 同步来的团队快照计算，
 * 不触碰 {@code TeamData}。</p>
 */
@Mixin(PlayerEntity.class)
public abstract class ClientDisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void mtrlock$clientPrefix(CallbackInfoReturnable<Text> cir) {
        if (!((Object) this instanceof ClientPlayerEntity player)) {
            return;
        }
        final Text original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        final String prefix = ClientTeamPrefix.of(player.getUuidAsString());
        cir.setReturnValue(Text.literal(prefix + " ").append(original));
    }
}
