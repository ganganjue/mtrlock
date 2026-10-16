package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.protect.ProtectionListener;
import net.minecraft.block.pattern.CachedBlockPosition;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.server.command.FillCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * 1.4.2：/fill 命令绕过保护。
 *
 * <p>保护是 2D 的。这里不逐方块遍历，而是让 {@code SpatialIndex#lookupArea} 按 chunk
 * 去重扫描：只要矩形与任一“有归属的受保护范围”相交且玩家无权限，整条 /fill 取消。
 * 复杂度与选区跨越的 chunk 数相关，不随方块数线性增长。</p>
 */
@Mixin(FillCommand.class)
public abstract class FillCommandMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void mtrlock$guardFill(ServerCommandSource source,
                                          BlockBox box,
                                          BlockStateArgument blockState,
                                          @Coerce Object mode,
                                          Predicate<CachedBlockPosition> condition,
                                          CallbackInfoReturnable<Integer> cir) {
        if (source == null || box == null) {
            return;
        }
        final ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return; // 命令方块 / 控制台放行
        }

        if (!ProtectionListener.canPlayerModifyArea(player,
                box.getMinX(), box.getMaxX(), box.getMinZ(), box.getMaxZ())) {
            Mtrlock.LOGGER.info("[mtrlock] 拦截 /fill {} 在 ({}, {})-({}, {})，保护对象判定不通过",
                    player.getUuidAsString(),
                    box.getMinX(), box.getMinZ(), box.getMaxX(), box.getMaxZ());
            ProtectionListener.notifyDenied(player, false);
            cir.setReturnValue(0);
            cir.cancel();
        }
    }
}
