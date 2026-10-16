package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.perm.PermissionGuard;
import com.mtrstar.lock.protect.ProtectionConfig;
import com.mtrstar.lock.protect.ProtectionIndex;
import com.mtrstar.lock.protect.ProtectionRanges;
import com.mtrstar.lock.team.ResultCode;
import com.mtrstar.lock.team.ResultMessages;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.4.2：MTR /mtr 车厂命令保护。
 *
 * <p>MTR 4.0.0 和 4.0.5 的 {@code Init.depotOperationFromCommand} 私有 lambda 编号不同：</p>
 * <ul>
 *   <li>4.0.0：{@code $22}=allDepots，{@code $23}=按 name 过滤；</li>
 *   <li>4.0.5：{@code $23}=allDepots，{@code $25}=按 name 过滤。</li>
 * </ul>
 *
 * <p>所以这里用 {@code require = 0} 同时挂 3 个可能存在的方法名；回调里尝试读取
 * {@code name} 参数，读不到就是 allDepots 路径（空过滤）。这样 4.0.0 / 4.0.5 都不会
 * 因某个 lambda 不存在而启动失败。</p>
 */
@Mixin(targets = "org.mtr.mod.Init", remap = false)
public abstract class MtrDepotCommandMixin {

    @Inject(
            method = {
                    "lambda$depotOperationFromCommand$22",
                    "lambda$depotOperationFromCommand$23",
                    "lambda$depotOperationFromCommand$25"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void mtrlock$guardDepotCommand(@Coerce Object operation,
                                                  org.mtr.mapping.registry.CommandBuilder.ContextHandler handler,
                                                  CallbackInfoReturnable<Integer> cir) {
        if (handler == null || cir == null) {
            return;
        }
        String filter = "";
        try {
            filter = handler.getString("name");
        } catch (Exception ignored) {
            // allDepots 路径没有 name 参数：空过滤会对 NameColorDataBase.getDataByName 返回全部车厂。
        }
        mtrlock$checkDepotCommand(handler, filter, cir);
    }

    private static void mtrlock$checkDepotCommand(org.mtr.mapping.registry.CommandBuilder.ContextHandler handler,
                                                   String filter,
                                                   CallbackInfoReturnable<Integer> cir) {
        if (!ProtectionConfig.getInstance().isProtectDepotOperations()) {
            return;
        }

        final ServerPlayerEntity player = handler.getServerPlayer();
        if (player == null) {
            return; // 命令方块 / 控制台放行
        }

        final Data data = ProtectionIndex.getRememberedServerData();
        if (data == null) {
            return; // fail-open：MTR 数据尚未就绪
        }

        final String normalizedFilter = filter == null ? "" : filter;
        final List<Depot> depots = NameColorDataBase.getDataByName(data.depots, normalizedFilter);
        if (depots == null || depots.isEmpty()) {
            return;
        }

        final List<String> objectIds = new ArrayList<>(depots.size());
        for (Depot depot : depots) {
            if (depot == null) {
                continue;
            }
            objectIds.add(ProtectionRanges.objectIdOf(PermissionChecker.PREFIX_DEPOT, depot.getId()));
        }

        final List<String> denied = PermissionGuard.findDeniedInDepotOperation(
                objectIds,
                OwnershipData.getInstance()::hasCreator,
                PermissionChecker.editPermissionFor(player));
        if (denied.isEmpty()) {
            return;
        }

        player.sendMessage(Text.of(String.format(
                ResultMessages.zh(ResultCode.DEPOT_OPERATION_NO_PERMISSION), denied.get(0))), false);
        Mtrlock.LOGGER.info("[mtrlock] 拦截 {} 的 /mtr 车厂命令，权限不足: {}",
                player.getUuidAsString(), denied);
        cir.setReturnValue(0);
        cir.cancel();
    }
}
