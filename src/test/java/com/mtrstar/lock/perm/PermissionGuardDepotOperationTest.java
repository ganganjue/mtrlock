package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.tool.Utilities;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.1 车厂操作判定（{@link PermissionGuard#findDeniedInDepotOperation}）单元测试。
 *
 * <p>纯 JVM：归属 / 分享 / 团队成员都用内存桩，权限判定走
 * {@link PermissionChecker#canEdit(String, String, boolean, CreatorLookup, ShareLookup,
 * TeamMembershipLookup)}，完全等价于生产环境里
 * {@code PermissionChecker.editPermissionFor(player)} 的行为。</p>
 *
 * <p>三个 C2S 包（{@code generate_by_depot_ids} / {@code instant_deploy_by_depot_ids} /
 * {@code clear_by_depot_ids}）的载荷结构完全相同，所以矩阵按「同一个 JSON、三个包名」
 * 参数化覆盖；{@code instanceof} 分支本身由 VERIFY 的手工步骤覆盖。</p>
 */
class PermissionGuardDepotOperationTest {

    /** MTR 的三个车厂操作包（{@code getKey()}），载荷结构相同。 */
    private static final List<String> PACKET_KEYS = List.of(
            "generate_by_depot_ids",
            "instant_deploy_by_depot_ids",
            "clear_by_depot_ids");

    private static final long DEPOT_OWNED = -261431857219323562L;
    private static final long DEPOT_OTHER = 794930712694558185L;
    private static final long DEPOT_NO_OWNER = 1234567890123456789L;

    private static final String KEY_OWNED = PermissionChecker.PREFIX_DEPOT + ":" + Utilities.numberToPaddedHexString(DEPOT_OWNED);
    private static final String KEY_OTHER = PermissionChecker.PREFIX_DEPOT + ":" + Utilities.numberToPaddedHexString(DEPOT_OTHER);
    private static final String KEY_NO_OWNER = PermissionChecker.PREFIX_DEPOT + ":" + Utilities.numberToPaddedHexString(DEPOT_NO_OWNER);

    private static final String OWNER = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TEAM_MEMBER = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String OUTSIDER = "cccccccc-0000-0000-0000-000000000003";

    private static final String TEAM = "team-depot";

    /** objectId → 创建者（模拟 ownership.json）。 */
    private final Map<String, String> owners = new HashMap<>();

    /** objectId → 分享到的团队（模拟 shares.json）。 */
    private final Map<String, Set<String>> shares = new HashMap<>();

    /** teamId → 成员（模拟 teams.json）。 */
    private final Map<String, Set<String>> members = new HashMap<>();

    // =====================================================================
    // 载荷 / 桩
    // =====================================================================

    /** {@code DepotOperationByIds} 的 JSON：{@code {"depotIds":[<long>...]}}。 */
    private static String payload(long... depotIds) {
        final StringBuilder builder = new StringBuilder("{\"depotIds\":[");
        for (int i = 0; i < depotIds.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(depotIds[i]);
        }
        return builder.append("]}").toString();
    }

    private PermissionGuard.CreatorLookup creatorLookup() {
        return owners::containsKey;
    }

    private PermissionGuard.EditPermission permission(String playerUuid, boolean admin) {
        return objectId -> PermissionChecker.canEdit(objectId, playerUuid, admin,
                owners::get,
                id -> shares.getOrDefault(id, Collections.emptySet()),
                (teamId, uuid) -> members.getOrDefault(teamId, Collections.emptySet()).contains(uuid));
    }

    private List<String> denied(String contentJson, String playerUuid, boolean admin) {
        return PermissionGuard.findDeniedInDepotOperation(contentJson, creatorLookup(),
                permission(playerUuid, admin));
    }

    // =====================================================================
    // 放行：创建者 / 团队成员 / 分享对象 / OP 3+ / 无归属
    // =====================================================================

