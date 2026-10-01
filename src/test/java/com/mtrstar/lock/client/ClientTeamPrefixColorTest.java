package com.mtrstar.lock.client;

import com.mtrstar.lock.team.TeamPrefix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClientTeamPrefix} 客户端称号颜色测试（1.2.4）。 */
class ClientTeamPrefixColorTest {

    private static final String PLAYER = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String TEAM = "team-1";

    @BeforeEach
    @AfterEach
    void reset() {
        ClientOwnership.clear();
    }

    @Test
    @DisplayName("客户端 resolve：称号 + 颜色")
    void titleWithColor() {
        ClientOwnership.setTitles(Map.of(PLAYER, "红石局长"));
        ClientOwnership.setTitleColors(Map.of(PLAYER, "#ff5555"));

        final TeamPrefix.Resolved r = ClientTeamPrefix.resolve(PLAYER);
        assertEquals("红石局长", r.text());
        assertEquals("#ff5555", r.color());
        assertTrue(r.title());
        assertEquals("[红石局长]", ClientTeamPrefix.of(PLAYER));
    }

    @Test
    @DisplayName("客户端 resolve：有称号无色 → color=null")
    void titleWithoutColor() {
        ClientOwnership.setTitles(Map.of(PLAYER, "调度员"));
        final TeamPrefix.Resolved r = ClientTeamPrefix.resolve(PLAYER);
        assertEquals("调度员", r.text());
        assertNull(r.color());
        assertTrue(r.title());
    }

    @Test
    @DisplayName("客户端 resolve：无称号回退团队前缀，不带颜色")
    void fallbackTeam() {
        ClientOwnership.setShareSnapshot(Map.of(), Map.of(TEAM, Set.of(PLAYER)));
        ClientOwnership.setTeamNames(Map.of(TEAM, "红石铁路局"));

        final TeamPrefix.Resolved r = ClientTeamPrefix.resolve(PLAYER);
        assertEquals("红石", r.text());
        assertNull(r.color());
        assertFalse(r.title());
        assertEquals("[红石]", ClientTeamPrefix.of(PLAYER));
    }

    @Test
    @DisplayName("客户端 resolve：无任何数据 → null；of → 空串")
    void noData() {
        assertNull(ClientTeamPrefix.resolve(PLAYER));
        assertEquals(ClientTeamPrefix.NO_TEAM, ClientTeamPrefix.of(PLAYER));
        assertNull(ClientTeamPrefix.resolve(null));
    }

    @Test
    @DisplayName("clear() 后颜色缓存一并清空")
    void clearResetsColors() {
        ClientOwnership.setTitles(Map.of(PLAYER, "局长"));
        ClientOwnership.setTitleColors(Map.of(PLAYER, "#00aa00"));
        assertEquals("#00aa00", ClientTeamPrefix.resolve(PLAYER).color());

        ClientOwnership.clear();
        assertNull(ClientTeamPrefix.resolve(PLAYER));
    }
}
