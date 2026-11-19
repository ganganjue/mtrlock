package com.mtrstar.lock.protect;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProtectionListener} 水平多格方块副格展开测试（1.5.3）。
 *
 * <p>只验证坐标展开与「任一坐标被拒即整体拒绝」的纯判定；权限本身由既有
 * {@link BlockProtectionTest} 覆盖。</p>
 */
class ProtectionMultiBlockTest {

    private static Predicate<BlockPos> allowedOutside(Set<BlockPos> protectedPositions) {
        return pos -> !protectedPositions.contains(pos);
    }

    @Test
    @DisplayName("床跨界：主格在保护区内、破坏保护区分外的床头 → 拒绝")
    void bedCrossBoundaryBreakHeadDenied() {
        final BlockPos foot = new BlockPos(0, 64, 0);
        final BlockPos head = new BlockPos(0, 64, -1);
        final BlockPos otherPart = ProtectionListener.bedOtherPartPos(head, Direction.NORTH, false);

        assertEquals(foot, otherPart, "床头的另一半应回推到床脚");
        assertFalse(ProtectionListener.allPartsAllowed(head, otherPart, allowedOutside(Set.of(foot))),
                "床头在保护区外、床脚在保护区内时，破坏必须被拒绝");
    }

    @Test
    @DisplayName("双箱跨界：一箱在保护区内、放置外侧配对箱 → 拒绝")
    void chestCrossBoundaryPlacementDenied() {
        final BlockPos target = new BlockPos(0, 64, 0);
        final BlockPos pairedChest = new BlockPos(1, 64, 0);
        final BlockPos otherPart = ProtectionListener.chestOtherPartPos(target, Direction.NORTH, true);

        assertEquals(pairedChest, otherPart, "双箱 LEFT 朝北时，配对方应位于东侧");
        assertFalse(ProtectionListener.allPartsAllowed(target, otherPart,
                        allowedOutside(Set.of(pairedChest))),
                "配对箱落在保护区内时，新箱放置必须被拒绝");
    }

    @Test
    @DisplayName("双箱同区：两箱都在保护区内 → 放行")
    void chestSameProtectedAreaAllowed() {
        final BlockPos target = new BlockPos(0, 64, 0);
        final BlockPos pairedChest = new BlockPos(1, 64, 0);
        final BlockPos otherPart = ProtectionListener.chestOtherPartPos(target, Direction.NORTH, true);

        assertTrue(ProtectionListener.allPartsAllowed(target, otherPart,
                        allowedOutside(Set.of())),
                "目标箱和配对箱都无保护覆盖时应放行");
        assertEquals(pairedChest, otherPart);
    }

    @Test
    @DisplayName("单箱：无副格时只查主格，行为不变")
    void singleChestKeepsSinglePositionBehavior() {
        final BlockPos target = new BlockPos(0, 64, 0);
        final AtomicInteger checks = new AtomicInteger();

        assertTrue(ProtectionListener.allPartsAllowed(target, (BlockPos) null, pos -> {
            checks.incrementAndGet();
            return true;
        }));
        assertEquals(1, checks.get(), "单箱只应检查主格一次");
    }
}
