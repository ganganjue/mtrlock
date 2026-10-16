package com.mtrstar.lock.protect;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.2：Axiom SetBlock 多目标坐标遍历逻辑单测。
 *
 * <p>不加载 Axiom；用 mock 的 {@code Map<BlockPos, BlockState>} 做 blocks，
 * 只验证 {@link AxiomBlockGuard#anyDenied} 的语义。</p>
 */
class AxiomBlockGuardTest {

    @Test
    @DisplayName("任一坐标被拒 → anyDenied=true")
    void anyDeniedWhenOnePositionIsDenied() {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(0, 64, 0), null);
        blocks.put(new BlockPos(10, 64, 10), null);

        assertTrue(AxiomBlockGuard.anyDenied(blocks.keySet(),
                (x, z) -> !(x == 10 && z == 10)));
    }

    @Test
    @DisplayName("全部坐标允许 / 空集合 → anyDenied=false")
    void allowedWhenAllPositionsAllowed() {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(0, 64, 0), null);
        blocks.put(new BlockPos(10, 64, 10), null);

        assertFalse(AxiomBlockGuard.anyDenied(blocks.keySet(), (x, z) -> true));
        assertFalse(AxiomBlockGuard.anyDenied(Collections.emptySet(), (x, z) -> false));
    }

    @Test
    @DisplayName("null 坐标 / null 参数安全，不 NPE")
    void nullSafety() {
        assertFalse(AxiomBlockGuard.anyDenied(null, (x, z) -> false));
        assertFalse(AxiomBlockGuard.anyDenied(
                Arrays.asList(null, new BlockPos(1, 0, 1)),
                (x, z) -> x == 1));
    }
}
