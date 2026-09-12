package com.mtrstar.lock.network;

import com.mtrstar.lock.Mtrlock;
import com.mtrstar.lock.perm.OwnershipData;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 功能 6：归属数据 S2C 同步（服务端侧）。
 *
 * <p>协议（{@code mtrlock:sync_ownership}，payload = PacketByteBuf）：</p>
 * <pre>
 *   boolean operator              // 收包玩家是不是 OP 3+（按收包玩家逐个发）
 *   VarInt  count
 *   repeat count 次：
 *     String  objectId            // "route:<hex>" / "station:<hex>" / "depot:<hex>"
 *     String  playerUuid
 * </pre>
 *
 * <p>只做“全量快照”推送，不做增量。推送时机：</p>
 * <ul>
 *   <li>玩家进服（{@code ServerPlayConnectionEvents.JOIN}，只发给该玩家）；</li>
 *   <li>{@code OwnershipData.setCreator} / {@code removeCreator} 之后（方法内部直接调用 {@link #pushToAll()}，发给所有在线玩家）。</li>
 * </ul>
 *
 * <p>客户端只把它当作“缓存”，最终权威仍是服务端拦截（功能 5）。</p>
 */
public final class OwnershipSync {

    /** 同步频道 id。 */
    public static final Identifier CHANNEL = new Identifier(Mtrlock.MOD_ID, "sync_ownership");

    /** 当前服务端引用（由 Mtrlock 在 SERVER_STARTED / SERVER_STOPPED 维护）。 */
    private static volatile MinecraftServer server;

    private OwnershipSync() {
    }

    public static void setServer(MinecraftServer value) {
        server = value;
    }

    public static void clearServer() {
        server = null;
    }

    /** 全量推送给所有在线玩家（每人带自己的 operator 标志）。必须在服务端线程执行。 */
    public static void pushToAll() {
        final MinecraftServer current = server;
        if (current == null) {
            return;
        }
        current.execute(() -> {
            final Map<String, String> snapshot = OwnershipData.getInstance().getAll();
            for (ServerPlayerEntity player : current.getPlayerManager().getPlayerList()) {
                send(player, snapshot, player.hasPermissionLevel(3));
            }
        });
    }

    /** 全量推送给单个玩家（进服时用）。 */
    public static void pushTo(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        send(player, OwnershipData.getInstance().getAll(), player.hasPermissionLevel(3));
    }

    private static void send(ServerPlayerEntity player, Map<String, String> ownership, boolean operator) {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        write(buf, ownership, operator);
        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    // ------------------------------------------------------------------
    // 编解码（服务端 write、客户端 read 共用）
    // ------------------------------------------------------------------

    public static void write(PacketByteBuf buf, Map<String, String> ownership, boolean operator) {
        buf.writeBoolean(operator);
        buf.writeVarInt(ownership.size());
        for (Map.Entry<String, String> entry : ownership.entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeString(entry.getValue());
        }
    }

    public static Snapshot read(PacketByteBuf buf) {
        final boolean operator = buf.readBoolean();
        final int size = buf.readVarInt();
        final Map<String, String> ownership = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ownership.put(buf.readString(), buf.readString());
        }
        return new Snapshot(ownership, operator);
    }

    /** 一次同步的快照：全量归属 + “收包玩家是否 OP 3+”。 */
    public record Snapshot(Map<String, String> ownership, boolean operator) {
    }
}
