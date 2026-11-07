package com.mtrstar.lock.client.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.client.ClientOwnership;
import com.mtrstar.lock.client.ClientRoutePlatformGuard;
import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.PermissionGuard;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.registry.RegistryClient;
import org.mtr.mod.packet.PacketDeleteData;
import org.mtr.mod.packet.PacketRequestResponseBase;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 功能 6：客户端发包层拦截。
 *
 * <p>注入点：{@code org.mtr.mapping.registry.RegistryClient.sendPacketToServer(PacketHandler)} 的 HEAD。
 * MTR 客户端所有 C2S 包都经过这一个方法（反编译确认），CCancel 后包不会发出，
 * 服务端就不会广播 {@code UpdateDataResponse}，因此不会产生本地“乐观更新”造成的视觉不一致。</p>
 *
 * <p>只处理编辑（{@link PacketUpdateData}）与删除（{@link PacketDeleteData}）；
 * 创建（归属里查不到该 id）与“自己的对象”以及 OP 3+ 都放行。
 * 判定逻辑直接复用服务端的纯逻辑 {@link PermissionGuard}（含 platform/siding 的父对象判定）。</p>
 *
 * <p><b>注意</b>：这只是“体验层”防护，恶意客户端仍可绕过；最终权威是服务端拦截（功能 5）。</p>
 */
@Mixin(value = RegistryClient.class, remap = false)
public abstract class RegistryClientMixin {

    private static final String MESSAGE_EDIT = "你没有权限编辑此对象";
    private static final String MESSAGE_DELETE = "你没有权限删除此对象";

    @Inject(
            method = "sendPacketToServer(Lorg/mtr/mapping/registry/PacketHandler;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void mtrlock$guardClientSend(PacketHandler packet, CallbackInfo ci) {
        if (!(packet instanceof PacketRequestResponseBase)) {
            ClientRoutePlatformGuard.clearSnapshot();
            return;
        }

        final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
        if (player == null) {
            ClientRoutePlatformGuard.clearSnapshot();
            return;
        }

        final String content = ((PacketRequestResponseBaseAccessor) (Object) packet).mtrlock$getContent();
        if (content == null || content.isEmpty()) {
            ClientRoutePlatformGuard.clearSnapshot();
            return;
        }

        final String uuid = player.getUuidAsString();
        final List<String> denied;
        final Text message;

        // 1.1.0：客户端还没有分享 / 团队快照（阶段 5 才同步），canEditOrUnknown 对非创建者
        // fail-open，避免误拦“对象已分享给其团队”的成员；精确拦截仍由服务端兜底。
        if (packet instanceof PacketUpdateData) {
            // 1.5.2：MTR DashboardScreen 已先本地修改线路，再调用 sendPacketToServer。
            // 这里消费本地修改前的快照，既做新增引用预判，也用于拒绝后的乐观更新回滚。
            final ClientRoutePlatformGuard.Snapshot snapshot = ClientRoutePlatformGuard.takeSnapshot();
            final MinecraftClientData clientData = MinecraftClientData.getDashboardInstance();
            final ClientRoutePlatformGuard.CheckResult result = ClientRoutePlatformGuard.check(
                    content, uuid, clientData, snapshot);
            denied = result.deniedObjectIds();
            if (result.stationPermissionDenied()) {
                ClientRoutePlatformGuard.rollbackAddedPlatforms(clientData, snapshot);
                message = new Text(net.minecraft.text.Text.translatable(PermissionGuard.STATION_ADDITION_DENY_KEY));
            } else {
                message = Text.of(MESSAGE_EDIT);
            }
        } else if (packet instanceof PacketDeleteData) {
            ClientRoutePlatformGuard.clearSnapshot();
            denied = PermissionGuard.findDeniedInDelete(
                    content,
                    ClientOwnership::hasCreator,
                    ChildParents::get,
                    objectId -> ClientOwnership.canEditOrUnknown(objectId, uuid));
            message = Text.of(MESSAGE_DELETE);
        } else {
            ClientRoutePlatformGuard.clearSnapshot();
            return;
        }

        if (!denied.isEmpty()) {
            player.sendMessage(message, true);
            Mtrlock.LOGGER.info("[mtrlock] 客户端拦截 {} 权限不足: {}", uuid, denied);
            ci.cancel();
        }
    }
}
