package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.protect.ProtectionListener;
import net.minecraft.block.pattern.CachedBlockPosition;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.SetBlockCommand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * 1.4.2：/setblock 命令绕过保护。
 *
 * <p>{@link SetBlockCommand} 最终走 {@code ServerWorld#setBlockState / breakBlock}，不会触发
 * Fabric 的 PlayerBlockBreakEvents。这里在真正 execute 前按 target BlockPos 判定；没有玩家
 *（命令方块 / 控制台）放行。</p>
 */
@Mixin(SetBlockCommand.class)
public abstract class SetBlockCommandMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void mtrlock$guardSetBlock(ServerCommandSource source,
                                              BlockPos pos,
                                              BlockStateArgument blockState,
                                              SetBlockCommand.Mode mode,
                                              Predicate<CachedBlockPosition> condition,
                                              CallbackInfoReturnable<Integer> cir) {
        if (source == null || pos == null) {
            return;
        }
        final ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return; // 命令方块 / 控制台放行
        }
        if (!ProtectionListener.canPlayerModifyAt(player, pos.getX(), pos.getZ())) {
            Mtrlock.LOGGER.info("[mtrlock] 拦截 /setblock {} 在 ({}, {})，保护对象判定不通过",
                    player.getUuidAsString(), pos.getX(), pos.getZ());
            ProtectionListener.notifyDenied(player, mode == SetBlockCommand.Mode.DESTROY);
            cir.setReturnValue(0);
            cir.cancel();
        }
    }
}
