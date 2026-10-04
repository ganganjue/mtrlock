package com.mtrstar.lock.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mtrstar.lock.protect.ProtectionConfig;
import com.mtrstar.lock.protect.ProtectionIndex;
import com.mtrstar.lock.protect.SpatialIndex;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * {@code /mtrlock protect ...} 子命令（1.3.0，OP 3+）。
 *
 * <ul>
 *   <li>{@code status}：当前保护开关 / 索引规模 / 服务端数据是否就绪；</li>
 *   <li>{@code reload}：重新读取 {@code protection.properties} 并重建空间索引；</li>
 *   <li>{@code rebuild}：从当前存档（MTR 的 {@code Simulator} 数据）重建空间索引，
 *       用于网页 dashboard 直接改数据后的手动兜底。</li>
 * </ul>
 *
 * <p>权限校验复用 {@link MtrlockCommand#requireAdmin}（OP 权限等级 3+），
 * 与 {@code /mtrlock title ...} 保持同一套规则。命令只操作服务端内存索引与配置文件，
 * 不依赖客户端模组。</p>
 */
public final class ProtectCommand {

    private ProtectCommand() {
    }

    /** 构建 {@code protect} 节点，由 {@link MtrlockCommand#register} 挂到 {@code /mtrlock} 下。 */
    static LiteralArgumentBuilder<ServerCommandSource> build() {
        return CommandManager.literal("protect")
                .then(CommandManager.literal("status")
                        .executes(ProtectCommand::status))
                .then(CommandManager.literal("reload")
                        .executes(ProtectCommand::reload))
                .then(CommandManager.literal("rebuild")
                        .executes(ProtectCommand::rebuild));
    }

    /** /mtrlock protect status —— 显示配置与索引概况。 */
    private static int status(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        if (!MtrlockCommand.requireAdmin(ctx)) return 0;

        TeamCommand.ok(ctx, "=== mtrlock 区域方块保护 ===");
        for (String line : ProtectionConfig.getInstance().statusLines()) {
            TeamCommand.ok(ctx, line);
        }
        final SpatialIndex index = ProtectionIndex.get();
        TeamCommand.ok(ctx, "  索引对象数：" + index.size() + "，覆盖 chunk 数：" + index.chunkCount());
        TeamCommand.ok(ctx, "  服务端数据：" + (ProtectionIndex.hasRememberedServerData()
                ? "已就绪（" + ProtectionIndex.rememberedObjectCount() + " 个车站 / 车厂）"
                : "尚未就绪"));
        return 1;
    }

    /** /mtrlock protect reload —— 重新读配置 + 重建索引。 */
    private static int reload(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        if (!MtrlockCommand.requireAdmin(ctx)) return 0;

        ProtectionConfig.getInstance().load();
        ProtectionIndex.rebuildFromRememberedServerData();
        TeamCommand.ok(ctx, "已重新加载保护配置并重建索引（对象数 " + ProtectionIndex.get().size() + "）");
        warnIfDataNotReady(ctx);
        return 1;
    }

    /** /mtrlock protect rebuild —— 从当前存档数据重建索引。 */
    private static int rebuild(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        if (!MtrlockCommand.requireAdmin(ctx)) return 0;

        if (!ProtectionIndex.hasRememberedServerData()) {
            TeamCommand.err(ctx, "服务端 MTR 数据尚未就绪，无法重建索引；请等服务器完全启动后再试");
            return 0;
        }
        ProtectionIndex.rebuildFromRememberedServerData();
        TeamCommand.ok(ctx, "已从当前存档重建保护索引（对象数 " + ProtectionIndex.get().size()
                + "，覆盖 chunk 数 " + ProtectionIndex.get().chunkCount() + "）");
        return 1;
    }

    /** 数据没就绪时提示（命令本身仍算成功，因为配置已重载）。 */
    private static void warnIfDataNotReady(CommandContext<ServerCommandSource> ctx) {
        if (!ProtectionIndex.hasRememberedServerData()) {
            TeamCommand.err(ctx, "服务端 MTR 数据尚未就绪，索引暂为空；服务器完全启动后会自动重建");
        }
    }
}
