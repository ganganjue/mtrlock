package com.mtrstar.lock.mixin;

import com.mojang.authlib.GameProfile;
import com.mtrstar.lock.network.OperatorUpdateNotifier;
import com.mtrstar.lock.network.OwnershipSync;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.5.3：/op、/deop 后立即向目标在线玩家重发 OwnershipSync 快照。
 *
 * <p>只追加一次 {@link OwnershipSync#pushTo}；不改 JOIN / 归属变更等既有触发条件，
 * 也不改变 S2C payload。普通玩家 JOIN 不会进入 addToOperators / removeFromOperators，
 * 因此不会产生额外推送。</p>
 *
 * <p>目标类使用 targets 字符串 + {@code @Pseudo}，不做运行期 Class.forName 探测。
 * 虽然 PlayerManager 是原版类且一定存在，这里仍保留 {@code @Pseudo} 以遵守项目对
 * Mixin 的统一红线要求。</p>
 */
@Pseudo
@Mixin(targets = "net.minecraft.server.PlayerManager")
public abstract class PlayerManagerOperatorSyncMixin {

    @Inject(
            method = "addToOperators(Lcom/mojang/authlib/GameProfile;)V",
            at = @At("RETURN"),
            remap = true
    )
    private void mtrlock$pushAfterAddOperator(GameProfile profile, CallbackInfo ci) {
        mtrlock$pushIfOnline(profile);
    }

    @Inject(
            method = "removeFromOperators(Lcom/mojang/authlib/GameProfile;)V",
            at = @At("RETURN"),
            remap = true
    )
    private void mtrlock$pushAfterRemoveOperator(GameProfile profile, CallbackInfo ci) {
        mtrlock$pushIfOnline(profile);
    }

    private void mtrlock$pushIfOnline(GameProfile profile) {
        if (profile == null || profile.getId() == null) {
            return;
        }
        final PlayerManager self = (PlayerManager) (Object) this;
        OperatorUpdateNotifier.notifyChange(profile.getId(), self::getPlayer, OwnershipSync::pushTo);
    }
}
