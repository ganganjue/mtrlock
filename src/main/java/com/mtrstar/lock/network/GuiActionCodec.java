package com.mtrstar.lock.network;

import com.mtrstar.lock.gui.GuiType;
import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.gui.TitleActionType;
import com.mtrstar.lock.network.payload.GuiResult;
import com.mtrstar.lock.network.payload.OpenGuiS2C;
import com.mtrstar.lock.network.payload.PlayerEntry;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import com.mtrstar.lock.network.payload.TitleGuiAction;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;
import com.mtrstar.lock.team.ResultCode;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI 包的读写（1.2.3）。
 *
 * <p>所有字符串都用“可空”编码（1 字节存在位 + 内容），避免任何一个字段为 null 时
 * 把整包写崩；枚举按名字传输，未知名字回退为 {@code null}，由上层判 UNKNOWN_ACTION。</p>
 *
 * <p>协议版本写在每个包的第一个字段（C2S / S2C 都是），两侧各自校验。</p>
 */
public final class GuiActionCodec {

    private GuiActionCodec() {
    }

    // =====================================================================
    // C2S：TeamGuiAction
    // =====================================================================

    public static void writeTeamGuiAction(PacketByteBuf buf, TeamGuiAction action) {
        buf.writeVarInt(action.protocolVersion());
        writeNullableString(buf, action.type() == null ? null : action.type().name());
        writeNullableString(buf, action.teamId());
        writeNullableString(buf, action.teamName());
        writeNullableString(buf, action.targetUuid());
        writeNullableString(buf, action.targetName());
        writeNullableString(buf, action.objectId());
    }

    public static TeamGuiAction readTeamGuiAction(PacketByteBuf buf) {
        final int version = buf.readVarInt();
        final TeamActionType type = parseTeamActionType(readNullableString(buf));
        return new TeamGuiAction(version, type,
                readNullableString(buf), readNullableString(buf),
                readNullableString(buf), readNullableString(buf),
                readNullableString(buf));
    }

    // =====================================================================
    // C2S：TitleGuiAction
    // =====================================================================

    public static void writeTitleGuiAction(PacketByteBuf buf, TitleGuiAction action) {
        buf.writeVarInt(action.protocolVersion());
        writeNullableString(buf, action.type() == null ? null : action.type().name());
        writeNullableString(buf, action.targetUuid());
        writeNullableString(buf, action.title());
    }

    public static TitleGuiAction readTitleGuiAction(PacketByteBuf buf) {
        final int version = buf.readVarInt();
        final TitleActionType type = parseTitleActionType(readNullableString(buf));
        return new TitleGuiAction(version, type, readNullableString(buf), readNullableString(buf));
    }

    // =====================================================================
    // S2C：OpenGui
    // =====================================================================

    public static void writeOpenGui(PacketByteBuf buf, OpenGuiS2C open) {
        buf.writeVarInt(open.protocolVersion());
        buf.writeVarInt(open.gui() == null ? -1 : open.gui().ordinal());
    }

    public static OpenGuiS2C readOpenGui(PacketByteBuf buf) {
        final int version = buf.readVarInt();
        final int ordinal = buf.readVarInt();
        final GuiType[] values = GuiType.values();
        final GuiType type = ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
        return new OpenGuiS2C(version, type);
    }

    // =====================================================================
    // S2C：GuiResult
    // =====================================================================

    public static void writeGuiResult(PacketByteBuf buf, GuiResult result) {
        buf.writeVarInt(result.protocolVersion());
        buf.writeBoolean(result.ok());
        writeNullableString(buf, result.code().name());
    }

    public static GuiResult readGuiResult(PacketByteBuf buf) {
        final int version = buf.readVarInt();
        final boolean ok = buf.readBoolean();
        final ResultCode code = parseResultCode(readNullableString(buf));
        return new GuiResult(version, ok, code);
    }

    // =====================================================================
    // S2C：TeamGuiSnapshot
    // =====================================================================

    public static void writeTeamGuiSnapshot(PacketByteBuf buf, TeamGuiSnapshot snapshot) {
        buf.writeVarInt(snapshot.myTeams().size());
        for (TeamGuiSnapshot.TeamEntry entry : snapshot.myTeams()) {
            writeNullableString(buf, entry.teamId());
            writeNullableString(buf, entry.name());
            writeNullableString(buf, entry.ownerUuid());
            writeNullableString(buf, entry.ownerName());
            buf.writeVarInt(Math.max(0, entry.memberCount()));
            buf.writeVarInt(Math.max(0, entry.shareCount()));
            buf.writeBoolean(entry.owner());
            writePlayers(buf, entry.members());
        }

        writePending(buf, snapshot.applications());
        writePending(buf, snapshot.invitations());

        buf.writeVarInt(snapshot.shares().size());
        for (TeamGuiSnapshot.ShareEntry entry : snapshot.shares()) {
            writeNullableString(buf, entry.objectId());
            writeNullableString(buf, entry.teamId());
            writeNullableString(buf, entry.teamName());
        }

        buf.writeVarInt(snapshot.myObjectIds().size());
        for (String objectId : snapshot.myObjectIds()) {
            writeNullableString(buf, objectId);
        }

        writePlayers(buf, snapshot.onlinePlayers());
    }

