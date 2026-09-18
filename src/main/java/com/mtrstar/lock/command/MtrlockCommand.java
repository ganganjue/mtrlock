package com.mtrstar.lock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamData;
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
        );
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
