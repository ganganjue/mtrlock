package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.protect.ProtectionListener;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 1.4.2：TNT / 爆炸间接破坏保护。
 *
 * <p>{@link Explosion#collectBlocksAndDamageEntities()} 只计算受影响方块。若 affectedBlocks
 * 内任一坐标命中「有归属的受保护对象」，则清空整个 affectedBlocks；后续
 * {@code affectWorld} 不会破坏任何方块。实体伤害不在本次保护范围。</p>
 *
 * <p>无玩家身份，因此不区分爆炸来源：有归属即拦截整次爆炸的方块破坏。</p>
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Inject(method = "collectBlocksAndDamageEntities", at = @At("RETURN"))
    private void mtrlock$clearProtectedBlocks(CallbackInfo ci) {
        final Explosion self = (Explosion) (Object) this;
        final List<BlockPos> affectedBlocks = self.getAffectedBlocks();
        if (affectedBlocks == null || affectedBlocks.isEmpty()) {
            return;
        }
        for (BlockPos pos : affectedBlocks) {
            if (pos != null && ProtectionListener.hasProtectedObjectAt(pos.getX(), pos.getZ())) {
                affectedBlocks.clear();
                Mtrlock.LOGGER.info("[mtrlock] 拦截爆炸：affectedBlocks 命中受保护区域，整次爆炸不破坏方块");
                return;
            }
        }
    }
}
