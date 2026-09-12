package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.Text;
import org.mtr.mod.packet.PacketSetRouteIdHasDisabledAnnouncements;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 功能 5（旁路入口）：拦截“线路禁用报站”开关的修改。
 *
 * <p>不是所有线路编辑都走 {@code UpdateDataRequest}：
 * {@code PacketSetRouteIdHasDisabledAnnouncements} 直接覆写 {@code runServer}，
 * 把 {@code routeId} 写进 {@code PersistentStateData.routeIdsWithDisabledAnnouncements}。
 * 这里补一个注入点，保证这个入口也受权限控制。</p>
 *
 * <p>规则与主入口一致：只在该 route 已有归属记录、且当前玩家不是创建者/管理员时拒绝；
 * 无归属记录（历史对象 / 非玩家创建）→ 放行。</p>
 */
@Mixin(value = PacketSetRouteIdHasDisabledAnnouncements.class, remap = false)
public abstract class RouteFlagPermissionMixin {

    private static final String MESSAGE = "你没有权限编辑此对象";

    /** 包里的线路 long id。 */
    @Shadow
    @Final
    private long routeId;

    @Inject(
            method = "runServer(Lorg/mtr/mapping/holder/MinecraftServer;Lorg/mtr/mapping/holder/ServerPlayerEntity;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void mtrlock$guardRouteFlag(MinecraftServer server, ServerPlayerEntity player, CallbackInfo ci) {
        if (player == null) {
            return;
        }

        final String objectId = PermissionChecker.PREFIX_ROUTE + ":" + Utilities.numberToPaddedHexString(routeId);
        // 无归属记录 → 放行，不干预
        if (!OwnershipData.getInstance().hasCreator(objectId)) {
            return;
        }
        if (!PermissionChecker.canEdit(player, objectId)) {
            player.sendMessage(Text.of(MESSAGE), false);
            Mtrlock.LOGGER.info("[mtrlock] 拦截线路开关修改 {} 权限不足: {}", player.getUuidAsString(), objectId);
            ci.cancel();
        }
    }
}
