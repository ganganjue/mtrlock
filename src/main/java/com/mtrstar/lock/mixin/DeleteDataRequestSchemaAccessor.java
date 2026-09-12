package com.mtrstar.lock.mixin;

import org.mtr.core.generated.operation.DeleteDataRequestSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读访问 {@link DeleteDataRequestSchema} 里 {@code protected final} 的 id 列表。
 *
 * <p>用于删除成功后的归属记录清理（见 {@link DeleteOwnershipCleanupMixin}）。</p>
 */
@Mixin(value = DeleteDataRequestSchema.class, remap = false)
public interface DeleteDataRequestSchemaAccessor {

    @Accessor("stationIds")
    LongArrayList mtrlock$stationIds();

    @Accessor("platformIds")
    LongArrayList mtrlock$platformIds();

    @Accessor("routeIds")
    LongArrayList mtrlock$routeIds();

    @Accessor("sidingIds")
    LongArrayList mtrlock$sidingIds();

    @Accessor("depotIds")
    LongArrayList mtrlock$depotIds();
}
