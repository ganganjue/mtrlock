package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.ChildParents;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import org.mtr.core.operation.DeleteDataRequest;
import org.mtr.core.operation.DeleteDataResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 功能 5（收尾）：删除成功后清理 {@code ownership.json} 归属记录与 {@link ChildParents} 从属索引。
 *
 * <p>注入点：{@code DeleteDataRequest.delete(Simulator)} 的 RETURN。
 * 只有这个方法真正执行（即删除没有被 {@link PacketEditPermissionMixin} 在包入口 cancel）时才会清理。</p>
 *
 * <ul>
 *   <li>删除 station / depot：清自身归属记录，并清掉挂在它下面的所有 platform / siding 索引；</li>
 *   <li>删除 route：清自身归属记录；</li>
 *   <li>删除 platform / siding：清自己的从属索引（子对象不单独记归属）。</li>
 * </ul>
 *
 * <p>对网页 dashboard 触发的删除同样生效（它没有玩家、不经过权限拦截，但删除本身仍会走到这里）。</p>
 */
@Mixin(value = DeleteDataRequest.class, remap = false)
public abstract class DeleteOwnershipCleanupMixin {

    @Inject(
            method = "delete(Lorg/mtr/core/simulation/Simulator;)Lorg/mtr/core/operation/DeleteDataResponse;",
            at = @At("RETURN"),
            remap = false
    )
    private void mtrlock$cleanupOwnership(Simulator simulator, CallbackInfoReturnable<DeleteDataResponse> cir) {
        final DeleteDataRequestSchemaAccessor self = (DeleteDataRequestSchemaAccessor) (Object) this;

        // 顶层对象：清归属记录；删 station / depot 时同时清掉挂在它下面的子对象索引
        removeCreatorsAndChildren(self.mtrlock$stationIds(), PermissionChecker.PREFIX_STATION);
        removeCreators(self.mtrlock$routeIds(), PermissionChecker.PREFIX_ROUTE);
        removeCreatorsAndChildren(self.mtrlock$depotIds(), PermissionChecker.PREFIX_DEPOT);

        // 子对象：清自己的从属索引（子对象不单独记归属记录）
        removeChildIndex(self.mtrlock$platformIds(), ChildParents.PREFIX_PLATFORM);
        removeChildIndex(self.mtrlock$sidingIds(), ChildParents.PREFIX_SIDING);
    }

    /** 清归属记录，并先清掉挂在它下面的所有子索引。 */
    private static void removeCreatorsAndChildren(LongArrayList ids, String prefix) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            final String parentObjectId = prefix + ":" + Utilities.numberToPaddedHexString(ids.getLong(i));
            ChildParents.removeChildrenOf(parentObjectId);
        }
        removeCreators(ids, prefix);
    }

    /** 清子对象自己的从属索引。 */
    private static void removeChildIndex(LongArrayList ids, String childPrefix) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            ChildParents.remove(childPrefix + ":" + Utilities.numberToPaddedHexString(ids.getLong(i)));
        }
    }

    private static void removeCreators(LongArrayList ids, String prefix) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            final String objectId = prefix + ":" + Utilities.numberToPaddedHexString(ids.getLong(i));
            final boolean hadCreator = OwnershipData.getInstance().hasCreator(objectId);
            OwnershipData.getInstance().removeCreator(objectId);
            if (hadCreator) {
                Mtrlock.LOGGER.info("[mtrlock] 删除清理归属记录: {}", objectId);
            }
        }
    }
}
