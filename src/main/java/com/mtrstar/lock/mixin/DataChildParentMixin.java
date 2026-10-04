package com.mtrstar.lock.mixin;

import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.protect.ProtectionIndex;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 功能 5 / 方案 A：维护 platform→station / siding→depot 从属索引。
 *
 * <p>注入点：{@code org.mtr.core.data.Data.sync()} 的 RETURN。
 * MTR 的 {@code sync()} 内部会调用 {@code mapAreasAndSavedRails(platforms, stations)} /
 * {@code mapAreasAndSavedRails(sidings, depots)}，按几何包含关系把
 * {@code Platform.area} / {@code Siding.area} 挂好。此时读取 {@code area} 就能建立索引。</p>
 *
 * <p>{@code Simulator} 在加载与 tick 时都会调用 {@code Data.sync()}（反编译确认），
 * 所以索引对服务端加载出来的历史对象同样有效。</p>
 *
 * <p>1.3.0 起同一个注入点还负责<b>区域方块保护的空间索引对账</b>（{@link ProtectionIndex}）：
 * {@code Simulator} 构造函数在 FileLoader 全部读完（内部 {@code Future.get()} 阻塞汇合）
 * 之后才调用 {@code sync()}，因此这里是「服务端数据已加载完成」的可靠信号——
 * 服务器重启后不会出现保护失效。用 {@code instanceof Simulator} 把客户端
 * {@code ClientData} 排除在外（客户端不处理方块事件）。</p>
 *
 * <p>注意：只做“索引”，不参与权限判定本身；删除成功后的清理见
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
}
