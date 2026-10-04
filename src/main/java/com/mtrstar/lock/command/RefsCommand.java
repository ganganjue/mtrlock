package com.mtrstar.lock.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mtrstar.lock.perm.PermissionChecker;
import com.mtrstar.lock.protect.ProtectionIndex;
import com.mtrstar.lock.refs.RemovedRefsData;
import com.mtrstar.lock.refs.RouteRefReconciler;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /mtrlock refs ...} 子命令（1.4.0，OP 3+）。
 *
 * <ul>
 *   <li>{@code status}：账本记录数、加载状态、文件路径；</li>
 *   <li>{@code list [routeId]}：列出被移除的引用（可按线路过滤）；</li>
 *   <li>{@code restore <routeId> <platformId>}：手动把某条引用加回线路并删记录，
 *       用于 owner 永不再上线 / 车站已重建等自动恢复覆盖不到的场景。</li>
 * </ul>
 *
 * <p>权限校验复用 {@link MtrlockCommand#requireAdmin}（OP 权限等级 3+），与
 * {@code /mtrlock protect ...} 保持同一套规则。</p>
 */
public final class RefsCommand {

    private RefsCommand() {
    }

    /** 构建 {@code refs} 节点，由 {@link MtrlockCommand#register} 挂到 {@code /mtrlock} 下。 */
    static LiteralArgumentBuilder<ServerCommandSource> build() {
        return CommandManager.literal("refs")
                .then(CommandManager.literal("status")
                        .executes(RefsCommand::status))
                .then(CommandManager.literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(CommandManager.argument("routeId", StringArgumentType.word())
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "routeId")))))
                .then(CommandManager.literal("restore")
                        .then(CommandManager.argument("routeId", StringArgumentType.word())
                                .then(CommandManager.argument("platformId", StringArgumentType.word())
                                        .executes(RefsCommand::restore))));
    }

    /** /mtrlock refs status —— 账本概况。 */
    private static int status(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        if (!MtrlockCommand.requireAdmin(ctx)) return 0;

        for (String line : statusLines(RemovedRefsData.getInstance())) {
            TeamCommand.ok(ctx, line);
        }
        return 1;
    }

    /**
     * {@code status} 的文案（纯函数，便于单测）。
     *
     * @param data 账本；null 视为未加载
     * @return 逐行文案
     */
    public static List<String> statusLines(RemovedRefsData data) {
        final List<String> lines = new ArrayList<>();
        lines.add("=== mtrlock 线路引用账本 ===");
        if (data == null) {
            lines.add("  状态：尚未初始化");
            return lines;
        }
        lines.add("  记录条数：" + data.size() + " 条，涉及 " + data.routeCount() + " 条线路");
        final boolean failed = data.isLoadFailed();
        lines.add("  加载状态：" + (failed
                ? "上次加载失败（本轮对账已跳过，不会覆盖坏文件）"
                : "正常"));
        lines.add("  数据文件：" + data.file());
        lines.add("  保留期限：" + RemovedRefsData.RETENTION_DAYS + " 天（启动时清理更早的记录）");
        return lines;
    }

    /** /mtrlock refs list [routeId] —— 列出被移除的引用。 */
    private static int list(CommandContext<ServerCommandSource> ctx, String routeIdFilter) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        if (!MtrlockCommand.requireAdmin(ctx)) return 0;

        final RemovedRefsData data = RemovedRefsData.getInstance();
        final Map<String, List<RemovedRefsData.RemovedRefEntry>> all = data.getAll();
        if (all.isEmpty()) {
            TeamCommand.ok(ctx, "当前没有被移除的线路引用（记录数 0）");
            return 1;
        }

        int shown = 0;
        for (Map.Entry<String, List<RemovedRefsData.RemovedRefEntry>> entry : all.entrySet()) {
            if (routeIdFilter != null && !routeIdFilter.isEmpty() && !routeIdFilter.equals(entry.getKey())) {
                continue;
            }
            TeamCommand.ok(ctx, entry.getKey() + "（" + entry.getValue().size() + " 条）：");
            for (RemovedRefsData.RemovedRefEntry ref : entry.getValue()) {
                TeamCommand.ok(ctx, "  platform " + ref.platformId
                        + (ref.stationObjectId == null ? "（父车站已删）" : "  ← " + ref.stationObjectId)
                        + "，移除于 " + ref.removedAt);
            }
            shown++;
        }
        if (shown == 0) {
            TeamCommand.err(ctx, "没有找到线路 " + routeIdFilter + " 的记录");
            return 0;
        }
        TeamCommand.ok(ctx, "共 " + shown + " 条线路，使用 /mtrlock refs restore <routeId> <platformId> 手动恢复");
        return 1;
    }

    /** /mtrlock refs restore &lt;routeId&gt; &lt;platformId&gt; —— 手动恢复一条引用。 */
    private static int restore(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        if (!MtrlockCommand.requireAdmin(ctx)) return 0;

        final String routeId = StringArgumentType.getString(ctx, "routeId");
        if (!CommandUtil.isValidObjectId(routeId)
                || !routeId.startsWith(PermissionChecker.PREFIX_ROUTE + ":")) {
            TeamCommand.err(ctx, "线路 ID 格式错误。正确格式如 route:0B0829457F350DE9");
            return 0;
        }
        final long platformId;
        try {
            platformId = Long.parseLong(StringArgumentType.getString(ctx, "platformId"));
        } catch (NumberFormatException e) {
            TeamCommand.err(ctx, "platformId 必须是数字（可用 /mtrlock refs list 查看）");
            return 0;
        }

        final RemovedRefsData data = RemovedRefsData.getInstance();
        if (data.isLoadFailed()) {
            TeamCommand.err(ctx, "引用账本上次加载失败，为避免破坏恢复源，本命令暂不可用");
            return 0;
        }
        if (!data.hasRemoved(routeId, platformId)) {
            TeamCommand.err(ctx, "账本里没有 " + routeId + " / platform " + platformId + " 的记录");
            return 0;
        }
        if (!ProtectionIndex.hasRememberedServerData()) {
            TeamCommand.err(ctx, "服务端 MTR 数据尚未就绪，无法恢复；请等服务器完全启动后再试");
            return 0;
        }

        final Data serverData = ProtectionIndex.getRememberedServerData();
        final Route route = RouteRefReconciler.routeById(serverData, routeId);
        if (route == null) {
            TeamCommand.err(ctx, "当前存档里找不到线路 " + routeId + "（记录可能已过期）");
            return 0;
        }
        final Platform platform = serverData.platformIdMap.get(platformId);
        if (platform == null) {
            TeamCommand.err(ctx, "当前存档里找不到 platform " + platformId + "，无法恢复");
            return 0;
        }

        boolean already = false;
        for (RoutePlatformData rpd : route.getRoutePlatforms()) {
            if (rpd.getPlatform() != null && rpd.getPlatform().getId() == platformId) {
                already = true;
                break;
            }
        }
        if (!already) {
            route.getRoutePlatforms().add(new RoutePlatformData(platformId));
        }
        RouteRefReconciler.forgetRemoved(data, routeId, platformId);
        data.flush();

        TeamCommand.ok(ctx, (already ? "线路已经引用 platform " : "已恢复 platform ") + platformId
                + " → " + routeId + (already ? "（仅清掉记录）" : ""));
        return 1;
    }
}
