package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.protect.AxiomBlockGuard;
import com.mtrstar.lock.protect.ProtectionListener;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 1.4.2：Axiom 单包多目标 SetBlock 保护。
 *
 * <p>Axiom 正常单块路径自己会调 Fabric UseBlockCallback / PlayerBlockBreakEvents，但只覆盖
 * blockHit 对应的主位置。这个 mixin 在服务端 handle HEAD 遍历 {@code blocks} 的全部坐标，
 * 任一无权限就整包取消。</p>
 *
 * <p>使用 @Pseudo + targets 字符串：未安装 Axiom 时 mixin 不生效。</p>
 */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBlock", remap = false)
public abstract class AxiomServerboundSetBlockMixin {

    @Shadow
    @Final
    private Map<BlockPos, BlockState> blocks;

    /** 仅用于拒绝提示文案：破坏走 deny_break，放置走 deny_place。 */
    @Shadow
    @Final
    private boolean breaking;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtrlock$guardAxiomSetBlock(MinecraftServer server,
                                            ServerPlayerEntity player,
                                            CallbackInfo ci) {
        if (player == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        if (AxiomBlockGuard.anyDenied(blocks.keySet(),
                (x, z) -> ProtectionListener.canPlayerModifyAt(player, x, z))) {
            Mtrlock.LOGGER.info("[mtrlock] 拦截 Axiom SetBlock {}，保护对象判定不通过",
                    player.getUuidAsString());
            ProtectionListener.notifyDenied(player, breaking);
            ci.cancel();
        }
    }
}
