package com.mtrstar.lock.perm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子对象 → 父对象 从属索引（功能 5 / 方案 A）。
 *
 * <p>MTR 的 {@code Platform} / {@code Siding} 的 JSON 只序列化自己的
 * {@code id / name / color / position1 / position2}，<b>不携带父级 station / depot 的 id</b>
 * （反编译 {@code SavedRailBaseSchema.serializeData} 确认）。父关系只存在于运行时的
 * {@code SavedRailBase.area} 字段，由服务端 {@code Data.sync()} 里的
 * {@code mapAreasAndSavedRails}（按几何包含关系）建立。</p>
 *
 * <p>因此这里维护一张 {@code childObjectId -> parentObjectId} 索引：
 * {@code "platform:<hex>" -> "station:<hex>"}、{@code "siding:<hex>" -> "depot:<hex>"}。
 * 由 {@code DataChildParentMixin} 在每个 {@code Data.sync()} 之后刷新。
 * 包入口的权限判定就能用父对象的归属来判定子对象的编辑 / 删除。</p>
 *
 * <p>线程安全：{@link ConcurrentHashMap}；服务端 tick 线程写、网络线程读。</p>
 */
public final class ChildParents {

    /** 子对象 id 前缀。 */
    public static final String PREFIX_PLATFORM = "platform";
    public static final String PREFIX_SIDING = "siding";

    /** childObjectId -> parentObjectId。 */
    private static final Map<String, String> PARENT = new ConcurrentHashMap<>();

    private ChildParents() {
    }

    /** 记录 / 覆盖一条从属关系。 */
    public static void put(String childObjectId, String parentObjectId) {
        if (childObjectId == null || parentObjectId == null) {
            return;
        }
        PARENT.put(childObjectId, parentObjectId);
    }

    /**
     * 查询子对象的父对象。
     *
     * @return 父 objectId；不是已知子对象 / 查不到时返回 {@code null}
     */
    public static String get(String childObjectId) {
        return childObjectId == null ? null : PARENT.get(childObjectId);
    }

    /** 移除一个子对象的索引（显式删除 platform / siding 时用）。 */
    public static void remove(String childObjectId) {
        if (childObjectId != null) {
            PARENT.remove(childObjectId);
        }
    }

    /** 移除挂在某父对象下的所有子索引（删除 station / depot 时用）。 */
    public static void removeChildrenOf(String parentObjectId) {
        if (parentObjectId == null) {
            return;
        }
        PARENT.entrySet().removeIf(entry -> parentObjectId.equals(entry.getValue()));
    }

    /** 当前索引条数（测试 / 调试用）。 */
    public static int size() {
        return PARENT.size();
    }

    /** 清空（测试用）。 */
    public static void clear() {
        PARENT.clear();
    }
}
