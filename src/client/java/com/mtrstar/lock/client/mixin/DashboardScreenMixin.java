package com.mtrstar.lock.client.mixin;

import com.mtrstar.lock.client.ClientRoutePlatformGuard;
import org.mtr.core.data.Route;
import org.mtr.mod.screen.DashboardScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.5.2：在 MTR 本地把站台加入线路之前保存旧 {@code routePlatformData} 快照。
 *
 * <p>反编译 MTR 4.0.0 确认 {@code DashboardScreen.onClickAddPlatformToRoute(long)}
 * 的顺序是“先 add 到 editingRoute，再 sendPacketToServer”。只有保存旧集合，
 * {@link com.mtrstar.lock.client.mixin.RegistryClientMixin} 才能在发包前正确识别
 * “本次新增的 platformId”，并在客户端预判拒绝后回滚本地乐观更新。</p>
 */
@Mixin(value = DashboardScreen.class, remap = false)
public abstract class DashboardScreenMixin {

    @Shadow
    private Route editingRoute;

    @Inject(method = "onClickAddPlatformToRoute(J)V", at = @At("HEAD"), remap = false)
    private void mtrlock$captureRoutePlatformsBeforeAdd(long platformId, CallbackInfo ci) {
        ClientRoutePlatformGuard.captureBeforeAdd(editingRoute);
    }
}
