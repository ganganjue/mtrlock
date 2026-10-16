package com.mtrstar.lock.protect;

import net.minecraft.util.math.BlockPos;

/**
 * 1.4.2：Axiom 多目标方块的坐标遍历判定。
 *
 * <p>不依赖 Axiom 类；Axiom mixin 只传入 blocks.keySet() 和“单点是否允许”的谓词，
 * 便于纯 JVM 单元测试覆盖“任一坐标被拒 → 整包拒绝”的语义。</p>
 */
public final class AxiomBlockGuard {

    private AxiomBlockGuard() {
    }

    /** 单点权限判定：返回 false 表示该坐标不可修改。 */
    public interface PositionPermission {
        boolean canModify(int x, int z);
    }

    /**
     * 任一坐标被拒绝则返回 true；空集合 / null 安全返回 false（fail-open）。
     */
    public static boolean anyDenied(Iterable<BlockPos> positions, PositionPermission permission) {
        if (positions == null || permission == null) {
            return false;
        }
        for (BlockPos pos : positions) {
            if (pos != null && !permission.canModify(pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }
}
