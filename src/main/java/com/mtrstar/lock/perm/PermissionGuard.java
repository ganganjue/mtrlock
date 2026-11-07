package com.mtrstar.lock.perm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mtr.core.tool.Utilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能 5：从 MTR 请求 JSON 里找出“当前操作者无权编辑 / 删除”的 objectId。
 *
 * <p>纯逻辑，不依赖 Minecraft / Fabric / 文件系统，方便单元测试。
 * 真正的玩家身份由 Mixin 在包入口拿到后，以函数回调注入：
 * {@link CreatorLookup}（该 objectId 是否已有归属记录）、
 * {@link ParentLookup}（子对象 platform/siding 对应的父对象 station/depot）、
 * {@link EditPermission}（该玩家能否编辑它）。</p>
 *
 * <p>核心规则（只拦“编辑 / 删除 / 车厂操作”，不拦“创建”）：</p>
 * <ul>
 *   <li><b>顶层对象</b>（station / route / depot）：{@code hasCreator == false} → 视为创建 / 未知 → 放行（fail-open）；
 *       {@code hasCreator == true} 且 {@code canEdit == false} → 记入 denied。</li>
 *   <li><b>子对象</b>（platform / siding）：先用 {@link ParentLookup} 找到父对象，
 *       再用<b>父对象</b>的归属与权限判定（“改子对象 = 改它所属的车站 / 车厂”）；
 *       父对象查不到 / 无归属记录 → fail-open 放行。</li>
 *   <li><b>车厂操作</b>（1.4.1，{@code depotIds}）：见
 *       {@link #findDeniedInDepotOperation(String, CreatorLookup, EditPermission)}。</li>
 * </ul>
 *
 * <p>objectId 统一为 {@code <prefix>:<hexId>}，hexId 用
 * {@link Utilities#numberToPaddedHexString(long)}（与 MTR {@code getHexId()} 一致，16 位大写）。</p>
 */
public final class PermissionGuard {

    /** 1.5.2：新增线路站台引用被拒时，客户端与服务端共用的文案 key。 */
    public static final String STATION_ADDITION_DENY_KEY = "mtrlock.deny.station_permission";

    /** 拒绝项来源：普通编辑/删除，或“新增线路站台引用”的站台权限补查。 */
    public enum DenialSource {
        EDIT,
        STATION_ADDITION
    }

    /** 带来源的拒绝项；调用方据此选择通用文案或站台专用文案。 */
    public record Denial(String objectId, DenialSource source) {
    }

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

    /**
     * 取某条线路当前已引用的 platformId 集合（1.5.2）。
     *
     * <p>用于把请求 JSON 里的 {@code routePlatformData} 与 MTR 当前 {@code Route}
     * 做差集：只有在请求里、但当前线路没有的 platformId 才算“新增引用”。</p>
     */
    @FunctionalInterface
    public interface RoutePlatformLookup {

        /** @return 当前已引用的 platformId；线路不存在时返回空集合 */
        Set<Long> currentPlatformIds(long routeId);
    }

    /**
     * platformId → 父车站 objectId 反查（1.5.2）。
     *
     * <p>生产实现走 MTR 的 {@code platformIdMap.get(platformId).area}；
     * 站台不存在 / 孤儿站台（{@code area == null}）返回 null，调用方按 fail-open 跳过，
     * 交给既有 {@code RouteRefReconciler} 处理。</p>
     */
    @FunctionalInterface
    public interface PlatformStationLookup {

        /** @return {@code station:<HEX>}；找不到父车站返回 null */
        String stationObjectIdOf(long platformId);
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
        return findDeniedInUpdate(contentJson, creators, parents, permission, null, null);
    }

    /**
     * 编辑请求 + 线路新增站台引用补查（1.5.2）。
     *
     * <p>在原有编辑权限检查之外，对每条线路对比：
     * 请求 JSON 的 {@code routes[].routePlatformData[].platformId}
     * vs MTR 当前 {@code Route#getRoutePlatforms()}。只检查“新增”的 platformId，
     * 并要求操作者同时具备该站台所属车站的编辑权限（OP3+ 已由 {@code permission} 豁免）。</p>
     *
     * <p>只收集拒绝项，不在本方法里取消包；调用方看到非空列表后必须整包拒绝，
     * 不能删除非法项后继续执行（MTR 的 {@code PacketUpdateData} 是整体语义）。</p>
     *
     * <p>{@code currentRoutePlatforms} / {@code stationObjectIds} 为 null 时退化为 1.5.1
     * 的纯编辑检查（fail-open，不阻断数据未就绪时的正常流程）。</p>
     *
     * @param contentJson          请求 JSON
     * @param creators             归属查询
     * @param parents              子对象 → 父对象解析；可为 null
     * @param permission           操作者对 objectId 的编辑权限
     * @param currentRoutePlatforms 当前线路已引用的 platformId 查询；可为 null
     * @param stationObjectIds     platformId → 父车站 objectId 查询；可为 null
     * @return 被拒绝的 objectId 列表；空表示全部放行
     */
    public static List<String> findDeniedInUpdate(String contentJson,
                                                  CreatorLookup creators,
                                                  ParentLookup parents,
                                                  EditPermission permission,
                                                  RoutePlatformLookup currentRoutePlatforms,
                                                  PlatformStationLookup stationObjectIds) {
        return objectIdsOf(findDeniedInUpdateDetailed(
                contentJson, creators, parents, permission, currentRoutePlatforms, stationObjectIds));
    }

    /**
     * 与 {@link #findDeniedInUpdate(String, CreatorLookup, ParentLookup, EditPermission,
     * RoutePlatformLookup, PlatformStationLookup)} 判定完全一致，但返回带来源的拒绝项。
     *
     * <p>客户端与服务端都走这一入口；只有新增线路站台引用补查产生的拒绝项标记为
     * {@link DenialSource#STATION_ADDITION}，其余仍为 {@link DenialSource#EDIT}。</p>
     */
    public static List<Denial> findDeniedInUpdateDetailed(String contentJson,
                                                          CreatorLookup creators,
                                                          ParentLookup parents,
                                                          EditPermission permission,
                                                          RoutePlatformLookup currentRoutePlatforms,
                                                          PlatformStationLookup stationObjectIds) {
        final List<Denial> denied = new ArrayList<>();
        final JsonObject root = parse(contentJson);
        if (root == null) {
            return denied;
        }
        final DenialSink sink = (objectId, source) -> denied.add(new Denial(objectId, source));
        collectObjects(root, "stations", PermissionChecker.PREFIX_STATION, creators, parents, permission, sink);
        collectObjects(root, "routes", PermissionChecker.PREFIX_ROUTE, creators, parents, permission, sink);
        collectObjects(root, "depots", PermissionChecker.PREFIX_DEPOT, creators, parents, permission, sink);
        collectObjects(root, "platforms", ChildParents.PREFIX_PLATFORM, creators, parents, permission, sink);
        collectObjects(root, "sidings", ChildParents.PREFIX_SIDING, creators, parents, permission, sink);
        collectDeniedRoutePlatformAdditions(root, currentRoutePlatforms, stationObjectIds, permission, denied);
        return denied;
    }

    /** 把带来源的拒绝项映射为 objectId 列表；保持旧调用方兼容。 */
    public static List<String> objectIdsOf(List<Denial> denials) {
        final List<String> objectIds = new ArrayList<>();
        if (denials != null) {
            for (Denial denial : denials) {
                if (denial != null && denial.objectId() != null) {
                    objectIds.add(denial.objectId());
                }
            }
        }
        return objectIds;
    }

    /** 是否包含“新增线路站台引用”的站台权限拒绝。 */
    public static boolean hasStationAdditionDenial(List<Denial> denials) {
        if (denials != null) {
            for (Denial denial : denials) {
                if (denial != null && denial.source() == DenialSource.STATION_ADDITION) {
                    return true;
                }
            }
        }
        return false;
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
        final List<Denial> denied = new ArrayList<>();
        final JsonObject root = parse(contentJson);
        if (root == null) {
            return objectIdsOf(denied);
        }
        final DenialSink sink = (objectId, source) -> denied.add(new Denial(objectId, source));
        collectLongs(root, "stationIds", PermissionChecker.PREFIX_STATION, creators, parents, permission, sink);
        collectLongs(root, "routeIds", PermissionChecker.PREFIX_ROUTE, creators, parents, permission, sink);
        collectLongs(root, "depotIds", PermissionChecker.PREFIX_DEPOT, creators, parents, permission, sink);
        collectLongs(root, "platformIds", ChildParents.PREFIX_PLATFORM, creators, parents, permission, sink);
        collectLongs(root, "sidingIds", ChildParents.PREFIX_SIDING, creators, parents, permission, sink);
        return objectIdsOf(denied);
    }

    // =====================================================================
    // 车厂操作请求（DepotOperationByIds，1.4.1）
    // =====================================================================

    /**
     * 车厂操作请求（{@code DepotOperationByIds} 的 JSON，1.4.1）。
     *
     * <p>对应三个 C2S 包，载荷结构完全相同（只有 {@code depotIds} 一个 long 数组）：</p>
     * <ul>
     *   <li>{@code generate_by_depot_ids}（{@code PacketDepotGenerate}）—— 生成列车；</li>
     *   <li>{@code instant_deploy_by_depot_ids}（{@code PacketDepotInstantDeploy}）—— 即时部署；</li>
     *   <li>{@code clear_by_depot_ids}（{@code PacketDepotClear}）—— 清空车辆。</li>
     * </ul>
     *
     * <p>车厂是顶层对象，直接用<b>自身归属</b>判定（不走 {@link ChildParents}）；
     * 没有任何归属记录 → fail-open 放行，与编辑 / 删除的语义一致。</p>
     *
     * <p>只在 {@code PacketRequestResponseBase#runServerOutbound} 的 HEAD 调用——那里才拿得到玩家；
     * 请求入队后（{@code OperationProcessor} / {@code Depot}）已经没有玩家身份了。</p>
     *
     * @param contentJson 请求 JSON（{@code PacketRequestResponseBase.content}）
     * @param creators    归属查询
     * @param permission  权限查询
     * @return 被拒绝的 objectId 列表；空表示全部放行
     */
    public static List<String> findDeniedInDepotOperation(String contentJson,
                                                          CreatorLookup creators,
                                                          EditPermission permission) {
        final List<Denial> denied = new ArrayList<>();
        final JsonObject root = parse(contentJson);
        if (root == null) {
            return objectIdsOf(denied);
        }
        // parents = null：车厂是顶层对象，不需要子对象 → 父对象反查
        collectLongs(root, "depotIds", PermissionChecker.PREFIX_DEPOT, creators, null, permission,
                (objectId, source) -> denied.add(new Denial(objectId, source)));
        return objectIdsOf(denied);
    }

    /**
     * 1.4.2：已经解析好的车厂 objectId 列表版本。
     *
     * <p>供 {@code /mtr generatePath / clearVehicles / instantDeploy} 命令路径复用：
     * 这些命令使用 {@code DepotOperationByName}，没有 C2S 包 JSON，只有名字过滤结果。
     * 判定语义与上面的 JSON 版本完全一致：顶层对象、无归属 fail-open、有归属且无权限则拒绝。</p>
     */
    public static List<String> findDeniedInDepotOperation(Collection<String> objectIds,
                                                          CreatorLookup creators,
                                                          EditPermission permission) {
        final List<String> denied = new ArrayList<>();
        if (objectIds == null || creators == null || permission == null) {
            return denied;
        }
        for (String objectId : objectIds) {
            if (objectId == null || objectId.isEmpty()) {
                continue;
            }
            if (!creators.hasCreator(objectId)) {
                continue; // 无归属 → fail-open
            }
            if (!permission.canEdit(objectId)) {
                denied.add(objectId);
            }
        }
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

    /**
     * 找出请求里新增的线路站台引用，并补查操作者对父车站的编辑权限。
     *
     * <p>请求结构由 MTR {@code RouteSchema} 序列化确认：
     * {@code routes: [{ id, routePlatformData: [{ platformId, ... }] }]}。
     * 已存在引用与删除引用不进入补查：只处理 {@code platformId} 不在当前线路集合里的项。</p>
     */
    private static void collectDeniedRoutePlatformAdditions(JsonObject root,
                                                            RoutePlatformLookup currentRoutePlatforms,
                                                            PlatformStationLookup stationObjectIds,
                                                            EditPermission permission,
                                                            List<Denial> denied) {
        if (currentRoutePlatforms == null || stationObjectIds == null || permission == null) {
            return;
        }
        final JsonElement routesElement = root.get("routes");
        if (routesElement == null || !routesElement.isJsonArray()) {
            return;
        }

        for (JsonElement routeElement : routesElement.getAsJsonArray()) {
            if (!routeElement.isJsonObject()) {
                continue;
            }
            final JsonObject routeObject = routeElement.getAsJsonObject();
            final Long routeId = asLong(routeObject.get("id"));
            if (routeId == null) {
                continue;
            }
            final JsonElement platformDataElement = routeObject.get("routePlatformData");
            if (platformDataElement == null || !platformDataElement.isJsonArray()) {
                continue;
            }

            final Set<Long> currentPlatformIds;
            try {
                currentPlatformIds = currentRoutePlatforms.currentPlatformIds(routeId);
            } catch (RuntimeException e) {
                continue; // 查询失败按 fail-open 处理，不能因补查破坏正常编辑
            }

            final Set<Long> additions = new LinkedHashSet<>();
            for (JsonElement platformData : platformDataElement.getAsJsonArray()) {
                if (!platformData.isJsonObject()) {
                    continue;
                }
                final Long platformId = asLong(platformData.getAsJsonObject().get("platformId"));
                if (platformId != null && (currentPlatformIds == null || !currentPlatformIds.contains(platformId))) {
                    additions.add(platformId);
                }
            }

            for (Long platformId : additions) {
                final String stationObjectId;
                try {
                    stationObjectId = stationObjectIds.stationObjectIdOf(platformId);
                } catch (RuntimeException e) {
                    continue; // 孤儿 / 数据未就绪：交给既有对账逻辑，不在此处崩溃或误拒
                }
                if (stationObjectId == null || stationObjectId.isEmpty()) {
                    continue;
                }
                if (!permission.canEdit(stationObjectId)) {
                    denied.add(new Denial(
                            ChildParents.PREFIX_PLATFORM + ":" + Utilities.numberToPaddedHexString(platformId),
                            DenialSource.STATION_ADDITION));
                }
            }
        }
    }

    /** 内部拒绝项写入器；来源由各收集器显式给出。 */
    @FunctionalInterface
    private interface DenialSink {
        void deny(String objectId, DenialSource source);
    }

    /** UpdateDataRequest：数组元素是对象，取 "id"。 */
    private static void collectObjects(JsonObject root, String key, String prefix,
                                       CreatorLookup creators, ParentLookup parents,
                                       EditPermission permission, DenialSink sink) {
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
                addIfDenied(prefix, id, creators, parents, permission, sink);
            }
        }
    }

    /** DeleteDataRequest：数组元素是 long。 */
    private static void collectLongs(JsonObject root, String key, String prefix,
                                     CreatorLookup creators, ParentLookup parents,
                                     EditPermission permission, DenialSink sink) {
        final JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            final Long id = asLong(item);
            if (id != null) {
                addIfDenied(prefix, id, creators, parents, permission, sink);
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
                                    EditPermission permission, DenialSink sink) {
        final String objectId = prefix + ":" + Utilities.numberToPaddedHexString(id);

        // 子对象（platform / siding）：用父对象（station / depot）的归属来判定
        final String parentObjectId = (parents == null) ? null : parents.parentOf(objectId);
        if (parentObjectId != null) {
            if (creators.hasCreator(parentObjectId) && !permission.canEdit(parentObjectId)) {
                sink.deny(objectId, DenialSource.EDIT);
            }
            return;
        }

        // 顶层对象（station / route / depot）：按自身归属判定
        // 无归属记录 → 视为创建 / 未知对象 → 放行（fail-open）
        if (!creators.hasCreator(objectId)) {
            return;
        }
        if (!permission.canEdit(objectId)) {
            sink.deny(objectId, DenialSource.EDIT);
        }
    }
}
