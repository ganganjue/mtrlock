package com.mtrstar.lock.perm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mtr.core.tool.Utilities;

import java.util.ArrayList;
import java.util.List;

/**
 * 功能 5：从 MTR 请求 JSON 里找出“当前操作者无权编辑 / 删除”的 objectId。
 *
 * <p>纯逻辑，不依赖 Minecraft / Fabric / 文件系统，方便单元测试。
 * 真正的玩家身份由 Mixin 在包入口拿到后，以函数回调注入：
 * {@link CreatorLookup}（该 objectId 是否已有归属记录）、
 * {@link ParentLookup}（子对象 platform/siding 对应的父对象 station/depot）、
 * {@link EditPermission}（该玩家能否编辑它）。</p>
 *
 * <p>核心规则（只拦“编辑 / 删除”，不拦“创建”）：</p>
 * <ul>
 *   <li><b>顶层对象</b>（station / route / depot）：{@code hasCreator == false} → 视为创建 / 未知 → 放行（fail-open）；
 *       {@code hasCreator == true} 且 {@code canEdit == false} → 记入 denied。</li>
 *   <li><b>子对象</b>（platform / siding）：先用 {@link ParentLookup} 找到父对象，
 *       再用<b>父对象</b>的归属与权限判定（“改子对象 = 改它所属的车站 / 车厂”）；
 *       父对象查不到 / 无归属记录 → fail-open 放行。</li>
 * </ul>
 *
 * <p>objectId 统一为 {@code <prefix>:<hexId>}，hexId 用
 * {@link Utilities#numberToPaddedHexString(long)}（与 MTR {@code getHexId()} 一致，16 位大写）。</p>
 */
public final class PermissionGuard {

    /** 查询某 objectId 是否已有归属记录（有 = 该对象已存在，本次是编辑 / 删除而不是创建）。 */
    public interface CreatorLookup {

        boolean hasCreator(String objectId);
    }

    /**
     * 子对象 → 父对象 解析回调。
     *
     * <p>platform 用父 station、siding 用父 depot 判权限（MTR 的 JSON 不携带父 id，见 {@link ChildParents}）。
     * 返回 {@code null} 表示“不是子对象 / 查不到父” → 回退到按自身归属判定。</p>
     */
    public interface ParentLookup {

        String parentOf(String childObjectId);
    }

    /** 查询当前操作者能否编辑 / 删除某 objectId。 */
    public interface EditPermission {

        boolean canEdit(String objectId);
    }

    private PermissionGuard() {
    }

    // =====================================================================
    // 编辑请求（UpdateDataRequest）
    // =====================================================================

    /** 向后兼容重载：不解析父对象（等价于 {@code parents = null}）。 */
    public static List<String> findDeniedInUpdate(String contentJson,
                                                  CreatorLookup creators,
                                                  EditPermission permission) {
        return findDeniedInUpdate(contentJson, creators, null, permission);
    }

    /**
     * 编辑请求（{@code UpdateDataRequest} 的 JSON）。
     *
     * <p>字段：{@code stations} / {@code platforms} / {@code sidings} / {@code routes} / {@code depots}，
     * 每个元素是对象，含 {@code "id": <long>}。</p>
     *
     * @param contentJson 请求 JSON（{@code PacketRequestResponseBase.content}）
     * @param creators    归属查询
     * @param parents     子对象 → 父对象解析；可为 null
     * @param permission  权限查询
     * @return 被拒绝的 objectId 列表；空表示全部放行
     */
    public static List<String> findDeniedInUpdate(String contentJson,
                                                  CreatorLookup creators,
                                                  ParentLookup parents,
                                                  EditPermission permission) {
        final List<String> denied = new ArrayList<>();
        final JsonObject root = parse(contentJson);
        if (root == null) {
            return denied;
        }
        collectObjects(root, "stations", PermissionChecker.PREFIX_STATION, creators, parents, permission, denied);
        collectObjects(root, "routes", PermissionChecker.PREFIX_ROUTE, creators, parents, permission, denied);
        collectObjects(root, "depots", PermissionChecker.PREFIX_DEPOT, creators, parents, permission, denied);
        collectObjects(root, "platforms", ChildParents.PREFIX_PLATFORM, creators, parents, permission, denied);
        collectObjects(root, "sidings", ChildParents.PREFIX_SIDING, creators, parents, permission, denied);
        return denied;
    }

