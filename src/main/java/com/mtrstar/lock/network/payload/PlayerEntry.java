package com.mtrstar.lock.network.payload;

import java.util.Objects;

/**
 * 在线玩家条目（UUID + 显示名），GUI 快照用（1.2.3）。
 *
 * <p>纯数据 record：客户端与服务端共用，不含 Minecraft 类型，可纯 JVM 单测。</p>
 *
 * @param uuid 玩家 UUID 字符串
 * @param name 玩家当前显示名
 */
public record PlayerEntry(String uuid, String name) {

    public PlayerEntry {
        Objects.requireNonNull(uuid, "uuid");
        name = name == null ? "" : name;
    }
}
