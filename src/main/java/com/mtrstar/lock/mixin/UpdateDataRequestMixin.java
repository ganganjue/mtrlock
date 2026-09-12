package com.mtrstar.lock.mixin;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PendingCreators;
import org.mtr.core.data.Data;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.tool.Utilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Mixin B：服务端真正把对象写进 {@code Simulator} 的时刻，找出“新增”的线路 / 车站 / 车厂并记录创建者。
 *
 * <p>注入点：{@link UpdateDataRequest#update()} 的 HEAD 与 RETURN。</p>
 *
 * <p>为什么选这里：</p>
 * <ul>
 *   <li>MTR 4.0.0 没有“创建单个对象”的专用包，创建/更新统一由 {@code UpdateDataRequest.update()} 完成
 *       （服务端 {@code OperationProcessor.process("update_data", ...)} → {@code new UpdateDataRequest(reader, simulator).update()}）。</li>
 *   <li>它内部对每个对象做 {@code existing = data.xxxIdMap.get(id)}；{@code existing == null} 时才
 *       {@code data.xxx.add(obj)}，也就是“新增”。</li>
 *   <li>方法执行前后对 {@code data.stations / routes / depots} 求差集，差集就是本次新增的对象；
 *       RETURN 时对象已经成功写入，符合“创建成功后”。</li>
 *   <li>这个方法的字段 {@code data} 就是 {@code Simulator}（{@code Simulator extends Data}），
 *       所以直接 diff simulator 的集合即可，无需额外获取服务端数据。</li>
 * </ul>
 *
 * <p>玩家信息不在这里：{@code update()} 由 {@code Simulator.tick()} 出队调用，早已离开网络线程。
 * 因此从 Mixin A 写好的 {@link PendingCreators} 里按 id 取回 UUID。</p>
 *
 * <p>只记录创建者，不拦截、不修改、不做权限判断。</p>
 */
@Mixin(value = UpdateDataRequest.class, remap = false)
public abstract class UpdateDataRequestMixin {

    /**
     * {@code UpdateDataRequest} 里保存目标数据的字段。
     * 在服务端它是 {@code Simulator}（{@code Simulator extends Data}），update() 会把对象合并进它的集合。
     */
    @Shadow
    @Final
    private Data data;

    /**
     * update() 是同步方法，HEAD→RETURN 之间必然在同一个线程；
     * 用 ThreadLocal 把“调用前的 id 快照”传给 RETURN 回调，避免静态共享状态互相踩。
     */
    private static final ThreadLocal<Snapshot> MTRLOCK_SNAPSHOT = new ThreadLocal<>();

    /** 调用前：记录 routes / stations / depots 三个集合里已有的 id。 */
    @Inject(
            method = "update()Lorg/mtr/core/operation/UpdateDataResponse;",
            at = @At("HEAD"),
            remap = false
    )
    private void mtrlock$snapshotBeforeUpdate(CallbackInfoReturnable<UpdateDataResponse> cir) {
        MTRLOCK_SNAPSHOT.set(new Snapshot(
                collectIds(data.stations),
                collectIds(data.routes),
                collectIds(data.depots)
        ));
    }

    /**
     * 调用后：与 HEAD 的快照对比，差集即本次新增对象。
     * 对每个新增对象，用它的 long id 去 {@link PendingCreators} 查创建者 UUID；
     * 查到就写入 {@link OwnershipData}，key = {@code prefix + ":" + Utilities.numberToPaddedHexString(id)}。
     */
    @Inject(
            method = "update()Lorg/mtr/core/operation/UpdateDataResponse;",
            at = @At("RETURN"),
            remap = false
    )
    private void mtrlock$recordNewCreators(CallbackInfoReturnable<UpdateDataResponse> cir) {
        final Snapshot before = MTRLOCK_SNAPSHOT.get();
        MTRLOCK_SNAPSHOT.remove();
        if (before == null) {
            return;
        }

        recordNew(data.stations, before.stations, "station");
        recordNew(data.routes, before.routes, "route");
        recordNew(data.depots, before.depots, "depot");
    }

    /** 把集合里所有对象的 long id 收成一个 Set，作为“调用前快照”。 */
    private static Set<Long> collectIds(Collection<? extends NameColorDataBase> collection) {
        final Set<Long> ids = new HashSet<>(Math.max(16, collection.size() * 2));
        for (NameColorDataBase object : collection) {
            ids.add(object.getId());
        }
        return ids;
    }

    /** 找出 collection 中相对 beforeIds 新增的对象并记录创建者。 */
    private static void recordNew(Collection<? extends NameColorDataBase> collection, Set<Long> beforeIds, String prefix) {
        for (NameColorDataBase object : collection) {
            final long id = object.getId();
            if (beforeIds.contains(id)) {
                continue; // 老对象（本次只是编辑），跳过
            }

            final String uuid = PendingCreators.poll(id);
            if (uuid == null) {
                continue; // 不是通过玩家包创建的（例如网页 dashboard / 命令 / 内部逻辑），没有玩家可归属
            }

            final String objectId = prefix + ":" + Utilities.numberToPaddedHexString(id);
            OwnershipData.getInstance().setCreator(objectId, uuid);
            // TODO: in-game 验证通过后删除
            Mtrlock.LOGGER.info("[mtrlock] 记录创建者 {} -> {}", objectId, uuid);
        }
    }

    /** 三个集合的 id 快照。 */
    private static final class Snapshot {

        private final Set<Long> stations;
        private final Set<Long> routes;
        private final Set<Long> depots;

        Snapshot(Set<Long> stations, Set<Long> routes, Set<Long> depots) {
            this.stations = stations;
            this.routes = routes;
            this.depots = depots;
        }
    }
}
