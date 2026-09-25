package com.mtrstar.lock.mixin;

import com.mtrstar.lock.compat.DisplayModDetector;
import com.mtrstar.lock.team.TeamPrefix;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给玩家显示名加上团队名前缀，例如 {@code <[红石] Steve> 大家好}。
 *
 * <p><b>注入点：{@link PlayerEntity#getDisplayName()} 的 RETURN。</b></p>
 *
 * <p>为什么不是 {@code Entity.getDisplayName()}：在 1.20.1（yarn 1.20.1+build.10）里
 * {@link PlayerEntity} <b>覆写</b>了 {@code getDisplayName()}，而 {@link ServerPlayerEntity}
 * 又继承 PlayerEntity 的实现。Java 虚分派下，玩家调用永远走 PlayerEntity 的覆写，
 * Entity 的实现根本不会被执行——所以 @Mixin(Entity.class) 对玩家无效（已用 javap 反编译确认：
 * Entity 与 PlayerEntity 各自声明了 {@code getDisplayName()}，ServerPlayerEntity 未声明）。
 * 因此本 Mixin 以 {@link PlayerEntity} 为注入目标。</p>
 *
 * <p>为什么注入 getDisplayName 就能改聊天栏（已用 javap 反编译确认 1.20.1 聊天链路）：</p>
 * <pre>
 *   ServerPlayNetworkHandler.handleDecoratedMessage(SignedMessage)
 *     -&gt; MessageType.params(MessageType.CHAT, Entity)   // 内部调用 entity.getDisplayName()
 *     -&gt; PlayerManager.broadcast(SignedMessage, sender, MessageType.Parameters)
 *     -&gt; 把带前缀的 Text 序列化进 ChatMessageS2CPacket
 * </pre>
 * 同时也会影响登录/退出/死亡等使用 getDisplayName() 的服务器消息。
 *
 * <p><b>只在 {@link ServerPlayerEntity} 时修改</b>：客户端实体不是 ServerPlayerEntity，
 * 客户端也没有服务端 {@code TeamData}（S2C 只同步到 ClientOwnership），若在客户端也加前缀，
 * 头顶名字会全部错误地显示 {@link TeamPrefix#NO_TEAM}。因此这里把范围限定在服务端。</p>
 */
@Mixin(PlayerEntity.class)
public abstract class EntityDisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void mtrlock$addTeamPrefix(CallbackInfoReturnable<Text> cir) {
        // 检测到 StyledChat / StyledPlayerList 时不注入显示名，改用 Placeholder（见 MtrlockPlaceholders）。
        if (DisplayModDetector.hasConflictingDisplayMod()) {
            return;
        }
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }
        final Text original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        final String prefix = TeamPrefix.of(player.getUuidAsString());
        if (prefix.isEmpty()) {
            // 无团队无称呼：不加前缀，也不加多余空格。
            return;
        }
        cir.setReturnValue(Text.literal(prefix + " ").append(original));
    }
}