    // =====================================================================
    // 删除请求（DeleteDataRequest）
    // =====================================================================

    /** 向后兼容重载：不解析父对象（等价于 {@code parents = null}）。 */
    public static List<String> findDeniedInDelete(String contentJson,
                                                  CreatorLookup creators,
                                                  EditPermission permission) {
        return findDeniedInDelete(contentJson, creators, null, permission);
    }

    /**
     * 删除请求（{@code DeleteDataRequest} 的 JSON）。
     *
     * <p>字段：{@code stationIds} / {@code routeIds} / {@code depotIds} / {@code platformIds} / {@code sidingIds}，
     * 每个是 long 数组。</p>
     *
     * @param contentJson 请求 JSON
     * @param creators    归属查询
     * @param parents     子对象 → 父对象解析；可为 null
     * @param permission  权限查询
     * @return 被拒绝的 objectId 列表；空表示全部放行
     */
    public static List<String> findDeniedInDelete(String contentJson,
                                                  CreatorLookup creators,
                                                  ParentLookup parents,
                                                  EditPermission permission) {
        final List<String> denied = new ArrayList<>();
        final JsonObject root = parse(contentJson);
        if (root == null) {
            return denied;
        }
        collectLongs(root, "stationIds", PermissionChecker.PREFIX_STATION, creators, parents, permission, denied);
        collectLongs(root, "routeIds", PermissionChecker.PREFIX_ROUTE, creators, parents, permission, denied);
        collectLongs(root, "depotIds", PermissionChecker.PREFIX_DEPOT, creators, parents, permission, denied);
        collectLongs(root, "platformIds", ChildParents.PREFIX_PLATFORM, creators, parents, permission, denied);
        collectLongs(root, "sidingIds", ChildParents.PREFIX_SIDING, creators, parents, permission, denied);
        return denied;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 解析失败 / 空内容一律返回 null（fail-open，避免 MTR 改字段名时误伤正常操作）。 */
    private static JsonObject parse(String contentJson) {
        if (contentJson == null || contentJson.isEmpty()) {
            return null;
        }
        try {
            final JsonElement element = JsonParser.parseString(contentJson);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** UpdateDataRequest：数组元素是对象，取 "id"。 */
    private static void collectObjects(JsonObject root, String key, String prefix,
                                       CreatorLookup creators, ParentLookup parents,
                                       EditPermission permission, List<String> denied) {
        final JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            final JsonElement idElement = item.getAsJsonObject().get("id");
            if (idElement == null || !idElement.isJsonPrimitive()) {
                continue;
            }
            final Long id = asLong(idElement);
            if (id != null) {
                addIfDenied(prefix, id, creators, parents, permission, denied);
            }
        }
    }

    /** DeleteDataRequest：数组元素是 long。 */
    private static void collectLongs(JsonObject root, String key, String prefix,
                                     CreatorLookup creators, ParentLookup parents,
                                     EditPermission permission, List<String> denied) {
        final JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            final Long id = asLong(item);
            if (id != null) {
                addIfDenied(prefix, id, creators, parents, permission, denied);
            }
        }
    }

    private static Long asLong(JsonElement element) {
        try {
            return element.getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private static void addIfDenied(String prefix, long id,
                                    CreatorLookup creators, ParentLookup parents,
                                    EditPermission permission, List<String> denied) {
        final String objectId = prefix + ":" + Utilities.numberToPaddedHexString(id);

        // 子对象（platform / siding）：用父对象（station / depot）的归属来判定
        final String parentObjectId = (parents == null) ? null : parents.parentOf(objectId);
        if (parentObjectId != null) {
            if (creators.hasCreator(parentObjectId) && !permission.canEdit(parentObjectId)) {
                denied.add(objectId);
            }
            return;
        }

        // 顶层对象（station / route / depot）：按自身归属判定
        // 无归属记录 → 视为创建 / 未知对象 → 放行（fail-open）
        if (!creators.hasCreator(objectId)) {
            return;
        }
        if (!permission.canEdit(objectId)) {
            denied.add(objectId);
        }
    }
}
