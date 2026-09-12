package com.mtrstar.lock.mixin;

import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.PermissionChecker;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
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
}
