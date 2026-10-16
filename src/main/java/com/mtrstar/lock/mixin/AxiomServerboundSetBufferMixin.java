package com.mtrstar.lock.mixin;

import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BlockBuffer;
import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.protect.ProtectionListener;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 1.4.2：Axiom SetBuffer 批量写入保护。
 *
 * <p>Axiom 的 SetBuffer 不走 ServerWorld#setBlockState，而是直接写 ChunkSection，也不会触发
 * Fabric 玩家事件。这里在 {@code applyBlockBufferServer} HEAD 遍历 BlockBuffer 的全部坐标，
 * 任一无权限就整包取消。</p>
 *
 * <p>注意：BlockBuffer.forEach 的泛型签名引用了 intermediary 的 class_2680，直接调用会导致
 * javac 在 named mapping 环境下找不到类；因此这里用反射调用 forEach + 动态代理
 * PositionConsumer，避免编译期依赖泛型签名。</p>
 */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBuffer", remap = false)
public abstract class AxiomServerboundSetBufferMixin {

    /** Axiom 的 PositionConsumer 接口；未安装 Axiom 时为 null，class load 不会失败。 */
    private static final Class<?> POSITION_CONSUMER_CLASS = lookupPositionConsumerClass();

    /** 启动时只查一次 BlockBuffer.forEach，避免每个 SetBuffer 包都做 Class.forName/getMethod。 */
    private static final Method FOR_EACH_METHOD = lookupForEachMethod();

    private static Class<?> lookupPositionConsumerClass() {
        try {
            return Class.forName("com.moulberry.axiom.collections.PositionConsumer");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method lookupForEachMethod() {
        if (POSITION_CONSUMER_CLASS == null) {
            return null;
        }
        try {
            return BlockBuffer.class.getMethod("forEach", POSITION_CONSUMER_CLASS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Inject(method = "applyBlockBufferServer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mtrlock$guardAxiomSetBuffer(BlockBuffer buffer,
                                                    ServerWorld world,
                                                    ChunkedBooleanRegion region,
                                                    ServerPlayerEntity player,
                                                    CallbackInfo ci) {
        if (buffer == null || player == null) {
            return;
        }
        if (POSITION_CONSUMER_CLASS == null || FOR_EACH_METHOD == null) {
            Mtrlock.LOGGER.error("[mtrlock] Axiom SetBuffer 保护未生效："
                    + "PositionConsumer / BlockBuffer.forEach 未找到，可能 Axiom 版本已更新");
            return;
        }

        final boolean[] denied = {false};
        try {
            final Object positionConsumer = Proxy.newProxyInstance(
                    POSITION_CONSUMER_CLASS.getClassLoader(),
                    new Class<?>[]{POSITION_CONSUMER_CLASS},
                    (proxy, method, args) -> {
                        if (!denied[0] && args != null && args.length == 4
                                && args[0] instanceof Integer
                                && args[2] instanceof Integer) {
                            final int x = (Integer) args[0];
                            final int z = (Integer) args[2];
                            if (!ProtectionListener.canPlayerModifyAt(player, x, z)) {
                                denied[0] = true;
                                // 抛异常提前终止 Axiom 的 forEach，避免大 buffer 继续遍历。
                                throw new EarlyDenySignal();
                            }
                        }
                        return null;
                    });
            FOR_EACH_METHOD.invoke(buffer, positionConsumer);
        } catch (InvocationTargetException e) {
            if (!denied[0]) {
                Mtrlock.LOGGER.error("[mtrlock] Axiom SetBuffer 保护判定异常，放行本次操作", e.getCause());
                return;
            }
            // 自己抛出的 EarlyDenySignal，继续走下面的拒绝逻辑。
        } catch (Throwable e) {
            Mtrlock.LOGGER.error("[mtrlock] Axiom SetBuffer 保护判定异常，放行本次操作", e);
            return;
        }

        if (denied[0]) {
            Mtrlock.LOGGER.info("[mtrlock] 拦截 Axiom SetBuffer {}，保护对象判定不通过",
                    player.getUuidAsString());
            ProtectionListener.notifyDenied(player, false);
            ci.cancel();
        }
    }

    private static final class EarlyDenySignal extends RuntimeException {
    }
}
