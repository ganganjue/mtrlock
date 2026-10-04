package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mtr.core.operation.DepotOperationByIds;
import org.mtr.core.tool.Utilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.1 车厂操作判定与 <b>MTR 真实序列化器</b> 的对接测试。
 *
 * <p>前面的矩阵测试用的是手写 JSON；这里用 MTR 自己的
 * {@link Utilities#getJsonObjectFromData(org.mtr.core.serializer.SerializedDataBase)}
 * 生成 {@link DepotOperationByIds} 的载荷（与 {@code PacketDepotGenerate(DepotOperationByIds)}
 * 构造器里写进 {@code content} 的字符串完全同源），确保「字段名 / 数字格式 / objectId 拼法」
 * 三者不会随 MTR 版本漂移。</p>
 */
class DepotOperationRealPayloadTest {

    /** 取一个库位里真实存在过的负数 id（负数最容易暴露 hex 拼法问题）。 */
    private static final long DEPOT_ID = -261431857219323562L;
    private static final String DEPOT_KEY = PermissionChecker.PREFIX_DEPOT + ":"
            + Utilities.numberToPaddedHexString(DEPOT_ID);

    private static final String OWNER = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OUTSIDER = "cccccccc-0000-0000-0000-000000000003";

    /** 用 MTR 的序列化器生成真实载荷（与包里的 content 同一个来源）。 */
    private static String realPayload(long... depotIds) {
        final DepotOperationByIds operation = new DepotOperationByIds();
        for (long id : depotIds) {
            operation.addDepotId(id);
        }
        return Utilities.getJsonObjectFromData(operation).toString();
    }

    @Test
    @DisplayName("真实载荷是 {\"depotIds\":[<long>]}，且判定能命中")
    void realPayloadIsDenied() {
        final String json = realPayload(DEPOT_ID);
        assertTrue(json.startsWith("{\"depotIds\":["), json);
        assertTrue(json.contains(Long.toString(DEPOT_ID)), json);

        final Map<String, String> owners = new HashMap<>();
        owners.put(DEPOT_KEY, OWNER);

        final List<String> denied = PermissionGuard.findDeniedInDepotOperation(
                json, owners::containsKey, objectId -> false);
        assertEquals(List.of(DEPOT_KEY), denied, "真实载荷必须能被判定为无权限");
    }

    @Test
    @DisplayName("真实载荷 + 创建者 → 放行；真实载荷 + 多车厂 → 只列无权限的")
    void realPayloadAllowedForCreatorAndMixed() {
        final long other = 794930712694558185L;
        final String otherKey = PermissionChecker.PREFIX_DEPOT + ":"
                + Utilities.numberToPaddedHexString(other);
        final Map<String, String> owners = new HashMap<>();
        owners.put(DEPOT_KEY, OWNER);
        owners.put(otherKey, OWNER);

        final String json = realPayload(DEPOT_ID, other);
        // 创建者本人（permission 只看 uuid 是否等于 owner）
        assertEquals(List.of(), PermissionGuard.findDeniedInDepotOperation(
                json, owners::containsKey, objectId -> true));
        // 非授权：两个都被列出，顺序与载荷一致
        assertEquals(List.of(DEPOT_KEY, otherKey), PermissionGuard.findDeniedInDepotOperation(
                json, owners::containsKey, objectId -> false));
    }

    @Test
    @DisplayName("真实载荷 + 无归属 → fail-open 放行")
    void realPayloadFailOpenWithoutOwner() {
        assertEquals(List.of(), PermissionGuard.findDeniedInDepotOperation(
                realPayload(DEPOT_ID), objectId -> false, objectId -> false));
    }
}
