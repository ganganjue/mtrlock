package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.network.OwnershipSync;
import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.protect.ProtectionIndex;
import com.mtrstar.lock.refs.RefsNotices;
import com.mtrstar.lock.refs.RemovedRefsData;
import com.mtrstar.lock.refs.RouteRefReconciler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 功能 5 / 方案 A：维护 platform→station / siding→depot 从属索引。
 *
 * <p>注入点：{@code org.mtr.core.data.Data.sync()} 的 RETURN。
 * MTR 的 {@code sync()} 内部会调用 {@code mapAreasAndSavedRails(platforms, stations)} /
 * {@code mapAreasAndSavedRails(sidings, depots)}，按几何包含关系把
 * {@code Platform.area} / {@code Siding.area} 挂好。此时读取 {@code area} 就能建立索引。</p>
 *
 * <p>{@code Simulator} 在加载时以及结构变更（update / delete）时会调用 {@code Data.sync()}
 * （反编译确认；tick 里只有清理失效侧线这种条件路径才会调），
 * 所以索引对服务端加载出来的历史对象同样有效。</p>
 *
 * <p>1.3.0 起同一个注入点还负责<b>区域方块保护的空间索引对账</b>（{@link ProtectionIndex}）：
 * {@code Simulator} 构造函数在 FileLoader 全部读完（内部 {@code Future.get()} 阻塞汇合）
 * 之后才调用 {@code sync()}，因此这里是「服务端数据已加载完成」的可靠信号——
 * 服务器重启后不会出现保护失效。用 {@code instanceof Simulator} 把客户端
 * {@code ClientData} 排除在外（客户端不处理方块事件）。</p>
 *
 * <p>1.4.0 起再追加<b>线路引用对账</b>（{@link RouteRefReconciler}）：owner 对线路引用的
 * 某个站台失去权限时，把该站台从线路里临时移除并记账；权限恢复后自动加回。
 * 三个注入器共享同一个 RETURN 点但互不依赖，都直接读 {@code Data}。</p>
 *
 * <p>注意：只做「索引 / 对账」，不参与权限判定本身；删除成功后的清理见
 * {@link DeleteOwnershipCleanupMixin}。</p>
 */
@Mixin(value = Data.class, remap = false)
public abstract class DataChildParentMixin {

    @Inject(method = "sync()V", at = @At("RETURN"), remap = false)
    private void mtrlock$indexChildParents(CallbackInfo ci) {
        final Data self = (Data) (Object) this;

        for (Platform platform : self.platforms) {
            if (platform.area != null) {
                ChildParents.put(
                        ChildParents.PREFIX_PLATFORM + ":" + Utilities.numberToPaddedHexString(platform.getId()),
                        PermissionChecker.PREFIX_STATION + ":" + Utilities.numberToPaddedHexString(platform.area.getId()));
            }
        }

        for (Siding siding : self.sidings) {
            if (siding.area != null) {
                ChildParents.put(
                        ChildParents.PREFIX_SIDING + ":" + Utilities.numberToPaddedHexString(siding.getId()),
                        PermissionChecker.PREFIX_DEPOT + ":" + Utilities.numberToPaddedHexString(siding.area.getId()));
            }
        }
    }

    /**
     * 1.3.0：{@code Data#sync()} 之后对账区域方块保护的空间索引。
     *
     * <p>这是 MTR 自己的「数据一致」时刻，被以下路径调用：</p>
     * <ul>
     *   <li>{@code Simulator} 构造函数（服务端加载完成后）→ 重启后索引自动重建；</li>
     *   <li>{@code UpdateDataRequest#update()}（创建 / 编辑）与
     *       {@code DeleteDataRequest#delete()}（删除）内部；</li>
     *   <li>{@code Simulator.tick()} 清理掉失效侧线时。</li>
     * </ul>
     *
     * <p>不是每 tick 都调用，所以全量重建成本可以接受；重建是「构建新表 → 原子替换」，
     * 读侧不会看到空窗。</p>
     */
    @Inject(method = "sync()V", at = @At("RETURN"), remap = false)
    private void mtrlock$rebuildProtectionIndex(CallbackInfo ci) {
        // 只服务端：客户端 ClientData 不需要（也不应该）维护保护索引。
        if (!((Object) this instanceof Simulator)) {
            return;
        }
        final Data self = (Data) (Object) this;
        ProtectionIndex.rememberServerData(self);
        ProtectionIndex.rebuildFrom(self);
    }

