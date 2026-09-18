package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShareData} 的单元测试。
 *
 * <p>用包内 3 参构造注入桩，完全避开 {@code OwnershipData} / {@code TeamData} 单例
 * （它们依赖 FabricLoader），可在纯 JVM 下运行：</p>
 * <ul>
 *   <li>{@link ShareData.CreatorLookup} 用一张内存 map 模拟“对象 → 创建者”；</li>
 *   <li>{@link ShareData.TeamExistsLookup} 用一个 Set 模拟“团队是否存在”。</li>
 * </ul>
 */
class ShareDataTest {

    private static final String OBJ_A = "route:000000000000000A";
    private static final String OBJ_B = "station:000000000000000B";
    private static final String OBJ_C = "depot:000000000000000C";

    private static final String T1 = "team-1";
    private static final String T2 = "team-2";
    private static final String T3 = "team-3";

    private static final String PLAYER_P = "player-p";
    private static final String PLAYER_Q = "player-q";

    @TempDir
    Path tempDir;

    /** objectId → 创建者（模拟 OwnershipData）。 */
    private final Map<String, String> creators = new HashMap<>();

    /** 存在的 teamId（模拟 TeamData）。 */
    private final Set<String> existingTeams = new HashSet<>();

    private Path file() {
        return tempDir.resolve("shares.json");
    }

    private ShareData data() {
        return data(file());
    }

    private ShareData data(Path file) {
        return new ShareData(file, creators::get, existingTeams::contains);
    }

    // =====================================================================
    // share / unshare
    // =====================================================================

    @Test
    @DisplayName("share：新增返回 true，重复返回 false")
    void shareAndDuplicate() {
        final ShareData data = data();

        assertTrue(data.share(OBJ_A, T1));
        assertFalse(data.share(OBJ_A, T1), "重复分享应返回 false");

        assertTrue(data.share(OBJ_A, T2), "同一对象可分享给多个团队");
        assertEquals(2, data.totalShares());
        assertEquals(1, data.size());
    }

    @Test
    @DisplayName("unshare：删除返回 true，重复删除返回 false；空条目被清理")
    void unshare() {
        final ShareData data = data();
        assertTrue(data.share(OBJ_A, T1));

        assertFalse(data.unshare(OBJ_A, T2), "没分享过应返回 false");
        assertTrue(data.unshare(OBJ_A, T1));
        assertFalse(data.unshare(OBJ_A, T1), "重复删除应返回 false");

        assertTrue(data.getTeamsOfObject(OBJ_A).isEmpty());
        assertEquals(0, data.size(), "对象没有任何分享后条目应被移除");
        assertEquals(0, data.totalShares());
    }

    @Test
    @DisplayName("参数安全：null / 空串一律 false / 空集合，不 NPE")
    void nullSafe() {
        final ShareData data = data();

        assertFalse(data.share(null, T1));
        assertFalse(data.share(OBJ_A, null));
        assertFalse(data.share("", T1));
        assertFalse(data.share(OBJ_A, ""));
        assertFalse(data.unshare(null, T1));
        assertFalse(data.unshare(OBJ_A, null));

        assertEquals(0, data.revokeAllFromPlayer(null, PLAYER_P));
        assertEquals(0, data.revokeAllFromPlayer(T1, null));
        assertEquals(0, data.revokeAllForTeam(null));

        assertTrue(data.getTeamsOfObject(null).isEmpty());
        assertTrue(data.getTeamsOfObject("nope").isEmpty());
        assertTrue(data.getObjectsSharedToTeam(null).isEmpty());
        assertTrue(data.getObjectsSharedToTeam("nope").isEmpty());
    }

    // =====================================================================
    // 查询
    // =====================================================================

