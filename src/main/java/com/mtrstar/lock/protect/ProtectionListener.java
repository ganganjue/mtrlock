package com.mtrstar.lock.protect;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域方块保护的 Fabric 事件监听（1.3.0，服务端权威）。
 *
 * <p>只做两件事：</p>
 * <ul>
 *   <li>{@link PlayerBlockBreakEvents#BEFORE}：破坏方块前判定，返回 false 取消；</li>
 *   <li>{@link UseBlockCallback}：手拿 {@link BlockItem} 且确实会放置时判定，
 *       返回 {@link ActionResult#FAIL} 取消。用 {@link ItemPlacementContext#canPlace()} 与
 *       {@link ItemPlacementContext#getBlockPos()} 拿到「真正会放下方块」的位置
 *       （点击可替换方块时是点击位，否则是点击面的相邻位），避免把开箱子之类的
 *       正常交互误判成放置。</li>
 * </ul>
 *
 * <p>客户端一律直接放行（{@code world.isClient()} → PASS），只在服务端做权威判定。
 * 判定异常时 fail-open 放行，绝不因为保护逻辑本身出错而挡住正常玩法。</p>
 */
public final class ProtectionListener {

    private ProtectionListener() {
    }

    /** 注册事件（在 {@code Mtrlock.onInitialize} 里调用一次）。 */
    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register(ProtectionListener::onBlockBreak);
        UseBlockCallback.EVENT.register(ProtectionListener::onUseBlock);
    }

    /** 破坏方块：返回 false 取消。 */
    private static boolean onBlockBreak(World world, PlayerEntity player, BlockPos pos,
                                        BlockState state, BlockEntity blockEntity) {
        if (world == null || world.isClient() || !(player instanceof ServerPlayerEntity)) {
            return true;
        }
        return allow((ServerPlayerEntity) player, pos, true);
    }

    /** 放置方块：只处理「手持方块物品且确实会放置」的交互。 */
    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (world == null || world.isClient() || !(player instanceof ServerPlayerEntity)) {
            return ActionResult.PASS;
        }
        if (hitResult == null || player.isSpectator()) {
            return ActionResult.PASS;
        }
        final ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem)) {
            return ActionResult.PASS;
        }
        final ItemPlacementContext context = new ItemPlacementContext(player, hand, stack, hitResult);
        if (!context.canPlace()) {
            return ActionResult.PASS;
        }
        final BlockPos target = context.getBlockPos();
        return allow((ServerPlayerEntity) player, target, false) ? ActionResult.PASS : ActionResult.FAIL;
    }

    /**
     * 统一判定：查空间索引 → 权限判定 → 拒绝时提示。
     *
     * @param breaking 破坏为 true，放置为 false（只影响提示文案与日志）
     * @return 允许返回 true
     */
    private static boolean allow(ServerPlayerEntity player, BlockPos pos, boolean breaking) {
        try {
            final ProtectionConfig config = ProtectionConfig.getInstance();
            if (!config.isEnabled()) {
                return true;
            }

            final List<ObjectRange> hits = ProtectionIndex.get().lookup(pos.getX(), pos.getZ());
            if (hits.isEmpty()) {
                return true;
            }

            final List<String> objectIds = new ArrayList<>(hits.size());
            for (ObjectRange range : hits) {
                objectIds.add(range.objectId());
            }

            // PermissionChecker 走 MTR 的 mapping 包装类型（与 Mixin 注入点同一套），
            // 这里把 Fabric 事件拿到的 Yarn 玩家包一层，复用同一条生产判定路径，
            // 不另写权限分支。
            final org.mtr.mapping.holder.ServerPlayerEntity mappedPlayer =
                    new org.mtr.mapping.holder.ServerPlayerEntity(player);
            final boolean allowed = BlockProtection.canModify(objectIds,
                    OwnershipData.getInstance()::hasCreator,
                    PermissionChecker.editPermissionFor(mappedPlayer));
            if (!allowed) {
                Mtrlock.LOGGER.info("[mtrlock] 拦截 {} 在 ({}, {}) 的方块{}，保护对象: {}",
                        player.getUuidAsString(), pos.getX(), pos.getZ(),
                        breaking ? "破坏" : "放置", objectIds);
                if (config.isNotifyPlayer()) {
                    player.sendMessage(Text.translatable(breaking
                                    ? "mtrlock.protection.deny_break"
                                    : "mtrlock.protection.deny_place")
                            .formatted(Formatting.RED), false);
                }
            }
            return allowed;
        } catch (Exception e) {
            // 保护逻辑自身出错绝不能误伤玩家：fail-open，放行。
            Mtrlock.LOGGER.error("[mtrlock] 方块保护判定异常，放行本次操作: ({}, {})",
                    pos.getX(), pos.getZ(), e);
            return true;
        }
    }
}
