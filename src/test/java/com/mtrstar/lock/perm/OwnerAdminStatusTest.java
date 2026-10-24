package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线路 owner 的 OP3+ 状态解析测试。
 *
 * <p>在线 / 离线查询、在线优先、OP2 拒绝与异常降级均为纯 JVM 逻辑；Minecraft 侧的
 * UserCache / OperatorList 适配在 {@code DataChildParentMixin#isAdminOwner}。</p>
 */
class OwnerAdminStatusTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Test
    @DisplayName("OP3+ owner 在线：直接返回在线权限，不查离线表")
    void onlineAdminUsesOnlinePermission() {
        final AtomicBoolean offlineCalled = new AtomicBoolean();

        assertTrue(OwnerAdminStatus.isAdmin(
                OWNER,
                id -> Optional.of(true),
                id -> {
                    offlineCalled.set(true);
                    return false;
                }));
        assertFalse(offlineCalled.get(), "在线结果存在时不应回退离线查询");
    }

    @Test
    @DisplayName("OP3+ owner 离线：在线查不到时走 OperatorList 路径")
    void offlineAdminUsesOfflinePermission() {
        assertTrue(OwnerAdminStatus.isAdmin(
                OWNER,
                id -> Optional.empty(),
                id -> true));
    }

    @Test
    @DisplayName("非 OP owner 在线：在线返回 false，不回退离线表")
    void onlineNonAdminDoesNotFallbackToOffline() {
        final AtomicBoolean offlineCalled = new AtomicBoolean();

        assertFalse(OwnerAdminStatus.isAdmin(
                OWNER,
                id -> Optional.of(false),
                id -> {
                    offlineCalled.set(true);
                    return true;
                }));
        assertFalse(offlineCalled.get(), "在线玩家已明确非 OP 时不能回退离线表");
    }

    @Test
    @DisplayName("非 OP owner 离线：离线查询 false → 保持非 OP")
    void offlineNonAdminStaysNonAdmin() {
        assertFalse(OwnerAdminStatus.isAdmin(
                OWNER,
                id -> Optional.empty(),
                id -> false));
    }

    @Test
    @DisplayName("OP2 owner：在线 / 离线都低于 ADMIN_PERMISSION_LEVEL → 拒绝")
    void op2IsNotAdmin() {
        assertFalse(OwnerAdminStatus.isAdminLevel(2));
        assertFalse(OwnerAdminStatus.isAdmin(OWNER, id -> Optional.of(false), id -> false));
        assertFalse(OwnerAdminStatus.isAdmin(OWNER, id -> Optional.empty(), id -> false));
        assertTrue(OwnerAdminStatus.isAdminLevel(PermissionChecker.ADMIN_PERMISSION_LEVEL),
                "OP3 是 mtrlock 管理员阈值");
    }

    @Test
    @DisplayName("在线 / 离线查询异常：降级为非 OP，不抛出")
    void lookupFailuresDegradeToNonAdmin() {
        assertFalse(OwnerAdminStatus.isAdmin(
                OWNER,
                id -> {
                    throw new IllegalStateException("player manager unavailable");
                },
                id -> true));

        assertFalse(OwnerAdminStatus.isAdmin(
                OWNER,
                id -> Optional.empty(),
                id -> {
                    throw new IllegalStateException("user cache / operator list unavailable");
                }));
    }

    @Test
    @DisplayName("owner UUID 为 null：直接非 OP")
    void nullOwnerIsNotAdmin() {
        assertFalse(OwnerAdminStatus.isAdmin(null, id -> Optional.of(true), id -> true));
    }
}
