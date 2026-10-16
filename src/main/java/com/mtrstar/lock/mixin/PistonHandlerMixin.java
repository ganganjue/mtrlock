package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.protect.ProtectionListener;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 1.4.2：活塞间接破坏保护。
 *
 * <p>{@link PistonHandler#calculatePush()} 只计算移动 / 破坏列表，不写方块。任何保护区内的
 * 坐标出现在 movedBlocks 或 brokenBlocks 时，直接把 calculatePush 的返回值改成 false，
 * 后续 {@code PistonBlock.move} 就不会执行，整次活塞动作失败。</p>
 *
 * <p>无玩家身份，因此不区分活塞来源：有归属即拦，无归属放行。</p>
 */
@Mixin(PistonHandler.class)
public abstract class PistonHandlerMixin {

    @Inject(method = "calculatePush", at = @At("RETURN"), cancellable = true)
    private void mtrlock$blockProtectedPush(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        final PistonHandler self = (PistonHandler) (Object) this;
        if (mtrlock$hasProtected(self.getMovedBlocks()) || mtrlock$hasProtected(self.getBrokenBlocks())) {
            Mtrlock.LOGGER.info("[mtrlock] 拦截活塞动作：moved/broken 列表命中受保护区域");
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    private static boolean mtrlock$hasProtected(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return false;
        }
        for (BlockPos pos : positions) {
            if (pos != null && ProtectionListener.hasProtectedObjectAt(pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }
}
