package com.mtrstar.lock.perm;

import java.util.Optional;
import java.util.UUID;

/**
 * 线路 owner 的 OP3+ 状态解析（纯逻辑，便于单测）。
 *
 * <p>在线 owner 直接用实时玩家权限；离线 owner 才查 OperatorList。查询异常一律按
 * 非 OP 降级，不能中断 {@code Data.sync()}。</p>
 */
public final class OwnerAdminStatus {

    /** 在线玩家查询：返回 empty 表示玩家不在线，不能回退到离线查询。 */
    @FunctionalInterface
    public interface OnlineLookup {

        Optional<Boolean> isAdmin(UUID ownerUuid);
    }

    /** 离线 OP 查询。 */
    @FunctionalInterface
    public interface OfflineLookup {

        boolean isAdmin(UUID ownerUuid);
    }

    private OwnerAdminStatus() {
    }

    /**
     * 解析 owner 的真实 OP 状态。
     *
     * <p>在线结果优先；在线且非 OP 时不回退离线表。只有在线玩家不存在、查询结果为空或
     * 查询抛异常时才按非 OP 降级。</p>
     */
    public static boolean isAdmin(UUID ownerUuid, OnlineLookup onlineLookup, OfflineLookup offlineLookup) {
        if (ownerUuid == null || onlineLookup == null) {
            return false;
        }
        try {
            final Optional<Boolean> online = onlineLookup.isAdmin(ownerUuid);
            if (online != null && online.isPresent()) {
                return online.get();
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (offlineLookup == null) {
            return false;
        }
        try {
            return offlineLookup.isAdmin(ownerUuid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 权限等级是否达到 mtrlock 的 OP3+ 阈值。 */
    public static boolean isAdminLevel(int permissionLevel) {
        return permissionLevel >= PermissionChecker.ADMIN_PERMISSION_LEVEL;
    }
}