    @Test
    @DisplayName("三个车厂操作包的载荷判定完全一致")
    void allThreePacketPayloadsBehaveIdentically() {
        owners.put(KEY_OWNED, OWNER);
        for (String packetKey : PACKET_KEYS) {
            final String json = payload(DEPOT_OWNED);
            assertEquals(List.of(), denied(json, OWNER, false), packetKey + " 创建者应放行");
            assertEquals(List.of(KEY_OWNED), denied(json, OUTSIDER, false), packetKey + " 非授权应被拒");
        }
    }

    @Test
    @DisplayName("创建者本人 → 放行")
    void creatorIsAllowed() {
        owners.put(KEY_OWNED, OWNER);
        assertEquals(List.of(), denied(payload(DEPOT_OWNED), OWNER, false));
    }

    @Test
    @DisplayName("团队成员（车厂已分享给团队）→ 放行")
    void teamMemberIsAllowed() {
        owners.put(KEY_OWNED, OWNER);
        shares.put(KEY_OWNED, Set.of(TEAM));
        members.put(TEAM, Set.of(TEAM_MEMBER));
        assertEquals(List.of(), denied(payload(DEPOT_OWNED), TEAM_MEMBER, false));
    }

    @Test
    @DisplayName("分享对象：对象分享给团队、玩家是该团队成员 → 放行")
    void sharedObjectMemberIsAllowed() {
        owners.put(KEY_OWNED, OWNER);
        shares.put(KEY_OWNED, Set.of(TEAM));
        members.put(TEAM, new HashSet<>(Set.of(TEAM_MEMBER)));
        assertEquals(List.of(), denied(payload(DEPOT_OWNED), TEAM_MEMBER, false));
    }

    @Test
    @DisplayName("OP 3+（isAdmin=true）→ 放行，即使不是创建者也没分享")
    void adminIsAllowed() {
        owners.put(KEY_OWNED, OWNER);
        assertEquals(List.of(), denied(payload(DEPOT_OWNED), OUTSIDER, true));
    }

    @Test
    @DisplayName("无归属记录的车厂 → fail-open 放行（模组安装前 / 网页创建）")
    void noOwnerIsFailOpen() {
        assertEquals(List.of(), denied(payload(DEPOT_NO_OWNER), OUTSIDER, false));
    }

    // =====================================================================
    // 拦截：非授权 / 混合 / 多车厂
    // =====================================================================

    @Test
    @DisplayName("非授权玩家操作有归属的车厂 → 记入 denied（objectId 形如 depot:<HEX>）")
    void unauthorizedIsDenied() {
        owners.put(KEY_OWNED, OWNER);
        assertEquals(List.of(KEY_OWNED), denied(payload(DEPOT_OWNED), OUTSIDER, false));
    }

    @Test
    @DisplayName("混合请求（1 个有权限 + 1 个无权限）→ 只列出无权限的那个（Mixin 据此整包拒）")
    void mixedRequestListsOnlyDeniedOne() {
        owners.put(KEY_OWNED, OWNER);
        owners.put(KEY_OTHER, OWNER);
        shares.put(KEY_OWNED, Set.of(TEAM));
        members.put(TEAM, Set.of(TEAM_MEMBER));

        // TEAM_MEMBER 对 KEY_OWNED 有权限，对 KEY_OTHER 没有
        assertEquals(List.of(KEY_OTHER), denied(payload(DEPOT_OWNED, DEPOT_OTHER), TEAM_MEMBER, false));
    }

    @Test
    @DisplayName("一次请求多个无权限车厂 → 全部列出，顺序与 JSON 一致")
    void allUnauthorizedAreListedInOrder() {
        owners.put(KEY_OWNED, OWNER);
        owners.put(KEY_OTHER, OWNER);
        assertEquals(List.of(KEY_OWNED, KEY_OTHER), denied(payload(DEPOT_OWNED, DEPOT_OTHER), OUTSIDER, false));
        assertEquals(List.of(KEY_OTHER, KEY_OWNED), denied(payload(DEPOT_OTHER, DEPOT_OWNED), OUTSIDER, false));
    }

    @Test
    @DisplayName("同一车厂在数组里出现两次 → 列出两次（与删除请求的现有行为一致）")
    void duplicateIdsAreListedTwice() {
        owners.put(KEY_OWNED, OWNER);
        assertEquals(List.of(KEY_OWNED, KEY_OWNED), denied(payload(DEPOT_OWNED, DEPOT_OWNED), OUTSIDER, false));
    }