    @Test
    @DisplayName("getTeamsOfObject / getObjectsSharedToTeam：正反两个方向都能查")
    void queries() {
        final ShareData data = data();
        data.share(OBJ_A, T1);
        data.share(OBJ_A, T2);
        data.share(OBJ_B, T1);

        assertEquals(Set.of(T1, T2), data.getTeamsOfObject(OBJ_A));
        assertEquals(Set.of(T1), data.getTeamsOfObject(OBJ_B));
        assertEquals(Set.of(OBJ_A, OBJ_B), data.getObjectsSharedToTeam(T1));
        assertEquals(Set.of(OBJ_A), data.getObjectsSharedToTeam(T2));
        assertTrue(data.getObjectsSharedToTeam(T3).isEmpty());
    }

    @Test
    @DisplayName("返回的是不可变快照，外部改不动内部数据")
    void returnedSetsAreImmutable() {
        final ShareData data = data();
        data.share(OBJ_A, T1);

        final Set<String> teams = data.getTeamsOfObject(OBJ_A);
        assertThrows(UnsupportedOperationException.class, () -> teams.add(T2));

        final Set<String> objects = data.getObjectsSharedToTeam(T1);
        assertThrows(UnsupportedOperationException.class, () -> objects.add(OBJ_B));

        // 内部数据未被影响
        assertEquals(Set.of(T1), data.getTeamsOfObject(OBJ_A));
    }

    // =====================================================================
    // revokeAllFromPlayer
    // =====================================================================

    @Test
    @DisplayName("revokeAllFromPlayer：只撤销该玩家作为创建者的分享")
    void revokeAllFromPlayer() {
        final ShareData data = data();
        creators.put(OBJ_A, PLAYER_P);
        creators.put(OBJ_B, PLAYER_P);
        creators.put(OBJ_C, PLAYER_Q);

        data.share(OBJ_A, T1);
        data.share(OBJ_A, T2); // P 分享给别的团队，不应被撤销
        data.share(OBJ_B, T1);
        data.share(OBJ_C, T1); // Q 分享给同一团队，不应被撤销

        assertEquals(2, data.revokeAllFromPlayer(T1, PLAYER_P));

        assertEquals(Set.of(T2), data.getTeamsOfObject(OBJ_A), "P 分享给其它团队的对象应保留");
        assertTrue(data.getTeamsOfObject(OBJ_B).isEmpty(), "P 分享给 T1 的对象应被撤销");
        assertEquals(Set.of(T1), data.getTeamsOfObject(OBJ_C), "别人分享给 T1 的对象应保留");
        assertEquals(2, data.totalShares(), "剩余：OBJ_A→T2 与 OBJ_C→T1");
    }

    @Test
    @DisplayName("revokeAllFromPlayer：没有匹配分享时返回 0，不误删")
    void revokeAllFromPlayerNoMatch() {
        final ShareData data = data();
        creators.put(OBJ_A, PLAYER_Q);
        data.share(OBJ_A, T1);

        assertEquals(0, data.revokeAllFromPlayer(T1, PLAYER_P), "创建者不匹配 → 不撤销");
        assertEquals(0, data.revokeAllFromPlayer(T2, PLAYER_Q), "团队不匹配 → 不撤销");
        assertEquals(Set.of(T1), data.getTeamsOfObject(OBJ_A));
    }

    // =====================================================================
    // revokeAllForTeam
    // =====================================================================

    @Test
    @DisplayName("revokeAllForTeam：清掉该团队的所有分享，不影响其他团队")
    void revokeAllForTeam() {
        final ShareData data = data();
        data.share(OBJ_A, T1);
        data.share(OBJ_A, T2);
        data.share(OBJ_B, T1);
        data.share(OBJ_C, T2);

        assertEquals(2, data.revokeAllForTeam(T1));

        assertEquals(Set.of(T2), data.getTeamsOfObject(OBJ_A));
        assertTrue(data.getTeamsOfObject(OBJ_B).isEmpty());
        assertEquals(Set.of(T2), data.getTeamsOfObject(OBJ_C));
        assertEquals(0, data.revokeAllForTeam(T1), "重复撤销返回 0");
        assertEquals(2, data.totalShares());
    }

    // =====================================================================
    // cleanupOrphanTeams
    // =====================================================================

