package com.mtrstar.lock;

import com.mtrstar.lock.network.OwnershipSync;
import com.mtrstar.lock.perm.OwnershipData;

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
// This code runs as soon as Minecraft is in a mod-load-ready state.
// However, some things (like resources) may still be uninitialized.
// Proceed with mild caution.

// 归属数据（线路 / 车站 / 车厂的创建者）持久化：
//   服务端启动完成后从 config/mtrperm/ownership.json 读取，
//   服务端关闭前写回。
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
OwnershipData.getInstance().load();
// 功能 6：保存 server 引用，供 setCreator/removeCreator 后的 S2C 全量推送使用
OwnershipSync.setServer(server);
});
ServerLifecycleEvents.SERVER_STOPPING.register(server -> OwnershipData.getInstance().save());
ServerLifecycleEvents.SERVER_STOPPED.register(server -> OwnershipSync.clearServer());

// 功能 6：玩家进服时把当前归属快照推给他（含“是否 OP 3+”）
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
OwnershipSync.pushTo(handler.getPlayer()));

// 兜底：即使服务端不是正常关闭（崩溃 / kill），进程退出时也尽量落盘一次。
OwnershipData ownership = OwnershipData.getInstance();
Runtime.getRuntime().addShutdownHook(new Thread(ownership::save, "mtrlock-ownership-save"));
}

public static Identifier id(String path) {
return new Identifier(MOD_ID, path);
}
}
