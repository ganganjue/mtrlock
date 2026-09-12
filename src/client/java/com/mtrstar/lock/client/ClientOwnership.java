package com.mtrstar.lock.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能 6：客户端归属缓存。
 *
 * <p>由 {@code mtrlock:sync_ownership} S2C 包填充（见 {@code MtrlockClient}），
 * 只用于“发包前的本地预判”，<b>不是权威</b>；权威判定在服务端（功能 5）。</p>
 *
 * <p>字段：</p>
 * <ul>
 *   <li>{@link #ownership}：objectId → 创建者 UUID（全量快照）；</li>
 *   <li>{@link #operator}：当前客户端玩家是否 OP 3+（服务端随包逐个下发）。</li>
 * </ul>
 */
public final class ClientOwnership {

    private static final Map<String, String> OWNERSHIP = new ConcurrentHashMap<>();

    private static volatile boolean operator;

    private ClientOwnership() {
    }

    /** 全量替换归属快照。 */
    public static void setAll(Map<String, String> snapshot) {
        OWNERSHIP.clear();
        if (snapshot != null) {
            OWNERSHIP.putAll(snapshot);
        }
    }

    public static void setOperator(boolean value) {
        operator = value;
    }

    public static boolean isOperator() {
        return operator;
    }

    public static boolean hasCreator(String objectId) {
        return objectId != null && OWNERSHIP.containsKey(objectId);
    }

    public static String getCreator(String objectId) {
        return objectId == null ? null : OWNERSHIP.get(objectId);
    }

    /**
     * 本地预判：当前玩家能否编辑 / 删除该 objectId。
     * 管理员直接放行；否则要求归属 UUID 等于自己。
     */
    public static boolean canEdit(String objectId, String playerUuid) {
        if (operator) {
            return true;
        }
        if (objectId == null || playerUuid == null) {
            return false;
        }
        final String creator = OWNERSHIP.get(objectId);
        return creator != null && creator.equals(playerUuid);
    }

    /** 退出服务器时清空缓存。 */
    public static void clear() {
        OWNERSHIP.clear();
        operator = false;
    }

    public static int size() {
        return OWNERSHIP.size();
    }
}
