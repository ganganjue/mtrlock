package com.mtrstar.lock.client;

import com.mtrstar.lock.client.gui.ClientGuiState;
import com.mtrstar.lock.client.gui.GuiRefreshable;
import com.mtrstar.lock.client.gui.TeamGuiScreen;
import com.mtrstar.lock.client.gui.TitleGuiScreen;
import com.mtrstar.lock.gui.GuiType;
import com.mtrstar.lock.network.GuiActionCodec;
import com.mtrstar.lock.network.GuiChannels;
import com.mtrstar.lock.network.GuiProtocol;
import com.mtrstar.lock.network.OwnershipSync;
import com.mtrstar.lock.network.payload.GuiResult;
import com.mtrstar.lock.network.payload.OpenGuiS2C;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;
import com.mtrstar.lock.team.ResultCode;
import com.mtrstar.lock.team.ResultMessages;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 客户端入口：归属 / 团队 / 称号 S2C 接收 + GUI Screen 打开（1.2.3）。
 *
 * <p>所有回调只做展示：把服务端快照写进本地缓存并刷新当前 Screen；
 * 打开 GUI 前先校验协议版本，版本不匹配直接提示、不打开。</p>
 */
public class MtrlockClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OwnershipSync.CHANNEL,
                (client, handler, buf, responseSender) -> {
                    final OwnershipSync.Snapshot snapshot = OwnershipSync.read(buf);

                    final Map<String, Set<String>> teamMembers = new HashMap<>();
                    final Map<String, String> teamNames = new HashMap<>();
                    for (var e : snapshot.teams().entrySet()) {
                        teamMembers.put(e.getKey(), e.getValue().members());
                        teamNames.put(e.getKey(), e.getValue().name());
                    }

                    client.execute(() -> {
                        ClientOwnership.setAll(snapshot.ownership());
                        ClientOwnership.setOperator(snapshot.operator());
                        ClientOwnership.setShareSnapshot(snapshot.shares(), teamMembers);
                        ClientOwnership.setTeamNames(teamNames);
                        ClientOwnership.setTitles(snapshot.titles());
                        // 1.2.4：可选称号颜色尾段（旧服务端没有 → 空表，等价于无色）
                        ClientOwnership.setTitleColors(snapshot.titleColors());
                    });
                });

        // S2C：打开 GUI（带协议版本）
        ClientPlayNetworking.registerGlobalReceiver(GuiChannels.OPEN_GUI,
                (client, handler, buf, responseSender) -> {
                    final OpenGuiS2C open = GuiActionCodec.readOpenGui(buf);
                    client.execute(() -> {
                        if (!GuiProtocol.isCompatible(open.protocolVersion())) {
                            warn(client, ResultCode.PROTOCOL_MISMATCH);
                            return;
                        }
                        if (open.gui() == GuiType.TITLE) {
                            client.setScreen(new TitleGuiScreen());
                        } else {
                            client.setScreen(new TeamGuiScreen());
                        }
                    });
                });

        // S2C：团队 / 称号全量快照 + 操作结果
        ClientPlayNetworking.registerGlobalReceiver(GuiChannels.TEAM_SYNC,
                (client, handler, buf, responseSender) -> {
                    final TeamGuiSnapshot snapshot = GuiActionCodec.readTeamGuiSnapshot(buf);
                    client.execute(() -> {
                        ClientGuiState.setTeamSnapshot(snapshot);
                        refresh(client);
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(GuiChannels.TITLE_SYNC,
                (client, handler, buf, responseSender) -> {
                    final TitleGuiSnapshot snapshot = GuiActionCodec.readTitleGuiSnapshot(buf);
                    client.execute(() -> {
                        ClientGuiState.setTitleSnapshot(snapshot);
                        refresh(client);
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(GuiChannels.ACTION_RESULT,
                (client, handler, buf, responseSender) -> {
                    final GuiResult result = GuiActionCodec.readGuiResult(buf);
                    client.execute(() -> {
                        ClientGuiState.setLastResult(result);
                        refresh(client);
                    });
                });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientOwnership.clear();
            ClientGuiState.clear();
        });
    }

    private static void refresh(MinecraftClient client) {
        if (client.currentScreen instanceof GuiRefreshable refreshable) {
            refreshable.onGuiDataChanged();
        }
    }

    private static void warn(MinecraftClient client, ResultCode code) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable(ResultMessages.langKey(code)).formatted(Formatting.RED), false);
        }
    }
}
