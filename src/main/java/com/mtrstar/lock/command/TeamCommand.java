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

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** /team 下的全部子命令。 */
public final class TeamCommand {

    private TeamCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("team")
                        .then(CommandManager.literal("create")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::create)))
                        .then(CommandManager.literal("list")
                                .executes(TeamCommand::list))
                        .then(CommandManager.literal("info")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::info)))
                        .then(CommandManager.literal("apply")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::apply)))
                        .then(CommandManager.literal("accept")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(TeamCommand::accept))))
                        .then(CommandManager.literal("deny")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(TeamCommand::deny))))
                        .then(CommandManager.literal("invite")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(TeamCommand::invite))))
                        .then(CommandManager.literal("join")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::join)))
                        .then(CommandManager.literal("decline")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::decline)))
                        .then(CommandManager.literal("leave")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::leave)))
                        .then(CommandManager.literal("kick")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(TeamCommand::kick))))
                        .then(CommandManager.literal("transfer")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(TeamCommand::transfer))))
                        .then(CommandManager.literal("disband")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(TeamCommand::disband)))
                        .then(CommandManager.literal("share")
                                .then(CommandManager.argument("objectId", StringArgumentType.word())
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .executes(TeamCommand::share))))
                        .then(CommandManager.literal("unshare")
                                .then(CommandManager.argument("objectId", StringArgumentType.word())
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .executes(TeamCommand::unshare))))
                        .then(CommandManager.literal("shares")
                                .executes(TeamCommand::shares))
        );
    }

    private static int create(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final String name = StringArgumentType.getString(ctx, "name");
        final Team team = TeamData.getInstance().createTeam(name, uuid(player));
        if (team == null) {
            err(ctx, "创建失败：名字非法（空 / 超 32 字 / 含控制字符）、名字重复，或你已加入 " + TeamData.MAX_TEAMS_PER_PLAYER + " 个团队");
            return 0;
        }
        ok(ctx, "已创建团队 [" + team.getName() + "]");
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final List<Team> teams = TeamData.getInstance().getTeamsOfPlayer(uuid(player));
        if (teams.isEmpty()) {
            ok(ctx, "你还没有加入任何团队。");
            ok(ctx, "  /team create <名字>  创建团队");
            ok(ctx, "  /team apply <名字>   申请加入");
            return 1;
        }
        ok(ctx, "你所在的团队（" + teams.size() + "/" + TeamData.MAX_TEAMS_PER_PLAYER + "）：");
        for (Team t : teams) {
            final String role = t.isOwner(uuid(player)) ? "创建者" : "成员";
            ok(ctx, "  [" + t.getName() + "] " + role + "，" + t.getMembers().size() + " 人");
        }
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) {
        final String name = StringArgumentType.getString(ctx, "name");
        final Team team = TeamData.getInstance().getTeamByName(name);
        if (team == null) {
            err(ctx, "团队不存在：" + name);
            return 0;
        }
        ok(ctx, "团队 [" + team.getName() + "]");
        ok(ctx, "  创建者：" + nameOf(ctx, team.getOwnerUuid()));
        ok(ctx, "  成员（" + team.getMembers().size() + "）：");
        for (String m : team.getMembers()) {
            ok(ctx, "    " + nameOf(ctx, m) + (m.equals(team.getOwnerUuid()) ? "（创建者）" : ""));
        }
        final Set<String> apps = team.getPendingApplications();
        final Set<String> invs = team.getPendingInvitations();
        if (!apps.isEmpty()) {
            ok(ctx, "  待批准申请（" + apps.size() + "）：" + joinNames(ctx, apps));
        }
        if (!invs.isEmpty()) {
            ok(ctx, "  待接受邀请（" + invs.size() + "）：" + joinNames(ctx, invs));
        }
        return 1;
    }

    private static int apply(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final String name = StringArgumentType.getString(ctx, "name");
        final Team team = TeamData.getInstance().getTeamByName(name);
        if (team == null) {
            err(ctx, "团队不存在：" + name);
            return 0;
        }
        if (!TeamData.getInstance().applyToJoin(team.getTeamId(), uuid(player))) {
            err(ctx, "申请失败：你已是成员，或已经申请过");
            return 0;
        }
        ok(ctx, "已向 [" + team.getName() + "] 提交申请，等待创建者批准");
        notifyPlayer(ctx, team.getOwnerUuid(),
                player.getName().getString() + " 申请加入你的团队 [" + team.getName() + "]");
        return 1;
    }

    private static int accept(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        final String targetUuid = resolveOnlineUuid(ctx, StringArgumentType.getString(ctx, "player"));
        if (targetUuid == null) {
            err(ctx, "找不到在线玩家：" + StringArgumentType.getString(ctx, "player"));
            return 0;
        }
        if (!TeamData.getInstance().approveApplication(team.getTeamId(), uuid(player), targetUuid)) {
            err(ctx, "批准失败：你不是创建者，或对方没有待批申请，或对方已达 " + TeamData.MAX_TEAMS_PER_PLAYER + " 个团队上限");
            return 0;
        }
        ok(ctx, "已批准加入 [" + team.getName() + "]");
        notifyPlayer(ctx, targetUuid, "你已被批准加入团队 [" + team.getName() + "]");
        return 1;
    }

    private static int deny(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        final String targetUuid = resolveOnlineUuid(ctx, StringArgumentType.getString(ctx, "player"));
        if (targetUuid == null) {
            err(ctx, "找不到在线玩家");
            return 0;
        }
        if (!TeamData.getInstance().denyApplication(team.getTeamId(), uuid(player), targetUuid)) {
            err(ctx, "拒绝失败：你不是创建者，或对方没有待批申请");
            return 0;
        }
        ok(ctx, "已拒绝该申请");
        notifyPlayer(ctx, targetUuid, "你的团队申请被拒绝：" + team.getName());
        return 1;
    }

    private static int invite(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        final String targetUuid = resolveOnlineUuid(ctx, StringArgumentType.getString(ctx, "player"));
        if (targetUuid == null) {
            err(ctx, "找不到在线玩家");
            return 0;
        }
        if (!TeamData.getInstance().invite(team.getTeamId(), uuid(player), targetUuid)) {
            err(ctx, "邀请失败：你不是创建者，或对方已是成员 / 已被邀请");
            return 0;
        }
        ok(ctx, "已邀请加入 [" + team.getName() + "]");
        notifyPlayer(ctx, targetUuid, "你被邀请加入团队 [" + team.getName() + "]，用 /team join " + team.getName() + " 接受");
        return 1;
    }

    private static int join(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        if (!TeamData.getInstance().acceptInvitation(team.getTeamId(), uuid(player))) {
            err(ctx, "加入失败：你没有待接受邀请，或已达 " + TeamData.MAX_TEAMS_PER_PLAYER + " 个团队上限");
            return 0;
        }
        ok(ctx, "已加入团队 [" + team.getName() + "]");
        return 1;
    }

    private static int decline(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        if (!TeamData.getInstance().declineInvitation(team.getTeamId(), uuid(player))) {
            err(ctx, "拒绝失败：你没有待接受邀请");
            return 0;
        }
        ok(ctx, "已拒绝邀请");
        return 1;
    }

    private static int leave(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        if (team.isOwner(uuid(player))) {
            err(ctx, "创建者不能直接退出。请先 /team transfer 转让，或 /team disband 解散");
            return 0;
        }
        if (!TeamData.getInstance().leaveTeam(team.getTeamId(), uuid(player))) {
            err(ctx, "退出失败：你不是该团队成员");
            return 0;
        }
        ok(ctx, "已退出团队 [" + team.getName() + "]");
        return 1;
    }

    private static int kick(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        final String targetUuid = resolveOnlineUuid(ctx, StringArgumentType.getString(ctx, "player"));
        if (targetUuid == null) {
            err(ctx, "找不到在线玩家");
            return 0;
        }
        if (!TeamData.getInstance().kickMember(team.getTeamId(), uuid(player), isAdmin(player), targetUuid)) {
            err(ctx, "踢人失败：你没有权限（需为创建者或 OP 3+），或不能踢创建者 / 自己");
            return 0;
        }
        ok(ctx, "已踢出成员");
        notifyPlayer(ctx, targetUuid, "你被踢出团队 [" + team.getName() + "]");
        return 1;
    }

    private static int transfer(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        final String targetUuid = resolveOnlineUuid(ctx, StringArgumentType.getString(ctx, "player"));
        if (targetUuid == null) {
            err(ctx, "找不到在线玩家");
            return 0;
        }
        if (!TeamData.getInstance().transferOwnership(team.getTeamId(), uuid(player), isAdmin(player), targetUuid)) {
            err(ctx, "转让失败：你没有权限（需为创建者或 OP 3+），或目标不是成员");
            return 0;
        }
        ok(ctx, "已把 [" + team.getName() + "] 的创建者转让");
        notifyPlayer(ctx, targetUuid, "你已成为团队 [" + team.getName() + "] 的创建者");
        return 1;
    }

    private static int disband(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;
        if (!team.isOwner(uuid(player)) && !isAdmin(player)) {
            err(ctx, "解散失败：需要团队创建者或 OP 3+");
            return 0;
        }
        final Set<String> members = team.getMembers();
        if (!TeamData.getInstance().deleteTeam(team.getTeamId())) {
            err(ctx, "解散失败：团队不存在");
            return 0;
        }
        ok(ctx, "已解散团队 [" + team.getName() + "]");
        for (String m : members) {
            notifyPlayer(ctx, m, "你所在的团队 [" + team.getName() + "] 已被解散");
        }
        return 1;
    }

    private static int share(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final String objectId = StringArgumentType.getString(ctx, "objectId");
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;

        if (!CommandUtil.isValidObjectId(objectId)) {
            err(ctx, "对象 ID 格式错误。正确格式如 route:0B0829457F350DE9。用 /mtrlock my 查看你的对象");
            return 0;
        }
        final String creator = OwnershipData.getInstance().getCreator(objectId);
        if (creator == null) {
            err(ctx, "该对象没有归属记录（可能是模组安装前创建的）");
            return 0;
        }
        if (!creator.equals(uuid(player))) {
            err(ctx, "只有对象创建者可以分享");
            return 0;
        }
        if (!ShareData.getInstance().share(objectId, team.getTeamId())) {
            err(ctx, "分享失败：该对象已经分享给这个团队");
            return 0;
        }
        ok(ctx, "已把 " + objectId + " 分享给 [" + team.getName() + "]");
        return 1;
    }

    private static int unshare(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final String objectId = StringArgumentType.getString(ctx, "objectId");
        final Team team = requireTeamByName(ctx);
        if (team == null) return 0;

        if (!CommandUtil.isValidObjectId(objectId)) {
            err(ctx, "对象 ID 格式错误");
            return 0;
        }
        final String creator = OwnershipData.getInstance().getCreator(objectId);
        if (creator == null || !creator.equals(uuid(player))) {
            err(ctx, "只有对象创建者可以取消分享");
            return 0;
        }
        if (!ShareData.getInstance().unshare(objectId, team.getTeamId())) {
            err(ctx, "取消失败：该对象没有分享给这个团队");
            return 0;
        }
        ok(ctx, "已取消 " + objectId + " 对 [" + team.getName() + "] 的分享");
        return 1;
    }

    private static int shares(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        final String uuid = uuid(player);
        final var all = OwnershipData.getInstance().getAll();
        boolean any = false;
        for (var e : all.entrySet()) {
            if (!uuid.equals(e.getValue())) continue;
            final Set<String> teamIds = ShareData.getInstance().getTeamsOfObject(e.getKey());
            if (teamIds.isEmpty()) continue;
            if (!any) {
                ok(ctx, "你分享出去的对象：");
                any = true;
            }
            final StringBuilder sb = new StringBuilder("  " + e.getKey() + " → ");
            boolean first = true;
            for (String tid : teamIds) {
                final Team t = TeamData.getInstance().getTeam(tid);
                if (!first) sb.append("，");
                sb.append(t != null ? t.getName() : tid);
                first = false;
            }
            ok(ctx, sb.toString());
        }
        if (!any) {
            ok(ctx, "你还没有分享任何对象。用 /team share <对象ID> <团队名> 分享");
        }
        return 1;
    }

    static ServerPlayerEntity requirePlayer(CommandContext<ServerCommandSource> ctx) {
        final ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendError(Text.literal("此命令只能由玩家执行"));
            return null;
        }
        return p;
    }

    private static Team requireTeamByName(CommandContext<ServerCommandSource> ctx) {
        final String name = StringArgumentType.getString(ctx, "name");
        final Team team = TeamData.getInstance().getTeamByName(name);
        if (team == null) {
            err(ctx, "团队不存在：" + name);
        }
        return team;
    }

    static void ok(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), false);
    }

    static void err(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.RED), false);
    }

    private static void notifyPlayer(CommandContext<ServerCommandSource> ctx, String uuidStr, String msg) {
        if (uuidStr == null) return;
        try {
            final ServerPlayerEntity target = ctx.getSource().getServer()
                    .getPlayerManager().getPlayer(UUID.fromString(uuidStr));
            if (target != null) {
                target.sendMessage(Text.literal("[团队] " + msg).formatted(Formatting.YELLOW), false);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static String resolveOnlineUuid(CommandContext<ServerCommandSource> ctx, String playerName) {
        final ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
        return p == null ? null : p.getUuidAsString();
    }

    static String uuid(ServerPlayerEntity player) {
        return player.getUuidAsString();
    }

    static boolean isAdmin(ServerPlayerEntity player) {
        return player.hasPermissionLevel(3);
    }

    private static String nameOf(CommandContext<ServerCommandSource> ctx, String uuidStr) {
        if (uuidStr == null) return "?";
        try {
            final ServerPlayerEntity p = ctx.getSource().getServer()
                    .getPlayerManager().getPlayer(UUID.fromString(uuidStr));
            if (p != null) return p.getName().getString();
        } catch (IllegalArgumentException ignored) {
        }
        return uuidStr.length() >= 8 ? uuidStr.substring(0, 8) : uuidStr;
    }

    private static String joinNames(CommandContext<ServerCommandSource> ctx, Set<String> uuids) {
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String u : uuids) {
            if (!first) sb.append("，");
            sb.append(nameOf(ctx, u));
            first = false;
        }
        return sb.toString();
    }
}