    /**
     * 1.4.0：{@code Data#sync()} 之后对账线路引用。
     *
     * <p>第三个注入器，与前两个共享同一个 RETURN 点但互不依赖：它直接读当前
     * {@code Data}（{@code route.getRoutePlatforms()} + {@code data.platformIdMap.get(id).area}），
     * 不依赖 {@link ChildParents} 是否已刷新，也不依赖 Mixin 对同点注入器的顺序承诺。</p>
     *
     * <p>守卫：</p>
     * <ul>
     *   <li>{@code instanceof Simulator}：客户端 {@code ClientData} 不走对账；</li>
     *   <li>{@code removedRefs.isLoadFailed()}：账本是引用被移除后的<b>唯一恢复源</b>，
     *       坏文件时本轮完全跳过，绝不做任何移除。</li>
     * </ul>
     *
     * <p>有变更时：给在线 owner 发一条聊天提示（一次列全，不逐条刷屏）+ 写服务器日志。
     * 落盘由 {@link RemovedRefsData} 内部节流（5 秒 debounce 或 10 次对账）。</p>
     */
    @Inject(method = "sync()V", at = @At("RETURN"), remap = false)
    private void mtrlock$reconcileRouteRefs(CallbackInfo ci) {
        // 只服务端：客户端 ClientData 不持有归属 / 分享数据。
        if (!((Object) this instanceof Simulator)) {
            return;
        }
        final RemovedRefsData removedRefs = RemovedRefsData.getInstance();
        if (removedRefs.isLoadFailed()) {
            return;
        }
        final Data self = (Data) (Object) this;

        final RouteRefReconciler.Result result = RouteRefReconciler.reconcile(
                self,
                routeId -> OwnershipData.getInstance().getCreator(routeId),
                removedRefs,
                (ownerUuid, stationObjectId) -> PermissionChecker.canEdit(
                        parseUuid(ownerUuid), stationObjectId, false));

        if (!result.changed()) {
            return;
        }
        logResult(result);
        notifyOwners(result);
    }

    /** UUID 字符串解析失败（脏数据）→ null → canEdit 返回 false（fail-open，不移除）。 */
    private static UUID parseUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 服务器日志：移除 / 恢复 / 幽灵清理逐条记录（审计用）。 */
    private static void logResult(RouteRefReconciler.Result result) {
        for (RouteRefReconciler.Ref ref : result.removed()) {
            Mtrlock.LOGGER.info("[mtrlock] 线路引用移除（owner 已失去站台权限）: {} → platform {}",
                    ref.routeId(), ref.platformId());
        }
        for (RouteRefReconciler.Ref ref : result.restored()) {
            Mtrlock.LOGGER.info("[mtrlock] 线路引用恢复（owner 权限已恢复）: {} → platform {}",
                    ref.routeId(), ref.platformId());
        }
        for (RouteRefReconciler.Ref ref : result.purged()) {
            Mtrlock.LOGGER.info("[mtrlock] 线路引用记录清理（线路或站台已不存在）: {} → platform {}",
                    ref.routeId(), ref.platformId());
        }
    }

    /** 给在线 owner 发聊天提示：按 owner 分组，一次列全该 owner 的所有受影响线路。 */
    private static void notifyOwners(RouteRefReconciler.Result result) {
        final Set<String> owners = new LinkedHashSet<>();
        for (RouteRefReconciler.Ref ref : result.removed()) {
            owners.add(ownerOf(ref));
        }
        for (RouteRefReconciler.Ref ref : result.restored()) {
            owners.add(ownerOf(ref));
        }
        for (String owner : owners) {
            if (owner == null || owner.isEmpty()) {
                continue;
            }
            final List<RouteRefReconciler.Ref> removedForOwner = new ArrayList<>();
            final List<RouteRefReconciler.Ref> restoredForOwner = new ArrayList<>();
            for (RouteRefReconciler.Ref ref : result.removed()) {
                if (owner.equals(ownerOf(ref))) {
                    removedForOwner.add(ref);
                }
            }
            for (RouteRefReconciler.Ref ref : result.restored()) {
                if (owner.equals(ownerOf(ref))) {
                    restoredForOwner.add(ref);
                }
            }
            sendNotices(owner, removedForOwner, restoredForOwner);
        }
    }

    /** 从线路 id 反查 owner（ownership.json）。 */
    private static String ownerOf(RouteRefReconciler.Ref ref) {
        return ref == null || ref.routeId() == null ? null
                : OwnershipData.getInstance().getCreator(ref.routeId());
    }

    private static void sendNotices(String ownerUuid,
                                    List<RouteRefReconciler.Ref> removedForOwner,
                                    List<RouteRefReconciler.Ref> restoredForOwner) {
        final MinecraftServer server = OwnershipSync.getServer();
        if (server == null) {
            return; // 服务器还没启动完 / 已停：只写日志
        }
        final UUID uuid = parseUuid(ownerUuid);
        if (uuid == null) {
            return;
        }
        final ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player == null) {
            return; // owner 不在线：只写日志
        }
        if (!removedForOwner.isEmpty()) {
            player.sendMessage(Text.translatable(
                    RefsNotices.KEY_REMOVED, RefsNotices.summarize(removedForOwner)), false);
        }
        if (!restoredForOwner.isEmpty()) {
            player.sendMessage(Text.translatable(
                    RefsNotices.KEY_RESTORED, RefsNotices.summarize(restoredForOwner)), false);
        }
    }
}
