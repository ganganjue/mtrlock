package com.mtrstar.lock.client;

import com.mtrstar.lock.network.OwnershipSync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class MtrlockClient implements ClientModInitializer {

@Override
public void onInitializeClient() {
// 功能 6：接收服务端的归属快照（mtrlock:sync_ownership）
ClientPlayNetworking.registerGlobalReceiver(OwnershipSync.CHANNEL, (client, handler, buf, responseSender) -> {
final OwnershipSync.Snapshot snapshot = OwnershipSync.read(buf);
ClientOwnership.setAll(snapshot.ownership());
ClientOwnership.setOperator(snapshot.operator());
});

// 功能 6：断开连接时清空本地缓存
ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientOwnership.clear());
}
}
