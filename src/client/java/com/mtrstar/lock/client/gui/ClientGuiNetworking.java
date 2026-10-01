package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.network.GuiActionCodec;
import com.mtrstar.lock.network.GuiChannels;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TitleGuiAction;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/**
 * 客户端 GUI 发包（1.2.3）。
 *
 * <p>只负责把界面点击变成一次 C2S 包；发完不做任何本地状态修改，
 * 等服务端 {@code GuiResult} + 全量快照回来再刷新。</p>
 */
public final class ClientGuiNetworking {

    private ClientGuiNetworking() {
    }

    public static void sendTeamAction(TeamGuiAction action) {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        GuiActionCodec.writeTeamGuiAction(buf, action);
        ClientPlayNetworking.send(GuiChannels.TEAM_ACTION, buf);
    }

    public static void sendTitleAction(TitleGuiAction action) {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        GuiActionCodec.writeTitleGuiAction(buf, action);
        ClientPlayNetworking.send(GuiChannels.TITLE_ACTION, buf);
    }
}
