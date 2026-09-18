package com.mtrstar.lock.perm;

import com.mtrstar.lock.team.ShareData;
import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamData;
import org.mtr.core.data.Depot;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.mapping.holder.ServerPlayerEntity;

import java.util.Set;

/**
 * 权限判定工具（功能 4，纯服务端逻辑）。
 *
 * <p>只做“这个玩家能不能编辑这个对象”的判定，<b>不拦截</b>任何操作（拦截是功能 5 的事），
 * 也不读配置文件、不发网络包、不碰 GUI。</p>
 *
 * <p>判定规则（1.1.0 起）：</p>
 * <ol>
 *   <li>管理员（permission level &gt;= 3）→ 直接放行；</li>
 *   <li>否则看归属：{@code ownership.json} 里该 objectId 的创建者 UUID 等于玩家 UUID → 放行；</li>
 *   <li>否则看分享：对象分享给了某团队 T，且玩家是 T 的成员 → 放行；</li>
 *   <li>其余情况一律拒绝。</li>
 * </ol>
 *
 * <p>objectId 约定：{@code <prefix>:<hexId>}，prefix ∈ {route, station, depot}，
 * hexId 取 MTR 的 {@code NameColorDataBase.getHexId()} 原始输出
 * （= {@code Utilities.numberToPaddedHexString(id)}，16 位大写十六进制，<b>不能 lowercase / 截断</b>）。</p>
 *
 * <p>为了能写纯 JVM 单元测试，查询被抽象成 {@link OwnershipLookup} / {@link CreatorLookup} /
 * {@link ShareLookup} / {@link TeamMembershipLookup} 这些最小接口：生产代码用内置的生产实现
 * （内部走 {@link OwnershipData} / {@link ShareData} / {@link TeamData} 单例），测试注入简单桩。</p>
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
        if (player == null) {
            return false;
        }
        return canEdit(objectId, player.getUuidAsString(), isAdmin(player),
                OWNERSHIP, SHARES, MEMBERSHIPS);
    }

    /**
     * 1.1.0 纯逻辑判定（分享感知）：不依赖 Minecraft / Fabric，可直接单测。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>{@code isAdmin} → true；</li>
     *   <li>{@code objectId} / {@code playerUuid} 为 null / 空 → false；</li>
     *   <li>创建者 == 玩家 → true；</li>
     *   <li>对象分享给的任一团队 T 满足“玩家是 T 成员” → true；</li>
     *   <li>否则 false。</li>
     * </ol>
     *
     * @param objectId    对象 id，可为 null
     * @param playerUuid  玩家 UUID，可为 null
     * @param isAdmin     是否管理员（OP 3+）
     * @param creators    创建者查询；为 null 视为无归属
     * @param shares      分享查询；为 null 视为无分享
     * @param memberships 团队成员查询；为 null 视为不匹配
     * @return 能否编辑
     */
    public static boolean canEdit(String objectId, String playerUuid, boolean isAdmin,
                                  CreatorLookup creators,
                                  ShareLookup shares,
                                  TeamMembershipLookup memberships) {
        // 1) 管理员豁免：即使其它参数全为 null 也放行，不 NPE
        if (isAdmin) {
            return true;
        }
        // 2) 参数校验
        if (objectId == null || objectId.isEmpty() || playerUuid == null || playerUuid.isEmpty()) {
            return false;
        }
        // 3) 创建者本人
        if (creators != null) {
            final String creator = creators.getCreator(objectId);
            if (creator != null && creator.equals(playerUuid)) {
                return true;
            }
        }
        // 4) 分享给某团队、且玩家是该团队成员
        if (shares != null && memberships != null) {
            final Set<String> teamIds = shares.teamsOfObject(objectId);
            if (teamIds != null) {
                for (String teamId : teamIds) {
                    if (teamId != null && memberships.isMemberOf(teamId, playerUuid)) {
                        return true;
                    }
                }
            }
        }
        // 5) 否则拒绝
        return false;
    }

    /**
     * 服务端生产便捷入口：为一个玩家构造“能否编辑 objectId”的判定函数（分享感知）。
     *
     * <p>每处理一个包只解析一次 uuid / admin 标志，避免 {@link PermissionGuard} 对同一请求里
     * 多个对象重复取；返回的函数内部走 {@link #canEdit(String, String, boolean, CreatorLookup,
     * ShareLookup, TeamMembershipLookup)} + 生产注入。</p>
     *
     * @param player 服务端玩家；可为 null（此时判定恒 false）
     * @return 判定函数（供 {@link PermissionGuard} 使用）
     */
    public static PermissionGuard.EditPermission editPermissionFor(ServerPlayerEntity player) {
        final String playerUuid = player == null ? null : player.getUuidAsString();
        final boolean admin = isAdmin(player);
        return objectId -> canEdit(objectId, playerUuid, admin, OWNERSHIP, SHARES, MEMBERSHIPS);
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
    interface OwnershipLookup extends CreatorLookup {

        /** 是否存在归属记录（用于 fail-open 的“创建 / 未知对象”判断）。 */
        boolean hasCreator(String objectId);

        /** 取创建者 UUID；不存在返回 null。 */
        @Override
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

    /** 生产实现：分享查询（延迟到真正调用才触碰 {@link ShareData} 单例）。 */
    private static final ShareLookup SHARES = objectId -> ShareData.getInstance().getTeamsOfObject(objectId);

    /** 生产实现：团队成员查询（延迟到真正调用才触碰 {@link TeamData} 单例）。 */
    private static final TeamMembershipLookup MEMBERSHIPS = (teamId, playerUuid) -> {
        final Team team = TeamData.getInstance().getTeam(teamId);
        return team != null && team.isMember(playerUuid);
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
