package com.mtrstar.lock.perm;

import org.mtr.core.data.Depot;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.mapping.holder.ServerPlayerEntity;

/**
 * 权限判定工具（功能 4，纯服务端逻辑）。
 *
 * <p>只做“这个玩家能不能编辑这个对象”的判定，<b>不拦截</b>任何操作（拦截是功能 5 的事），
 * 也不读配置文件、不发网络包、不碰 GUI。</p>
 *
 * <p>判定规则：</p>
 * <ol>
 *   <li>管理员（permission level &gt;= 3）→ 直接放行；</li>
 *   <li>否则看归属：{@code ownership.json} 里该 objectId 的创建者 UUID 等于玩家 UUID → 放行；</li>
 *   <li>其余情况一律拒绝。</li>
 * </ol>
 *
 * <p>objectId 约定：{@code <prefix>:<hexId>}，prefix ∈ {route, station, depot}，
 * hexId 取 MTR 的 {@code NameColorDataBase.getHexId()} 原始输出
 * （= {@code Utilities.numberToPaddedHexString(id)}，16 位大写十六进制，<b>不能 lowercase / 截断</b>）。</p>
 *
 * <p>为了能写纯 JVM 单元测试，归属查询被抽象成 {@link OwnershipLookup} 这个最小接口：
 * 生产代码用 {@link #OWNERSHIP}（内部走 {@link OwnershipData} 单例），测试注入简单桩。
 * 公共 API 与规则完全不变。</p>
 */
public final class PermissionChecker {

    /** 管理员豁免所需的最低权限等级（原版 OP level 3）。 */
    public static final int ADMIN_PERMISSION_LEVEL = 3;

    /** 对象 id 前缀。 */
    public static final String PREFIX_ROUTE = "route";
    public static final String PREFIX_STATION = "station";
    public static final String PREFIX_DEPOT = "depot";

    private PermissionChecker() {
    }

    // =====================================================================
    // 公共 API
    // =====================================================================

    /**
     * 判断玩家能否编辑指定 objectId 的对象。
     *
     * @param player   发起操作的玩家；为 null 时返回 false
     * @param objectId {@code <prefix>:<hexId>}；为 null / 空时返回 false
     * @return 管理员，或该对象的创建者本人 → true；否则 false
     */
    public static boolean canEdit(ServerPlayerEntity player, String objectId) {
        return check(isAdmin(player),
                player == null ? null : player.getUuidAsString(),
                objectId,
                OWNERSHIP);
    }

    /**
     * 判断玩家能否编辑某个 MTR 对象（Route / Station / Depot）。
     *
     * <p>内部用 {@code getHexId()} 拼出 objectId，再走 {@link #canEdit(ServerPlayerEntity, String)}。</p>
     *
     * @param player    发起操作的玩家
     * @param mtrObject MTR 的 Route / Station / Depot；其它类型 / null 一律返回 false
     * @return 能否编辑
     */
    public static boolean canEdit(ServerPlayerEntity player, Object mtrObject) {
        final String objectId = objectIdOf(mtrObject);
        return objectId != null && canEdit(player, objectId);
    }

    /**
     * 是否为管理员（permission level &gt;= 3）。
     *
     * @param player 玩家，可为 null
     * @return 管理员返回 true；player 为 null 返回 false（不会 NPE）
     */
    public static boolean isAdmin(ServerPlayerEntity player) {
        return player != null && player.hasPermissionLevel(ADMIN_PERMISSION_LEVEL);
    }

    /**
     * 玩家是否为指定对象的创建者。
     *
     * @param player   玩家，可为 null
     * @param objectId 对象 id，可为 null / 空
     * @return 有归属记录且 UUID 匹配返回 true；否则 false
     */
    public static boolean isCreator(ServerPlayerEntity player, String objectId) {
        return checkCreator(player == null ? null : player.getUuidAsString(), objectId, OWNERSHIP);
    }

    // =====================================================================
    // 内部 / 可测试逻辑
    // =====================================================================

    /**
     * 归属查询的最小抽象，便于单测注入桩，避免触碰 {@link OwnershipData} 的静态初始化
     * （它依赖 FabricLoader / 文件系统）。
     */
    interface OwnershipLookup {

        /** 是否存在归属记录。 */
        boolean hasCreator(String objectId);

        /** 取创建者 UUID；不存在返回 null。 */
        String getCreator(String objectId);
    }

    /** 生产实现：每次查询都取 {@link OwnershipData} 单例（延迟到真正调用时才触发其加载）。 */
    private static final OwnershipLookup OWNERSHIP = new OwnershipLookup() {
        @Override
        public boolean hasCreator(String objectId) {
            return OwnershipData.getInstance().hasCreator(objectId);
        }

        @Override
        public String getCreator(String objectId) {
            return OwnershipData.getInstance().getCreator(objectId);
        }
    };

    /**
     * 核心判定：管理员优先；否则退化为“是否创建者”。
     *
     * @param admin     是否管理员
     * @param playerUuid 玩家 UUID；可为 null
     * @param objectId  对象 id；可为 null
     * @param ownership 归属查询；可为 null（视为无记录）
     */
    static boolean check(boolean admin, String playerUuid, String objectId, OwnershipLookup ownership) {
        // 管理员豁免：即使 playerUuid / objectId / ownership 都是 null 也直接放行，不 NPE
        if (admin) {
            return true;
        }
        return checkCreator(playerUuid, objectId, ownership);
    }

    /** 创建者判定：先做参数与归属校验，再比较 UUID。 */
    static boolean checkCreator(String playerUuid, String objectId, OwnershipLookup ownership) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        if (objectId == null || objectId.isEmpty()) {
            return false;
        }
        if (ownership == null) {
            return false;
        }
        // hasCreator 为 false → 没有归属记录，直接拒绝（不覆盖别人的创建者）
        if (!ownership.hasCreator(objectId)) {
            return false;
        }
        final String creator = ownership.getCreator(objectId);
        return creator != null && creator.equals(playerUuid);
    }

    /**
     * 由 MTR 对象推导 objectId。
     *
     * @param mtrObject Route / Station / Depot；其它类型或 null 返回 null
     * @return {@code <prefix>:<getHexId()>}；无法识别返回 null
     */
    static String objectIdOf(Object mtrObject) {
        if (!(mtrObject instanceof NameColorDataBase)) {
            return null;
        }

        final NameColorDataBase data = (NameColorDataBase) mtrObject;
        final String prefix;
        if (data instanceof Route) {
            prefix = PREFIX_ROUTE;
        } else if (data instanceof Station) {
            prefix = PREFIX_STATION;
        } else if (data instanceof Depot) {
            prefix = PREFIX_DEPOT;
        } else {
            return null; // 其它 NameColorDataBase 子类（本功能不涉及）
        }

        // 必须用 getHexId() 原始输出（16 位大写），不能 lowercase / 截断
        final String hexId = data.getHexId();
        if (hexId == null || hexId.isEmpty()) {
            return null;
        }
        return prefix + ":" + hexId;
    }
}