    @Test
    @DisplayName("cleanupOrphanTeams：指向不存在团队的分享被清掉")
    void cleanupOrphanTeams() {
        final ShareData data = data();
        data.share(OBJ_A, T1);
        data.share(OBJ_A, T2); // T2 是孤儿
        data.share(OBJ_B, T3);

        existingTeams.add(T1);
        existingTeams.add(T3);

        assertEquals(1, data.cleanupOrphanTeams());
        assertEquals(Set.of(T1), data.getTeamsOfObject(OBJ_A));
        assertEquals(Set.of(T3), data.getTeamsOfObject(OBJ_B));
        assertEquals(0, data.cleanupOrphanTeams(), "再清理一次应为 0");
        assertEquals(2, data.totalShares());
    }

    @Test
    @DisplayName("cleanupOrphanTeams：某对象所有团队都是孤儿时，整个条目被移除")
    void cleanupOrphanTeamsRemovesEmptyEntry() {
        final ShareData data = data();
        data.share(OBJ_A, T2); // 孤儿

        assertEquals(1, data.cleanupOrphanTeams());
        assertEquals(0, data.size());
        assertTrue(data.getTeamsOfObject(OBJ_A).isEmpty());
    }

    // =====================================================================
    // 持久化
    // =====================================================================

    @Test
    @DisplayName("持久化往返：save → load 保留 objectId → teamId 的映射")
    void persistenceRoundTrip() throws Exception {
        final Path f = file();

        final ShareData first = data(f);
        first.share(OBJ_A, T1);
        first.share(OBJ_A, T2);
        first.share(OBJ_B, T1);
        first.save();

        assertTrue(Files.exists(f));
        assertTrue(Files.size(f) > 0L);

        final ShareData second = data(f);
        second.load();
        assertFalse(second.hasLoadFailed());
        assertEquals(2, second.size());
        assertEquals(Set.of(T1, T2), second.getTeamsOfObject(OBJ_A));
        assertEquals(Set.of(T1), second.getTeamsOfObject(OBJ_B));
        assertEquals(Set.of(OBJ_A, OBJ_B), second.getObjectsSharedToTeam(T1));
        assertEquals(3, second.totalShares());
    }

    @Test
    @DisplayName("坏文件保护：load 失败置 loadFailed；之后 save 不覆盖坏文件")
    void badFileProtection() throws Exception {
        final Path f = file();
        final String corrupt = "{ this is not valid json";
        Files.writeString(f, corrupt, StandardCharsets.UTF_8);

        final ShareData data = data(f);
        data.load();
        assertTrue(data.hasLoadFailed(), "损坏文件后 loadFailed 应为 true");

        // 故意往内存塞数据，证明“跳过保存”不是因为内存为空
        assertTrue(data.share(OBJ_A, T1));
        data.save();

        assertEquals(corrupt, Files.readString(f, StandardCharsets.UTF_8),
                "load 失败后 save 不得覆盖坏文件");
    }

    @Test
    @DisplayName("文件不存在：load 重置 loadFailed；之后 save 正常写盘")
    void missingFileResetsFlag() throws Exception {
        final Path f = file();
        Files.writeString(f, "{ bad json", StandardCharsets.UTF_8);

        final ShareData data = data(f);
        data.load();
        assertTrue(data.hasLoadFailed());

        Files.delete(f);
        data.load();
        assertFalse(data.hasLoadFailed());

        assertTrue(data.share(OBJ_A, T1));
        data.save();
        assertTrue(Files.exists(f));
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains(OBJ_A));
    }

    @Test
    @DisplayName("内存为空 + 文件非空 → save 跳过，不清空文件")
    void emptyMemoryNonEmptyFileSkipsSave() throws Exception {
        final Path f = file();
        final String original = "{\"" + OBJ_A + "\":[\"" + T1 + "\"]}";
        Files.writeString(f, original, StandardCharsets.UTF_8);

        final ShareData data = data(f); // 不 load，内存为空
        assertEquals(0, data.size());
        data.save();

        assertEquals(original, Files.readString(f, StandardCharsets.UTF_8),
                "空内存不得覆盖非空文件");
    }
}
