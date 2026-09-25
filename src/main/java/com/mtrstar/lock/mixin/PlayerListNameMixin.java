package com.mtrstar.lock.mixin;

import com.mtrstar.lock.team.TeamPrefix;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给 tab 列表里的玩家名加上团队名前缀。
 *
 * <p><b>注入点：{@link ServerPlayerEntity#getPlayerListName()} 的 RETURN。</b></p>
 *
 * <p>为什么单独做一个 Mixin：1.20.1 的 tab 列表不走 {@code getDisplayName()}——
 * {@code PlayerListS2CPacket$Entry(ServerPlayerEntity)} 取的是
 * {@code getPlayerListName()}（已 javap 反编译确认），所以
 * {@link EntityDisplayNameMixin} 不会影响 tab，需要在此单独覆盖。</p>
 *
 * <p>原版 {@code ServerPlayerEntity.getPlayerListName()} 恒返回 {@code null}
 * （客户端回退到原始 {@code GameProfile} 名），因此这里直接用
 * {@code setReturnValue} 覆盖，不需要 {@code append}。参考字节码：</p>
 * <pre>
 *   public Text getPlayerListName() { return null; }
 * </pre>
 *
 * <p>只在服务端生效：{@link ServerPlayerEntity} 本身就是服务端类，
 * 客户端实体不是它，无需额外开关。</p>
 */
@Mixin(ServerPlayerEntity.class)
public abstract class PlayerListNameMixin {

    @Inject(method = "getPlayerListName", at = @At("RETURN"), cancellable = true)
    private void mtrlock$tabPrefix(CallbackInfoReturnable<Text> cir) {
        final ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        final String prefix = TeamPrefix.of(self.getUuidAsString());
        cir.setReturnValue(Text.literal(prefix + " " + self.getName().getString()));
    }
}
