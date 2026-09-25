package com.mtrstar.lock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamData;
import com.mtrstar.lock.team.TeamPrefix;
import com.mtrstar.lock.team.TitleData;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** /mtrlock 下的子命令。 */
public final class MtrlockCommand {

    private MtrlockCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("mtrlock")
                        .then(CommandManager.literal("my")
                                .executes(MtrlockCommand::my))
                        .then(CommandManager.literal("info")
                                .then(CommandManager.argument("objectId", StringArgumentType.word())
                                        .executes(MtrlockCommand::info)))
                        // 1.2.0：管理员自定义称呼（OP 3+ 才能设置 / 清除）
                        .then(CommandManager.literal("title")
                                .executes(MtrlockCommand::titleSelf)
                                .then(CommandManager.literal("clear")
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(MtrlockCommand::titleClear)))
                                .then(CommandManager.argument("player", StringArgumentType.word())
                                        .then(CommandManager.argument("title", StringArgumentType.string())
                                                .executes(MtrlockCommand::titleSet))))
        );
    }

    // =====================================================================
    // 1.2.0：/mtrlock title ...
    // =====================================================================

    /** /mtrlock title —— 查看自己的当前称呼。 */
    private static int titleSelf(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        final String title = TitleData.getInstance().getTitle(TeamCommand.uuid(player));
        if (title == null || title.isEmpty()) {
            TeamCommand.ok(ctx, "你当前没有自定义称呼（显示为团队前缀 / " + TeamPrefix.NO_TEAM + "）");
        } else {
            TeamCommand.ok(ctx, "你当前的称呼：[" + title + "]");
        }
        return 1;
    }

    /** /mtrlock title &lt;玩家名&gt; &lt;称呼&gt; —— OP 3+ 设置（称呼含空格需双引号）。 */
    private static int titleSet(CommandContext<ServerCommandSource> ctx) {
        if (!requireAdmin(ctx)) return 0;
        final ServerPlayerEntity target = onlinePlayer(ctx, StringArgumentType.getString(ctx, "player"));
        if (target == null) {
            TeamCommand.err(ctx, "玩家不在线：" + StringArgumentType.getString(ctx, "player"));
            return 0;
        }
        final String uuid = TeamCommand.uuid(target);
        if (!TitleData.getInstance().setTitle(uuid, StringArgumentType.getString(ctx, "title"))) {
            TeamCommand.err(ctx, "称呼非法：不能为空、不超过 " + TitleData.MAX_TITLE_LENGTH
                    + " 个字符、且不能含控制字符");
            return 0;
        }
        final String saved = TitleData.getInstance().getTitle(uuid);
        TeamCommand.ok(ctx, "已把 " + target.getName().getString() + " 的称呼设为 [" + saved + "]");
        target.sendMessage(
                Text.literal("[mtrlock] 你的称呼已被设为 [" + saved + "]").formatted(Formatting.YELLOW),
                false);
        return 1;
    }

    /** /mtrlock title clear &lt;玩家名&gt; —— OP 3+ 清除。 */
    private static int titleClear(CommandContext<ServerCommandSource> ctx) {
        if (!requireAdmin(ctx)) return 0;
        final ServerPlayerEntity target = onlinePlayer(ctx, StringArgumentType.getString(ctx, "player"));
        if (target == null) {
            TeamCommand.err(ctx, "玩家不在线：" + StringArgumentType.getString(ctx, "player"));
            return 0;
        }
        final String name = target.getName().getString();
        if (!TitleData.getInstance().clearTitle(TeamCommand.uuid(target))) {
            TeamCommand.err(ctx, name + " 当前没有自定义称呼");
            return 0;
        }
        TeamCommand.ok(ctx, "已清除 " + name + " 的自定义称呼");
        target.sendMessage(
                Text.literal("[mtrlock] 你的自定义称呼已被清除").formatted(Formatting.YELLOW),
                false);
        return 1;
    }

    /** 命令层 OP 3+ 校验（非玩家 / 非 OP 打红字并返回 false）。 */
    private static boolean requireAdmin(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return false;
        if (!TeamCommand.isAdmin(player)) {
            TeamCommand.err(ctx, "需要 OP 权限等级 3 才能使用该命令");
            return false;
        }
        return true;
    }

    /** 按玩家名查在线玩家（Yarn 1.20.1 有 PlayerManager.getPlayer(String)，无 getPlayerListEntry）。 */
    private static ServerPlayerEntity onlinePlayer(CommandContext<ServerCommandSource> ctx, String playerName) {
        return ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
    }

    private static int my(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = TeamCommand.requirePlayer(ctx);
        if (player == null) return 0;
        final String uuid = TeamCommand.uuid(player);
        final List<String> mine = new ArrayList<>();
        for (var e : OwnershipData.getInstance().getAll().entrySet()) {
            if (uuid.equals(e.getValue())) {
                mine.add(e.getKey());
            }
        }
        if (mine.isEmpty()) {
            TeamCommand.ok(ctx, "你还没有创建任何 MTR 对象");
            return 1;
        }
        mine.sort(String::compareTo);
        TeamCommand.ok(ctx, "你创建的对象（" + mine.size() + "）：");
        for (String id : mine) {
            final Set<String> teamIds = ShareData.getInstance().getTeamsOfObject(id);
            final String suffix = teamIds.isEmpty()
                    ? ""
                    : "  [已分享给 " + teamIds.size() + " 个团队]";
            TeamCommand.ok(ctx, "  " + id + suffix);
        }
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) {
        final String objectId = StringArgumentType.getString(ctx, "objectId");
        if (!CommandUtil.isValidObjectId(objectId)) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("对象 ID 格式错误。正确格式如 route:0B0829457F350DE9")
                            .formatted(Formatting.RED),
                    false);
            return 0;
        }
        final String creator = OwnershipData.getInstance().getCreator(objectId);
        if (creator == null) {
            TeamCommand.ok(ctx, objectId + " 没有归属记录（可能是模组安装前创建的）");
            return 1;
        }
        TeamCommand.ok(ctx, "对象：" + objectId);
        TeamCommand.ok(ctx, "  创建者：" + nameOf(ctx, creator));
        final Set<String> teamIds = ShareData.getInstance().getTeamsOfObject(objectId);
        if (teamIds.isEmpty()) {
            TeamCommand.ok(ctx, "  未分享给任何团队");
        } else {
            TeamCommand.ok(ctx, "  分享给的团队：");
            for (String tid : teamIds) {
                final Team t = TeamData.getInstance().getTeam(tid);
                TeamCommand.ok(ctx, "    " + (t != null ? t.getName() : tid));
            }
        }
        return 1;
    }

    private static String nameOf(CommandContext<ServerCommandSource> ctx, String uuidStr) {
        try {
            final ServerPlayerEntity p = ctx.getSource().getServer()
                    .getPlayerManager().getPlayer(UUID.fromString(uuidStr));
            if (p != null) return p.getName().getString();
        } catch (IllegalArgumentException ignored) {
        }
        return uuidStr.length() >= 8 ? uuidStr.substring(0, 8) : uuidStr;
    }
}