    @Test
    @DisplayName("只认 depotIds：其它键（stationIds / depots 等）不参与车厂操作判定")
    void otherKeysAreIgnored() {
        owners.put(KEY_OWNED, OWNER);
        final String json = "{\"stationIds\":[" + DEPOT_OWNED + "],"
                + "\"depots\":[{\"id\":" + DEPOT_OWNED + "}],"
                + "\"depotIds\":[" + DEPOT_OWNED + "]}";
        // stationIds 里的 id 属于别的对象类型，不该被当成 depot；这里只应命中 depotIds
        assertEquals(1, denied(json, OUTSIDER, false).size());
        assertEquals(KEY_OWNED, denied(json, OUTSIDER, false).get(0));
    }

    // =====================================================================
    // fail-open：结构不对 / 坏 JSON / 元素非法
    // =====================================================================

    @Test
    @DisplayName("depotIds 为空数组 → 放行")
    void emptyDepotIds() {
        ownerExistsButDenied();
        assertEquals(List.of(), denied("{\"depotIds\":[]}", OUTSIDER, false));
    }

    @Test
    @DisplayName("缺少 depotIds 键 → fail-open 放行")
    void missingDepotIdsKey() {
        ownerExistsButDenied();
        assertEquals(List.of(), denied("{}", OUTSIDER, false));
        assertEquals(List.of(), denied("{\"otherIds\":[1]}", OUTSIDER, false));
    }

    @Test
    @DisplayName("depotIds 不是数组（数字 / 对象 / 字符串）→ fail-open 放行")
    void nonArrayDepotIds() {
        ownerExistsButDenied();
        assertEquals(List.of(), denied("{\"depotIds\":123}", OUTSIDER, false));
        assertEquals(List.of(), denied("{\"depotIds\":{}}", OUTSIDER, false));
        assertEquals(List.of(), denied("{\"depotIds\":\"x\"}", OUTSIDER, false));
    }

    @Test
    @DisplayName("JSON 语法错误 / 根不是对象 → fail-open，不抛异常")
    void malformedJsonIsFailOpen() {
        ownerExistsButDenied();
        assertEquals(List.of(), denied("{ this is not json", OUTSIDER, false));
        assertEquals(List.of(), denied("[1,2,3]", OUTSIDER, false));
        assertEquals(List.of(), denied("null", OUTSIDER, false));
    }

    @Test
    @DisplayName("content 为 null / 空串 → fail-open")
    void nullAndEmptyContent() {
        ownerExistsButDenied();
        assertEquals(List.of(), denied(null, OUTSIDER, false));
        assertEquals(List.of(), denied("", OUTSIDER, false));
    }

    @Test
    @DisplayName("数组元素不是数字（字符串 / 对象 / null）→ 跳过该元素，其余照常判定")
    void nonNumericElementsAreSkipped() {
        owners.put(KEY_OWNED, OWNER);
        final String json = "{\"depotIds\":[\"abc\",{},null," + DEPOT_OWNED + "]}";
        assertEquals(List.of(KEY_OWNED), denied(json, OUTSIDER, false));
        assertTrue(denied("{\"depotIds\":[\"abc\",{}]}", OUTSIDER, false).isEmpty());
    }

    @Test
    @DisplayName("负数 / 大数 id → objectId 仍与 getHexId() 的 16 位大写 HEX 一致")
    void idToObjectIdMatchesHexId() {
        owners.put(KEY_OWNED, OWNER);
        final List<String> result = denied(payload(DEPOT_OWNED), OUTSIDER, false);
        assertEquals(1, result.size());
        assertTrue(result.get(0).startsWith("depot:"), result.get(0));
        assertEquals(KEY_OWNED, result.get(0));
        assertEquals(22, result.get(0).length(), "depot:(6) + 16 位十六进制");
    }

    /** 预置一个「有归属但无分享」的车厂，便于 fail-open 用例。 */
    private void ownerExistsButDenied() {
        owners.put(KEY_OWNED, OWNER);
    }
}
