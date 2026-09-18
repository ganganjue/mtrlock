package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.perm.PermissionGuard;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.Text;
import org.mtr.mod.packet.PacketDeleteData;
import org.mtr.mod.packet.PacketRequestResponseBase;
import org.mtr.mod.packet.PacketUpdateData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 功能 5：服务端拦截“编辑 / 删除”MTR 对象（Route / Station / Depot）。
 *
 * <p>注入点：{@code PacketRequestResponseBase.runServerOutbound(ServerWorld, ServerPlayerEntity)} 的 HEAD。
 * 这是所有 Request/Response 型 C2S 包在服务端的公共入口，参数里直接有 {@link ServerPlayerEntity}，
 * 且它内部才会调用 {@code Init.sendMessageC2S(...)} 把请求入队。因此在 HEAD {@code ci.cancel()}
 * 就能让操作根本不发生，同时还能给玩家发消息。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link PacketUpdateData}（"update_data"）= 编辑；</li>
 *   <li>{@link PacketDeleteData}（"delete_data"）= 删除；</li>
 *   <li>其它包（查询、配置、创建之外的…）直接 return，不干预。</li>
 * </ul>
 *
 * <p>只拦“编辑 / 删除”，不拦“创建”：由 {@link PermissionGuard} 用
 * {@link OwnershipData#hasCreator(String)} 区分——无归属记录 = 对象还不存在（创建）/未知 → 放行。</p>
 *
 * <p>不修改原方法逻辑，只在判定失败时 cancel；判定通过则完全放行原逻辑。</p>
 */
@Mixin(value = PacketRequestResponseBase.class, remap = false)
public abstract class PacketEditPermissionMixin {

    private static final String MESSAGE_EDIT = "你没有权限编辑此对象";
    private static final String MESSAGE_DELETE = "你没有权限删除此对象";

    /** 包里的请求 JSON（客户端请求原文）。 */
    @Shadow
    @Final
    private String content;

    @Inject(
            method = "runServerOutbound(Lorg/mtr/mapping/holder/ServerWorld;Lorg/mtr/mapping/holder/ServerPlayerEntity;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void mtrlock$guardEditAndDelete(ServerWorld world, ServerPlayerEntity player, CallbackInfo ci) {
        // 没有玩家（例如服务端内部 sendDirectlyToServerXxx）无法判定 → 不拦截
        if (player == null || content == null || content.isEmpty()) {
            return;
        }

        final List<String> denied;
        final String message;

        // 1.1.0：每包只解析一次 uuid / admin；判定函数内部走“分享感知”的纯逻辑 + 生产注入
        // （OwnershipData / ShareData / TeamData）。这样编辑 / 删除都能命中“团队成员可编辑”。
        final PermissionGuard.EditPermission permission = PermissionChecker.editPermissionFor(player);

        if ((Object) this instanceof PacketUpdateData) {
            // 编辑：逐个对象判定；无归属记录的会被 PermissionGuard 视为创建而放行
            denied = PermissionGuard.findDeniedInUpdate(
                    content,
                    OwnershipData.getInstance()::hasCreator,
                    ChildParents::get,
                    permission);
            message = MESSAGE_EDIT;
        } else if ((Object) this instanceof PacketDeleteData) {
            // 删除：逐个 id 判定
            denied = PermissionGuard.findDeniedInDelete(
                    content,
                    OwnershipData.getInstance()::hasCreator,
                    ChildParents::get,
                    permission);
            message = MESSAGE_DELETE;
        } else {
            // 其它包（创建之外的查询 / 配置 / 车辆操作等）不处理
            return;
        }

        if (!denied.isEmpty()) {
            player.sendMessage(Text.of(message), false);
            Mtrlock.LOGGER.info("[mtrlock] 拦截 {} 的 {} 操作，权限不足: {}",
                    player.getUuidAsString(), this.getClass().getSimpleName(), denied);
            ci.cancel();
        }
    }
}
