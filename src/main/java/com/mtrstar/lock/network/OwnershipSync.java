package com.mtrstar.lock.network;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamData;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class OwnershipSync {

    public static final Identifier CHANNEL = new Identifier(Mtrlock.MOD_ID, "sync_ownership");

    private static volatile MinecraftServer server;

    private OwnershipSync() {
    }

    public static void setServer(MinecraftServer v) { server = v; }
    public static void clearServer() { server = null; }

    public static void pushToAll() {
        final MinecraftServer current = server;
        if (current == null) return;
        current.execute(() -> {
            final Snapshot snap = buildSnapshot();
            for (ServerPlayerEntity player : current.getPlayerManager().getPlayerList()) {
                send(player, snap);
            }
        });
    }

    public static void pushTo(ServerPlayerEntity player) {
        if (player == null) return;
        final MinecraftServer current = server;
        if (current == null) return;
        current.execute(() -> send(player, buildSnapshot()));
    }

    private static Snapshot buildSnapshot() {
        final Map<String, String> ownership = OwnershipData.getInstance().getAll();

        final Map<String, TeamSnapshot> teams = new HashMap<>();
        for (Team t : TeamData.getInstance().getAllTeams()) {
            teams.put(t.getTeamId(), new TeamSnapshot(
                    t.getName(), t.getOwnerUuid(), new LinkedHashSet<>(t.getMembers())));
        }

        final Map<String, Set<String>> shares = ShareData.getInstance().getAllShares();

        return new Snapshot(ownership, teams, shares, false); // operator 占位；实际值在 send() 逐玩家写入
    }

    private static void send(ServerPlayerEntity player, Snapshot snap) {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeBoolean(player.hasPermissionLevel(3));
        writeBody(buf, snap);
        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    private static void writeBody(PacketByteBuf buf, Snapshot s) {
        buf.writeVarInt(s.ownership().size());
        for (Map.Entry<String, String> e : s.ownership().entrySet()) {
            buf.writeString(e.getKey());
            buf.writeString(e.getValue());
        }
        buf.writeVarInt(s.teams().size());
        for (Map.Entry<String, TeamSnapshot> e : s.teams().entrySet()) {
            buf.writeString(e.getKey());
            buf.writeString(e.getValue().name());
            buf.writeString(e.getValue().ownerUuid());
            buf.writeVarInt(e.getValue().members().size());
            for (String m : e.getValue().members()) buf.writeString(m);
        }
        buf.writeVarInt(s.shares().size());
        for (Map.Entry<String, Set<String>> e : s.shares().entrySet()) {
            buf.writeString(e.getKey());
            buf.writeVarInt(e.getValue().size());
            for (String t : e.getValue()) buf.writeString(t);
        }
    }

    public static Snapshot read(PacketByteBuf buf) {
        final boolean operator = buf.readBoolean();

        final int on = buf.readVarInt();
        final Map<String, String> ownership = new HashMap<>(Math.max(4, on));
        for (int i = 0; i < on; i++) ownership.put(buf.readString(), buf.readString());

        final int tn = buf.readVarInt();
        final Map<String, TeamSnapshot> teams = new HashMap<>(Math.max(4, tn));
        for (int i = 0; i < tn; i++) {
            final String id = buf.readString();
            final String name = buf.readString();
            final String owner = buf.readString();
            final int mn = buf.readVarInt();
            final Set<String> members = new LinkedHashSet<>();
            for (int j = 0; j < mn; j++) members.add(buf.readString());
            teams.put(id, new TeamSnapshot(name, owner, members));
        }

        final int sn = buf.readVarInt();
        final Map<String, Set<String>> shares = new HashMap<>(Math.max(4, sn));
        for (int i = 0; i < sn; i++) {
            final String oid = buf.readString();
            final int cn = buf.readVarInt();
            final Set<String> tids = new LinkedHashSet<>();
            for (int j = 0; j < cn; j++) tids.add(buf.readString());
            shares.put(oid, tids);
        }

        return new Snapshot(ownership, teams, shares, operator);
    }

    public record TeamSnapshot(String name, String ownerUuid, Set<String> members) {
    }

    public record Snapshot(Map<String, String> ownership,
                           Map<String, TeamSnapshot> teams,
                           Map<String, Set<String>> shares,
                           boolean operator) {
    }
}