    public static TeamGuiSnapshot readTeamGuiSnapshot(PacketByteBuf buf) {
        final int teamCount = buf.readVarInt();
        final List<TeamGuiSnapshot.TeamEntry> myTeams = new ArrayList<>(Math.max(0, teamCount));
        for (int i = 0; i < teamCount; i++) {
            final String teamId = readNullableString(buf);
            final String name = readNullableString(buf);
            final String ownerUuid = readNullableString(buf);
            final String ownerName = readNullableString(buf);
            final int memberCount = buf.readVarInt();
            final int shareCount = buf.readVarInt();
            final boolean owner = buf.readBoolean();
            final List<PlayerEntry> members = readPlayers(buf);
            myTeams.add(new TeamGuiSnapshot.TeamEntry(
                    teamId, name, ownerUuid, ownerName, memberCount, shareCount, owner, members));
        }

        final List<TeamGuiSnapshot.PendingEntry> applications = readPending(buf);
        final List<TeamGuiSnapshot.PendingEntry> invitations = readPending(buf);

        final int shareCount = buf.readVarInt();
        final List<TeamGuiSnapshot.ShareEntry> shares = new ArrayList<>(Math.max(0, shareCount));
        for (int i = 0; i < shareCount; i++) {
            shares.add(new TeamGuiSnapshot.ShareEntry(
                    readNullableString(buf), readNullableString(buf), readNullableString(buf)));
        }

        final int objectCount = buf.readVarInt();
        final List<String> myObjectIds = new ArrayList<>(Math.max(0, objectCount));
        for (int i = 0; i < objectCount; i++) {
            myObjectIds.add(readNullableString(buf));
        }

        final List<PlayerEntry> onlinePlayers = readPlayers(buf);

        return new TeamGuiSnapshot(myTeams, applications, invitations, shares, myObjectIds, onlinePlayers);
    }

    // =====================================================================
    // S2C：TitleGuiSnapshot
    // =====================================================================

    public static void writeTitleGuiSnapshot(PacketByteBuf buf, TitleGuiSnapshot snapshot) {
        writeNullableString(buf, snapshot.targetUuid());
        writeNullableString(buf, snapshot.targetName());
        writeNullableString(buf, snapshot.currentTitle());
        writePlayers(buf, snapshot.onlinePlayers());
    }

    public static TitleGuiSnapshot readTitleGuiSnapshot(PacketByteBuf buf) {
        final String targetUuid = readNullableString(buf);
        final String targetName = readNullableString(buf);
        final String currentTitle = readNullableString(buf);
        return new TitleGuiSnapshot(targetUuid, targetName, currentTitle, readPlayers(buf));
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private static void writePending(PacketByteBuf buf, List<TeamGuiSnapshot.PendingEntry> entries) {
        buf.writeVarInt(entries.size());
        for (TeamGuiSnapshot.PendingEntry entry : entries) {
            writeNullableString(buf, entry.teamId());
            writeNullableString(buf, entry.teamName());
            writeNullableString(buf, entry.playerUuid());
            writeNullableString(buf, entry.playerName());
        }
    }

    private static List<TeamGuiSnapshot.PendingEntry> readPending(PacketByteBuf buf) {
        final int count = buf.readVarInt();
        final List<TeamGuiSnapshot.PendingEntry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            entries.add(new TeamGuiSnapshot.PendingEntry(
                    readNullableString(buf), readNullableString(buf),
                    readNullableString(buf), readNullableString(buf)));
        }
        return entries;
    }

    private static void writePlayers(PacketByteBuf buf, List<PlayerEntry> players) {
        buf.writeVarInt(players.size());
        for (PlayerEntry player : players) {
            writeNullableString(buf, player.uuid());
            writeNullableString(buf, player.name());
        }
    }

    private static List<PlayerEntry> readPlayers(PacketByteBuf buf) {
        final int count = buf.readVarInt();
        final List<PlayerEntry> players = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final String uuid = readNullableString(buf);
            final String name = readNullableString(buf);
            if (uuid != null) {
                players.add(new PlayerEntry(uuid, name));
            }
        }
        return players;
    }

    private static void writeNullableString(PacketByteBuf buf, String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeString(value);
        }
    }

    private static String readNullableString(PacketByteBuf buf) {
        return buf.readBoolean() ? buf.readString() : null;
    }

    private static TeamActionType parseTeamActionType(String name) {
        if (name == null) return null;
        try {
            return TeamActionType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static TitleActionType parseTitleActionType(String name) {
        if (name == null) return null;
        try {
            return TitleActionType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResultCode parseResultCode(String name) {
        if (name == null) return ResultCode.UNKNOWN_ACTION;
        try {
            return ResultCode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ResultCode.UNKNOWN_ACTION;
        }
    }
}
