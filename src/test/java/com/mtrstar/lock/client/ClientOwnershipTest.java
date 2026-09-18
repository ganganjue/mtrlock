package com.mtrstar.lock.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientOwnership#canEditOrUnknown(String, String)} 的 fail-open 语义单元测试。
 *
 * <p>纯 JVM：ClientOwnership 只持有静态缓存，不触碰 FabricLoader。</p>
 */
class ClientOwnershipTest {

    private static final String OBJECT_ID = "route:0B0829457F350DE9";
    private static final String CREATOR = "creator-1111";
    private static final String MEMBER = "member-2222";
    private static final String OUTSIDER = "outsider-3333";
    private static final String TEAM_A = "team-A";

    @AfterEach
    void tearDown() {
        ClientOwnership.clear();
    }

    private void setupOwnership() {
        ClientOwnership.setAll(Map.of(OBJECT_ID, CREATOR));
        ClientOwnership.setOperator(false);
    }

    @Test
    @DisplayName("OP 3+ → true")
    void operatorAllowed() {
        ClientOwnership.setOperator(true);
        assertTrue(ClientOwnership.canEditOrUnknown(OBJECT_ID, OUTSIDER));
    }

    @Test
    @DisplayName("创建者本人 → true")
    void creatorAllowed() {
        setupOwnership();
        assertTrue(ClientOwnership.canEditOrUnknown(OBJECT_ID, CREATOR));
    }

    @Test
    @DisplayName("非创建者 + 分享信息未同步 → fail-open true（避免误拦团队成员）")
    void unsyncedFailsOpen() {
        setupOwnership();
        assertFalse(ClientOwnership.isShareInfoSynced());
        assertTrue(ClientOwnership.canEditOrUnknown(OBJECT_ID, OUTSIDER));
    }

    @Test
    @DisplayName("非创建者 + 已同步 + 是分享团队成员 → true")
    void syncedTeamMemberAllowed() {
        setupOwnership();
        ClientOwnership.setShareSnapshot(
                Map.of(OBJECT_ID, Set.of(TEAM_A)),
                Map.of(TEAM_A, Set.of(MEMBER)));
        assertTrue(ClientOwnership.isShareInfoSynced());
        assertTrue(ClientOwnership.canEditOrUnknown(OBJECT_ID, MEMBER));
    }

    @Test
    @DisplayName("非创建者 + 已同步 + 不是分享团队成员 → false")
    void syncedNonMemberDenied() {
        setupOwnership();
        ClientOwnership.setShareSnapshot(
                Map.of(OBJECT_ID, Set.of(TEAM_A)),
                Map.of(TEAM_A, Set.of(MEMBER)));
        assertFalse(ClientOwnership.canEditOrUnknown(OBJECT_ID, OUTSIDER));
    }

    @Test
    @DisplayName("非创建者 + 已同步 + 对象未分享 → false")
    void syncedUnsharedDenied() {
        setupOwnership();
        ClientOwnership.setShareSnapshot(Map.of(), Map.of(TEAM_A, Set.of(OUTSIDER)));
        assertFalse(ClientOwnership.canEditOrUnknown(OBJECT_ID, OUTSIDER));
    }

    @Test
    @DisplayName("null / 空 objectId / playerUuid → false")
    void nullSafe() {
        setupOwnership();
        assertFalse(ClientOwnership.canEditOrUnknown(null, MEMBER));
        assertFalse(ClientOwnership.canEditOrUnknown("", MEMBER));
        assertFalse(ClientOwnership.canEditOrUnknown(OBJECT_ID, null));
        assertFalse(ClientOwnership.canEditOrUnknown(OBJECT_ID, ""));
    }

    @Test
    @DisplayName("clear：清掉归属 / 分享 / operator / synced 标志")
    void clearResets() {
        setupOwnership();
        ClientOwnership.setOperator(true);
        ClientOwnership.setShareSnapshot(Map.of(OBJECT_ID, Set.of(TEAM_A)), Map.of(TEAM_A, Set.of(MEMBER)));

        ClientOwnership.clear();

        assertFalse(ClientOwnership.isOperator());
        assertFalse(ClientOwnership.isShareInfoSynced());
        assertFalse(ClientOwnership.hasCreator(OBJECT_ID));
        // 清空后回到 fail-open（非 OP、非创建者）
        assertTrue(ClientOwnership.canEditOrUnknown(OBJECT_ID, OUTSIDER));
    }
}
