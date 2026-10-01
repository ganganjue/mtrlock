package com.mtrstar.lock.network;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.gui.GuiType;
import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.gui.TeamGuiDispatcher;
import com.mtrstar.lock.gui.TitleActionType;
import com.mtrstar.lock.network.payload.GuiResult;
import com.mtrstar.lock.network.payload.OpenGuiS2C;
import com.mtrstar.lock.network.payload.PlayerEntry;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import com.mtrstar.lock.network.payload.TitleGuiAction;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.team.ActionResult;
import com.mtrstar.lock.team.ResultCode;
import com.mtrstar.lock.team.ResultMessages;
import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamActions;
import com.mtrstar.lock.team.TeamData;
import com.mtrstar.lock.team.TitleActions;
import com.mtrstar.lock.team.TitleData;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 服务端 GUI 网络层（1.2.3）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>注册两个 C2S 通道（团队 / 称号）；</li>
 *   <li>打开 GUI：先判客户端是否装了模组（{@code canSend}）、称号再判 OP 3+；</li>
 *   <li><b>服务端权威校验</b>：协议版本 → 限流 → 权限 / 目标解析 → 共享行为层
 *       （{@link TeamActions} / {@link TitleActions}）；</li>
 *   <li>操作后回 {@code GuiResult} 并重新下发<b>全量快照</b>，客户端从不做乐观更新。</li>
 * </ul>
 */
public final class ServerGuiNetworking {

    /** OP 权限等级 3（与命令 / {@code PermissionChecker} 一致）。 */
    public static final int ADMIN_PERMISSION_LEVEL = 3;

    private static final GuiRateLimiter RATE_LIMITER = new GuiRateLimiter();

    private ServerGuiNetworking() {
    }

    /** 注册 C2S 接收器（在 {@code Mtrlock.onInitialize} 调用一次）。 */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(GuiChannels.TEAM_ACTION,
                (server, player, handler, buf, responseSender) -> {
                    final TeamGuiAction action = GuiActionCodec.readTeamGuiAction(buf);
                    server.execute(() -> handleTeamAction(server, player, action));
                });

