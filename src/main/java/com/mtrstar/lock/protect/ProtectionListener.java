package com.mtrstar.lock.protect;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.perm.PermissionChecker;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BedBlock;
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
import java.util.Collections;
import java.util.List;

/**
 * 区域方块保护的 Fabric 事件监听与服务端判定入口（1.3.0，1.4.2 扩展）。
 *
 * <p>只做两件事：</p>
 * <ul>
 *   <li>{@link PlayerBlockBreakEvents#BEFORE}：破坏方块前判定，返回 false 取消；</li>
 *   <li>{@link UseBlockCallback}：手拿 {@link BlockItem} 且确实会放置时判定，
 *       返回 {@link ActionResult#FAIL} 取消。</li>
 * </ul>
 *
 * <p>1.4.2 起额外被命令 / Axiom / 活塞 / 爆炸 mixin 复用：</p>
 * <ul>
 *   <li>{@link #canPlayerModifyAt(ServerPlayerEntity, int, int)}：玩家操作的单点判定；</li>
 *   <li>{@link #hasProtectedObjectAt(int, int)}：有归属即视为保护中的区域，供无玩家身份的
 *       活塞 / 爆炸使用；</li>
 *   <li>{@link #notifyDenied(ServerPlayerEntity, boolean)}：统一的拒绝提示。</li>
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
        final BlockItem blockItem = (BlockItem) stack.getItem();
        final ItemPlacementContext context = new ItemPlacementContext(player, hand, stack, hitResult);
        if (!context.canPlace()) {
            return ActionResult.PASS;
        }
        final BlockPos target = context.getBlockPos();
        final ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        if (!allow(serverPlayer, target, false)) {
            return ActionResult.FAIL;
        }

        // 1.4.2：水平多格方块（床）副格也要查。床脚在 target，床头在玩家水平朝向偏移处；
        // 垂直多格（高草、门）的 x/z 与主格相同，MTR 2D 范围天然覆盖，不需要额外处理。
        if (blockItem.getBlock() instanceof BedBlock) {
            final BlockPos head = target.offset(context.getHorizontalPlayerFacing());
            if (!allow(serverPlayer, head, false)) {
                return ActionResult.FAIL;
            }
        }
        return ActionResult.PASS;
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

            final List<String> objectIds = protectedObjectIdsAt(pos.getX(), pos.getZ());
            if (objectIds.isEmpty()) {
                return true;
            }

            final boolean allowed = canPlayerModify(player, objectIds);
            if (!allowed) {
                Mtrlock.LOGGER.info("[mtrlock] 拦截 {} 在 ({}, {}) 的方块{}，保护对象: {}",
                        player.getUuidAsString(), pos.getX(), pos.getZ(),
                        breaking ? "破坏" : "放置", objectIds);
                notifyDenied(player, breaking);
            }
            return allowed;
        } catch (Exception e) {
            // 保护逻辑自身出错绝不能误伤玩家：fail-open，放行。
            Mtrlock.LOGGER.error("[mtrlock] 方块保护判定异常，放行本次操作: ({}, {})",
                    pos.getX(), pos.getZ(), e);
            return true;
        }
    }

    /**
     * 玩家能否修改指定 x/z 的方块。
     *
     * <p>供命令 / Axiom 等有玩家身份的 mixin 复用；判定异常 fail-open。</p>
     */
    public static boolean canPlayerModifyAt(ServerPlayerEntity player, int x, int z) {
        if (player == null) {
            return true; // 控制台 / 命令方块等无玩家身份，放行
        }
        try {
            final ProtectionConfig config = ProtectionConfig.getInstance();
            if (!config.isEnabled()) {
                return true;
            }
            final List<String> objectIds = protectedObjectIdsAt(x, z);
            if (objectIds.isEmpty()) {
                return true;
            }
            return canPlayerModify(player, objectIds);
        } catch (Exception e) {
            Mtrlock.LOGGER.error("[mtrlock] 方块保护判定异常，放行本次操作: ({}, {})", x, z, e);
            return true;
        }
    }

    /**
     * 玩家能否修改整个矩形 [minX,maxX] × [minZ,maxZ]。
     *
     * <p>保护是 2D 的，按 chunk 去重扫描；任一相交的受保护对象无权限则返回 false。
     * 供 /fill 这类范围命令复用，复杂度与方块数无关。</p>
     */
    public static boolean canPlayerModifyArea(ServerPlayerEntity player,
                                              int minX, int maxX,
                                              int minZ, int maxZ) {
        if (player == null) {
            return true; // 控制台 / 命令方块等无玩家身份，放行
        }
        try {
            final ProtectionConfig config = ProtectionConfig.getInstance();
            if (!config.isEnabled()) {
                return true;
            }
            final List<ObjectRange> hits = ProtectionIndex.get().lookupArea(minX, maxX, minZ, maxZ);
            if (hits.isEmpty()) {
                return true;
            }
            final List<String> objectIds = new ArrayList<>(hits.size());
            for (ObjectRange range : hits) {
                objectIds.add(range.objectId());
            }
            return canPlayerModify(player, objectIds);
        } catch (Exception e) {
            Mtrlock.LOGGER.error("[mtrlock] 方块保护范围判定异常，放行本次操作: ({}, {})-({}, {})",
                    minX, minZ, maxX, maxZ, e);
            return true;
        }
    }

    /**
     * 指定 x/z 是否存在「已有归属记录」的受保护对象。
     *
     * <p>用于活塞 / 爆炸这类没有玩家身份的间接破坏：有归属即拦，无归属放行。
     * 判定异常 fail-open。</p>
     */
    public static boolean hasProtectedObjectAt(int x, int z) {
        try {
            if (!ProtectionConfig.getInstance().isEnabled()) {
                return false;
            }
            for (ObjectRange range : ProtectionIndex.get().lookup(x, z)) {
                if (range != null && OwnershipData.getInstance().hasCreator(range.objectId())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Mtrlock.LOGGER.error("[mtrlock] 方块保护判定异常，放行本次间接破坏: ({}, {})", x, z, e);
            return false;
        }
    }

    /** 发送统一的拒绝提示（受 {@code notifyPlayer} 配置控制）。 */
    public static void notifyDenied(ServerPlayerEntity player, boolean breaking) {
        if (player == null) {
            return;
        }
        try {
            if (!ProtectionConfig.getInstance().isNotifyPlayer()) {
                return;
            }
            player.sendMessage(Text.translatable(breaking
                            ? "mtrlock.protection.deny_break"
                            : "mtrlock.protection.deny_place")
                    .formatted(Formatting.RED), false);
        } catch (Exception e) {
            Mtrlock.LOGGER.error("[mtrlock] 发送方块保护提示失败", e);
        }
    }

    /** 读取指定 x/z 覆盖到的保护对象 id 列表；不读配置、不判定权限。 */
    private static List<String> protectedObjectIdsAt(int x, int z) {
        final List<ObjectRange> hits = ProtectionIndex.get().lookup(x, z);
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> objectIds = new ArrayList<>(hits.size());
        for (ObjectRange range : hits) {
            objectIds.add(range.objectId());
        }
        return objectIds;
    }

    /**
     * 复用 {@link BlockProtection} 的权限判定：逐个覆盖对象检查创建者 / 团队成员 / OP 3+。
     */
    private static boolean canPlayerModify(ServerPlayerEntity player, List<String> objectIds) {
        final org.mtr.mapping.holder.ServerPlayerEntity mappedPlayer =
                new org.mtr.mapping.holder.ServerPlayerEntity(player);
        return BlockProtection.canModify(objectIds,
                OwnershipData.getInstance()::hasCreator,
                PermissionChecker.editPermissionFor(mappedPlayer));
    }
}
