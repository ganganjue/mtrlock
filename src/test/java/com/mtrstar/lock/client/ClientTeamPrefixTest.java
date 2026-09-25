package com.mtrstar.lock.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ClientTeamPrefix} 的纯 JVM 单元测试。
 *
 * <p>不触碰 {@code FabricLoader} / {@code TeamData}：数据通过
 * {@link ClientOwnership#setShareSnapshot(Map, Map)}（teams → members）和
 * {@link ClientOwnership#setTeamNames(Map)}（teamId → name）注入，
 * 模拟客户端收到的 S2C 团队快照。每个用例后 {@link ClientOwnership#clear()}。</p>
 */
class ClientTeamPrefixTest {

    private static final String ALICE = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String BOB = "bbbbbbbb-2222-2222-2222-222222222222";

    /** 字典序：TEAM_A < TEAM_B。 */
    private static final String TEAM_A = "team-A";
    private static final String TEAM_B = "team-B";

    @AfterEach
    void tearDown() {
        ClientOwnership.clear();
    }

    /** 注入 teamId → members 与 teamId → name。 */
    private static void givenTeams(Map<String, Set<String>> members, Map<String, String> names) {
        ClientOwnership.setShareSnapshot(Map.of(), members);
        ClientOwnership.setTeamNames(names);
    }

    // =====================================================================
    // 常量 / 非法参数
    // =====================================================================

    @Test
    @DisplayName("常量：NO_TEAM 与服务端一致；PREFIX_CHARS = 2")
    void constants() {
        assertEquals("[独立建造者]", ClientTeamPrefix.NO_TEAM);
        assertEquals(2, ClientTeamPrefix.PREFIX_CHARS);
    }

    @Test
    @DisplayName("null / 空 uuid → NO_TEAM（永不 null）")
    void invalidUuid() {
        assertSame(ClientTeamPrefix.NO_TEAM, ClientTeamPrefix.of(null));
        assertSame(ClientTeamPrefix.NO_TEAM, ClientTeamPrefix.of(""));
        assertNotNull(ClientTeamPrefix.of(null));
    }

    // =====================================================================
    // 无团队 / 有团队
    // =====================================================================

    @Test
    @DisplayName("无任何团队数据 → NO_TEAM")
    void noTeam() {
        assertSame(ClientTeamPrefix.NO_TEAM, ClientTeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("有一个团队 → [团队名前两字]（中文）")
    void singleTeamChinese() {
        givenTeams(Map.of(TEAM_A, Set.of(ALICE)), Map.of(TEAM_A, "红石铁路局"));
        assertEquals("[红石]", ClientTeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("团队名不足两字 → 全取")
    void shortTeamName() {
        givenTeams(Map.of(TEAM_A, Set.of(ALICE)), Map.of(TEAM_A, "队"));
        assertEquals("[队]", ClientTeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("emoji 团队名按 code point 截断，不切代理对")
    void emojiTeamName() {
        givenTeams(Map.of(TEAM_A, Set.of(ALICE)), Map.of(TEAM_A, "😀红石"));
        assertEquals("[😀红]", ClientTeamPrefix.of(ALICE));
    }

    // =====================================================================
    // 多团队：按 teamId 字典序取最小
    // =====================================================================

    @Test
    @DisplayName("多个团队 → 取 teamId 字典序最小的那个")
    void multipleTeamsPicksLexicographicallySmallest() {
        givenTeams(
                Map.of(TEAM_B, Set.of(ALICE), TEAM_A, Set.of(ALICE)),
                Map.of(TEAM_A, "甲团队", TEAM_B, "乙团队"));
        // TEAM_A < TEAM_B，期望 A 的名字
        assertEquals("[甲团]", ClientTeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("字典序更小的团队里没有该玩家 → 跳过，取真正所属的最小 teamId")
    void ignoresTeamsPlayerIsNotIn() {
        givenTeams(
                Map.of(TEAM_A, Set.of(BOB), TEAM_B, Set.of(ALICE)),
                Map.of(TEAM_A, "错团队", TEAM_B, "红石铁路局"));
        assertEquals("[红石]", ClientTeamPrefix.of(ALICE));
    }

    // =====================================================================
    // 缺名字 / 清空
    // =====================================================================

    @Test
    @DisplayName("所属 teamId 有成员表但没有名字 → NO_TEAM")
    void teamWithoutName() {
        ClientOwnership.setShareSnapshot(Map.of(), Map.of(TEAM_A, Set.of(ALICE)));
        ClientOwnership.setTeamNames(Map.of()); // 名字丢失/未同步
        assertSame(ClientTeamPrefix.NO_TEAM, ClientTeamPrefix.of(ALICE));
    }

    @Test
    @DisplayName("clear() 同时清掉团队名（清后 → NO_TEAM）")
    void clearClearsTeamNames() {
        givenTeams(Map.of(TEAM_A, Set.of(ALICE)), Map.of(TEAM_A, "红石铁路局"));
        assertEquals("[红石]", ClientTeamPrefix.of(ALICE));

        ClientOwnership.clear();
        assertSame(ClientTeamPrefix.NO_TEAM, ClientTeamPrefix.of(ALICE));
    }
}