        ServerPlayNetworking.registerGlobalReceiver(GuiChannels.TITLE_ACTION,
                (server, player, handler, buf, responseSender) -> {
                    final TitleGuiAction action = GuiActionCodec.readTitleGuiAction(buf);
                    server.execute(() -> handleTitleAction(server, player, action));
                });
    }

    // =====================================================================
    // 打开 GUI
    // =====================================================================

    /**
     * 打开指定 GUI（由 {@code /mtrlock gui} 命令调用）。
     *
     * @return 真的发出去（客户端有模组、权限通过）返回 true
     */
    public static boolean openGui(ServerPlayerEntity player, GuiType type) {
        if (player == null || type == null) {
            return false;
        }
        // 称号 GUI：先判 OP 3+
        if (type == GuiType.TITLE && !player.hasPermissionLevel(ADMIN_PERMISSION_LEVEL)) {
            player.sendMessage(Text.literal(ResultMessages.zh(ResultCode.NEED_ADMIN))
                    .formatted(Formatting.RED), false);
            return false;
        }
        // 未装客户端：命令仍可用，但提示需要安装
        if (!ServerPlayNetworking.canSend(player, GuiChannels.OPEN_GUI)) {
            player.sendMessage(Text.literal(ResultMessages.zh(ResultCode.CLIENT_REQUIRED))
                    .formatted(Formatting.YELLOW), false);
            return false;
        }

        send(player, GuiChannels.OPEN_GUI,
                buf -> GuiActionCodec.writeOpenGui(buf, new OpenGuiS2C(GuiProtocol.VERSION, type)));
        if (type == GuiType.TITLE) {
            sendTitleSync(player, player.getUuidAsString());
        } else {
            sendTeamSync(player);
        }
        Mtrlock.LOGGER.info("[mtrlock] 已为 {} 打开 {} GUI", player.getUuidAsString(), type);
        return true;
    }

    // =====================================================================
    // C2S 处理
    // =====================================================================

    private static void handleTeamAction(MinecraftServer server, ServerPlayerEntity player, TeamGuiAction action) {
        final String actorUuid = player.getUuidAsString();

        if (action == null || action.protocolVersion() != GuiProtocol.VERSION) {
            sendResult(player, false, ResultCode.PROTOCOL_MISMATCH);
            return;
        }
        if (!RATE_LIMITER.allow(actorUuid, System.currentTimeMillis())) {
            sendResult(player, false, ResultCode.RATE_LIMITED);
            return;
        }

        final String resolvedTarget = resolveOnlineTarget(server, action.targetUuid(), action.targetName());
        if (needsTarget(action.type()) && resolvedTarget == null) {
            sendResult(player, false, ResultCode.TARGET_NOT_ONLINE);
            sendTeamSync(player);
            return;
        }

        final ActionResult result = TeamGuiDispatcher.dispatch(
                TeamActions.get(), actorUuid, player.hasPermissionLevel(ADMIN_PERMISSION_LEVEL), action, resolvedTarget);
        sendResult(player, result.ok(), result.code());
        sendTeamSync(player);
    }

    private static void handleTitleAction(MinecraftServer server, ServerPlayerEntity player, TitleGuiAction action) {
        final String actorUuid = player.getUuidAsString();

        if (action == null || action.protocolVersion() != GuiProtocol.VERSION) {
            sendResult(player, false, ResultCode.PROTOCOL_MISMATCH);
            return;
        }
        if (!RATE_LIMITER.allow(actorUuid, System.currentTimeMillis())) {
            sendResult(player, false, ResultCode.RATE_LIMITED);
            return;
        }
        // 服务端二次校验：称号 GUI 只有 OP 3+
        if (!TitleActions.canOpen(player.hasPermissionLevel(ADMIN_PERMISSION_LEVEL))) {
            sendResult(player, false, ResultCode.NEED_ADMIN);
            return;
        }

        // 只读同步：允许查看任意（含离线）玩家已保存的称号；缺省看自己。
        if (action.type() == TitleActionType.REQUEST_SYNC) {
            final String readTarget = action.targetUuid() != null && !action.targetUuid().isEmpty()
                    ? action.targetUuid()
                    : player.getUuidAsString();
            sendResult(player, true, ResultCode.SYNCED);
            sendTitleSync(player, readTarget);
            return;
        }
        if (action.type() == null) {
            sendResult(player, false, ResultCode.UNKNOWN_ACTION);
            sendTitleSync(player, player.getUuidAsString());
            return;
        }

        // 设置 / 清除：目标必须在线（与 /mtrlock title 命令一致）。
        final String target = resolveOnlineTarget(server, action.targetUuid(), null);
        if (target == null) {
            sendResult(player, false, ResultCode.TARGET_NOT_ONLINE);
            sendTitleSync(player, player.getUuidAsString());
            return;
        }

        final ActionResult result;
        if (action.type() == TitleActionType.SET) {
            result = TitleActions.get().setTitle(actorUuid, true, target, action.title(), action.color());
        } else if (action.type() == TitleActionType.RESET_COLOR) {
            result = TitleActions.get().resetColor(actorUuid, true, target);
        } else {
            result = TitleActions.get().clearTitle(actorUuid, true, target);
        }
        sendResult(player, result.ok(), result.code());
        sendTitleSync(player, target);
    }

    /** 玩家退服时清掉限流桶。 */
    public static void clearRateLimit(String playerUuid) {
        RATE_LIMITER.clear(playerUuid);
    }

    /** 这些操作必须有一个在线目标，解析不到直接拒绝（不进入数据层）。 */
    private static boolean needsTarget(TeamActionType type) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case APPROVE:
            case DENY:
            case INVITE:
            case KICK:
            case TRANSFER:
                return true;
            default:
                return false;
        }
    }

    /**
     * 把客户端给的 targetUuid / targetName 解析成<b>在线</b>玩家 UUID。
     *
     * <p>与命令层一致：踢人 / 转让 / 批准 / 邀请 / 设置称号都只对在线玩家生效，
     * 防止改包对离线玩家操作。任何解析失败返回 null。</p>
     */
    private static String resolveOnlineTarget(MinecraftServer server, String targetUuid, String targetName) {
        if (targetUuid != null && !targetUuid.isEmpty()) {
            try {
                final ServerPlayerEntity online = server.getPlayerManager().getPlayer(UUID.fromString(targetUuid));
                return online == null ? null : online.getUuidAsString();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return resolveOnlineUuid(server, targetName);
    }

    private static String resolveOnlineUuid(MinecraftServer server, String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        final ServerPlayerEntity target = server.getPlayerManager().getPlayer(playerName);
        return target == null ? null : target.getUuidAsString();
    }

    // =====================================================================
    // 全量快照
    // =====================================================================

    /** 团队 GUI 全量快照（打开时、每次操作后各发一次）。 */
    public static TeamGuiSnapshot buildTeamSnapshot(MinecraftServer server, ServerPlayerEntity player) {
        final String uuid = player.getUuidAsString();

        final List<TeamGuiSnapshot.TeamEntry> myTeams = new ArrayList<>();
        for (Team team : TeamData.getInstance().getTeamsOfPlayer(uuid)) {
            final List<PlayerEntry> members = new ArrayList<>();
            for (String memberUuid : team.getMembers()) {
                members.add(new PlayerEntry(memberUuid, nameOf(server, memberUuid)));
            }
            members.sort(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PlayerEntry::uuid));
            myTeams.add(new TeamGuiSnapshot.TeamEntry(
                    team.getTeamId(),
                    team.getName(),
                    team.getOwnerUuid(),
                    nameOf(server, team.getOwnerUuid()),
                    team.getMembers().size(),
                    ShareData.getInstance().getObjectsSharedToTeam(team.getTeamId()).size(),
                    team.isOwner(uuid),
                    members));
        }

        final List<TeamGuiSnapshot.PendingEntry> applications = new ArrayList<>();
        final List<TeamGuiSnapshot.PendingEntry> invitations = new ArrayList<>();
        for (Team team : TeamData.getInstance().getAllTeams()) {
            if (team.isOwner(uuid)) {
                for (String applicant : team.getPendingApplications()) {
                    applications.add(new TeamGuiSnapshot.PendingEntry(
                            team.getTeamId(), team.getName(), applicant, nameOf(server, applicant)));
                }
            }
            if (team.getPendingInvitations().contains(uuid)) {
                invitations.add(new TeamGuiSnapshot.PendingEntry(
                        team.getTeamId(), team.getName(), team.getOwnerUuid(),
                        nameOf(server, team.getOwnerUuid())));
            }
        }

        final List<TeamGuiSnapshot.ShareEntry> shares = new ArrayList<>();
        final List<String> myObjectIds = new ArrayList<>();
        for (var entry : OwnershipData.getInstance().getAll().entrySet()) {
            if (!uuid.equals(entry.getValue())) {
                continue;
            }
            myObjectIds.add(entry.getKey());
            for (String teamId : ShareData.getInstance().getTeamsOfObject(entry.getKey())) {
                final Team team = TeamData.getInstance().getTeam(teamId);
                shares.add(new TeamGuiSnapshot.ShareEntry(
                        entry.getKey(), teamId, team != null ? team.getName() : teamId));
            }
        }
        myObjectIds.sort(String::compareTo);

        return new TeamGuiSnapshot(myTeams, applications, invitations, shares, myObjectIds,
                onlinePlayers(server));
    }

    /** 称号 GUI 全量快照（目标为空时回退到操作者自己）。 */
    public static TitleGuiSnapshot buildTitleSnapshot(MinecraftServer server, ServerPlayerEntity player,
                                                      String targetUuid) {
        final String target = targetUuid == null || targetUuid.isEmpty()
                ? player.getUuidAsString()
                : targetUuid;
        return new TitleGuiSnapshot(
                target,
                nameOf(server, target),
                TitleData.getInstance().getTitle(target),
                TitleData.getInstance().getColor(target),
                onlinePlayers(server));
    }

    private static List<PlayerEntry> onlinePlayers(MinecraftServer server) {
        final List<PlayerEntry> players = new ArrayList<>();
        for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
            players.add(new PlayerEntry(online.getUuidAsString(), online.getName().getString()));
        }
        players.sort(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerEntry::uuid));
        return players;
    }

    private static String nameOf(MinecraftServer server, String uuidStr) {
        if (uuidStr == null || uuidStr.isEmpty()) {
            return "?";
        }
        try {
            final ServerPlayerEntity online = server.getPlayerManager().getPlayer(UUID.fromString(uuidStr));
            if (online != null) {
                return online.getName().getString();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return uuidStr.length() >= 8 ? uuidStr.substring(0, 8) : uuidStr;
    }

    // =====================================================================
    // 发包
    // =====================================================================

    private static void sendTeamSync(ServerPlayerEntity player) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        final TeamGuiSnapshot snapshot = buildTeamSnapshot(server, player);
        send(player, GuiChannels.TEAM_SYNC, buf -> GuiActionCodec.writeTeamGuiSnapshot(buf, snapshot));
    }

    private static void sendTitleSync(ServerPlayerEntity player, String targetUuid) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        final TitleGuiSnapshot snapshot = buildTitleSnapshot(server, player, targetUuid);
        send(player, GuiChannels.TITLE_SYNC, buf -> GuiActionCodec.writeTitleGuiSnapshot(buf, snapshot));
    }

    private static void sendResult(ServerPlayerEntity player, boolean ok, ResultCode code) {
        send(player, GuiChannels.ACTION_RESULT,
                buf -> GuiActionCodec.writeGuiResult(buf, new GuiResult(GuiProtocol.VERSION, ok, code)));
    }

    private static void send(ServerPlayerEntity player, net.minecraft.util.Identifier channel,
                             Consumer<PacketByteBuf> writer) {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        writer.accept(buf);
        ServerPlayNetworking.send(player, channel, buf);
    }
}
