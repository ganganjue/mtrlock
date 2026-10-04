package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.perm.PermissionGuard;
import com.mtrstar.lock.protect.ProtectionConfig;
import com.mtrstar.lock.team.ResultCode;
import com.mtrstar.lock.team.ResultMessages;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.Text;
import org.mtr.mod.packet.PacketDeleteData;
import org.mtr.mod.packet.PacketDepotClear;
import org.mtr.mod.packet.PacketDepotGenerate;
import org.mtr.mod.packet.PacketDepotInstantDeploy;
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
 * 1.4.1 起同一注入点还拦截“别人的车厂操作”（生成列车 / 即时部署 / 清空车辆）。
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
 *   <li>1.4.1：{@link PacketDepotGenerate}（"generate_by_depot_ids"）、
 *       {@link PacketDepotInstantDeploy}（"instant_deploy_by_depot_ids"）、
 *       {@link PacketDepotClear}（"clear_by_depot_ids"）= 车厂操作，见
 *       {@link #mtrlock$checkDepotOperation(ServerPlayerEntity, PermissionGuard.EditPermission, CallbackInfo)}；</li>
 *   <li>其它包（查询、配置、创建之外的…）直接 return，不干预。</li>
 * </ul>
 *
 * <p>只拦“编辑 / 删除 / 车厂操作”，不拦“创建”：由 {@link PermissionGuard} 用
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
        } else if ((Object) this instanceof PacketDepotGenerate
                || (Object) this instanceof PacketDepotInstantDeploy
                || (Object) this instanceof PacketDepotClear) {
            // 1.4.1：车厂操作（生成列车 / 即时部署 / 清空车辆）。三个包载荷相同，走同一个处理方法。
            mtrlock$checkDepotOperation(player, permission, ci);
            return;
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

    /**
     * 1.4.1：车厂操作（{@code PacketDepotGenerate} / {@code PacketDepotInstantDeploy} /
     * {@code PacketDepotClear}）的权限校验。
     *
     * <p>三个包的载荷结构完全相同（{@code {"depotIds":[<long>...]}}），因此共用这一段：</p>
     * <ol>
     *   <li>{@code protectDepotOperations=false} → 直接放行（配置开关，默认 true）；</li>
     *   <li>按车厂自身归属判定（创建者 / 对象分享到的团队成员 / OP 3+；无归属记录 fail-open），
     *       与编辑 / 删除完全同一套 {@code PermissionChecker}；</li>
     *   <li>任一车厂无权限 → 取消本包（整包拒绝），聊天栏提示第一个无权限的车厂，并写日志。</li>
     * </ol>
     *
     * <p>为什么必须在这里：请求会被 {@code Init.sendMessageC2S} 入队、在 {@code Simulator} 侧
     * 异步执行（{@code OperationProcessor} / {@code DepotOperationByIds}），那时已经没有玩家身份了。</p>
     */
    private void mtrlock$checkDepotOperation(ServerPlayerEntity player,
                                             PermissionGuard.EditPermission permission,
                                             CallbackInfo ci) {
        if (!ProtectionConfig.getInstance().isProtectDepotOperations()) {
            return; // 1.4.1 开关关闭：三个车厂操作包全部放行
        }

        final List<String> denied = PermissionGuard.findDeniedInDepotOperation(
                content,
                OwnershipData.getInstance()::hasCreator,
                permission);
        if (denied.isEmpty()) {
            return;
        }

        // 多车厂无权限时只报第一个（与“删除/编辑一次列全”不同：这是“操作被拒”的单条提示）。
        // ResultMessages.zh 是结果码 ↔ 文案的既有通道；日志格式与上面编辑 / 删除分支保持一致。
        player.sendMessage(Text.of(String.format(
                ResultMessages.zh(ResultCode.DEPOT_OPERATION_NO_PERMISSION), denied.get(0))), false);
        Mtrlock.LOGGER.info("[mtrlock] 拦截 {} 的 {} 操作，权限不足: {}",
                player.getUuidAsString(), this.getClass().getSimpleName(), denied);
        ci.cancel();
    }
}
