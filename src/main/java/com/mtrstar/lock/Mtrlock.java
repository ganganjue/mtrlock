package com.mtrstar.lock;

import com.mtrstar.lock.compat.DisplayConfig;
import com.mtrstar.lock.compat.DisplayModDetector;
import com.mtrstar.lock.compat.MtrlockPlaceholders;
import com.mtrstar.lock.network.OwnershipSync;
import com.mtrstar.lock.network.ServerGuiNetworking;
import com.mtrstar.lock.perm.OwnershipData;
import com.mtrstar.lock.protect.ProtectionConfig;
import com.mtrstar.lock.protect.ProtectionIndex;
import com.mtrstar.lock.protect.ProtectionListener;
import com.mtrstar.lock.refs.RemovedRefsData;
import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.TeamData;
import com.mtrstar.lock.team.TitleData;
import com.mtrstar.lock.command.MtrlockCommand;
import com.mtrstar.lock.command.TeamCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mtrlock implements ModInitializer {
public static final String MOD_ID = "mtrlock";

// This logger is used to write text to the console and the log file.
// It is considered best practice to use your mod id as the logger's name.
// That way, it's clear which mod wrote info, warnings, and errors.
public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

@Override
public void onInitialize() {
// 1.2.0：显示前缀兼容层。
// 装了 StyledChat / StyledPlayerList 时，三个显示 Mixin 会自行早退（不注入），
// 改由 placeholder-api 暴露 %mtrlock:prefix% / %mtrlock:title% / %mtrlock:team%。
if (DisplayModDetector.hasConflictingDisplayMod()) {
MtrlockPlaceholders.register();
LOGGER.info("[mtrlock] 检测到 StyledChat/StyledPlayerList，显示前缀改用 Placeholder API");
} else {
LOGGER.info("[mtrlock] 显示前缀使用内置 Mixin（未检测到冲突模组）");
}

// 1.1.0 阶段 4：注册命令
// 1.1.0 阶段 5：团队 / 分享变更后推送 S2C 全量快照
TeamData.setChangeListener(OwnershipSync::pushToAll);
ShareData.setChangeListener(OwnershipSync::pushToAll);
// 1.2.0：自定义称呼变更后也推送 S2C 全量快照
TitleData.setChangeListener(OwnershipSync::pushToAll);

CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
TeamCommand.register(dispatcher);
MtrlockCommand.register(dispatcher);
});

// 1.2.3：GUI 网络。注册两个 C2S 接收器（团队 / 称号）；
// 打开入口走 /mtrlock gui，未装客户端的玩家由 canSend 判定后只提示、命令仍可用。
ServerGuiNetworking.register();

// 1.3.0：区域方块保护（车站 / 车厂矩形范围内的方块破坏与放置）。
// 纯服务端权威判定，客户端拦截行为不变；未装客户端同样生效。
ProtectionListener.register();

// This code runs as soon as Minecraft is in a mod-load-ready state.
// However, some things (like resources) may still be uninitialized.
// Proceed with mild caution.

// 归属数据（线路 / 车站 / 车厂的创建者）持久化：
//   服务端启动完成后从 config/mtrperm/ownership.json 读取，
//   服务端关闭前写回。
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
OwnershipData.getInstance().load();
// 1.1.0：团队 / 分享数据加载。顺序固定：TeamData 先加载，ShareData 再清理孤儿 teamId。
TeamData.getInstance().load();
ShareData.getInstance().load();
ShareData.getInstance().cleanupOrphanTeams();
// 1.2.0：自定义称呼加载（放在 TeamData / ShareData 之后）
TitleData.getInstance().load();
// 1.2.4：占位符显示配置（config/mtrperm/display.json）
DisplayConfig.getInstance().load();
// 1.3.0：区域方块保护配置（config/mtrperm/protection.properties）
ProtectionConfig.getInstance().load();
// 1.4.0：线路引用账本（config/mtrperm/removed_refs.json）加载 + 30 天清理。
// 加载失败（loadFailed）时，Data#sync 的对账会整体跳过，不做任何移除。
RemovedRefsData.getInstance().load();
RemovedRefsData.getInstance().cleanupExpired();
// 1.3.0：归属数据加载完成后重建一次空间索引。
// MTR 的 Simulator 构造（内部 sync()）可能早于本回调，那时 ownership.json 还没读，
// 所以这里用「记住的 Simulator」再兜底重建一次，避免重启后保护失效。
ProtectionIndex.rebuildFromRememberedServerData();
// 功能 6：保存 server 引用，供 setCreator/removeCreator 后的 S2C 全量推送使用
OwnershipSync.setServer(server);
});
ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
OwnershipData.getInstance().save();
// 1.1.0：分享 / 团队数据写回
ShareData.getInstance().save();
TeamData.getInstance().save();
// 1.2.0：自定义称呼写回
TitleData.getInstance().save();
// 1.2.4：显示配置写回
DisplayConfig.getInstance().save();
// 1.3.0：区域方块保护配置写回
ProtectionConfig.getInstance().save();
// 1.4.0：线路引用账本写回（未落盘的节流变更也必须在这里落一次）
RemovedRefsData.getInstance().flush();
});
ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
OwnershipSync.clearServer();
// 1.3.0：清掉记住的 Simulator 与空间索引（集成服务器会随存档反复启停）
ProtectionIndex.forgetServerData();
ProtectionIndex.get().clear();
});

// 功能 6：玩家进服时把当前归属快照推给他（含“是否 OP 3+”）
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
OwnershipSync.pushTo(handler.getPlayer()));

// 1.2.3：玩家退服时清掉 GUI 限流桶，避免长期运行内存增长。
ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
ServerGuiNetworking.clearRateLimit(handler.getPlayer().getUuidAsString()));

// 兜底：即使服务端不是正常关闭（崩溃 / kill），进程退出时也尽量落盘一次。
OwnershipData ownership = OwnershipData.getInstance();
TeamData teams = TeamData.getInstance();
ShareData shares = ShareData.getInstance();
TitleData titles = TitleData.getInstance();
DisplayConfig displayConfig = DisplayConfig.getInstance();
ProtectionConfig protectionConfig = ProtectionConfig.getInstance();
RemovedRefsData removedRefs = RemovedRefsData.getInstance();
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
ownership.save();
shares.save();
teams.save();
titles.save();
displayConfig.save();
protectionConfig.save();
removedRefs.flush();
}, "mtrlock-data-save"));
}

public static Identifier id(String path) {
return new Identifier(MOD_ID, path);
}
}
