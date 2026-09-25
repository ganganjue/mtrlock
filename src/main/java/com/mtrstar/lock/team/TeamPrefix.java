package com.mtrstar.lock.team;

import java.util.List;

/**
 * 团队名前缀（聊天 / 显示名用）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>玩家没有任何团队（或 uuid 非法）→ {@link #NO_TEAM}；</li>
 *   <li>玩家有团队 → 取"最早加入的团队"（{@link TeamData#getTeamsOfPlayer(String)} 已按
 *       createdAt 稳定排序，第一个即最早），再把团队名前 {@value #PREFIX_CHARS} 个
 *       Unicode code point 包进方括号，例如“红石铁路局” → {@code [红石]}；</li>
 *   <li>按 code point 截取：中文按字算，emoji（代理对）不会被从中间截断。</li>
 * </ul>
 *
 * <p>本类<b>纯函数</b>为主（{@link #firstChars(String, int)} / {@link #of(String)}），
 * 唯一的外部依赖是"按 uuid 取最早团队名"，通过 {@link TeamNameLookup} seam 注入，
 * 生产默认走 {@link TeamData} 单例，纯 JVM 测试走桩——因此本类<b>类加载时不会触碰
 * FabricLoader</b>（默认 lookup 是 lambda，只有在真正调用时才会用到 {@code TeamData}）。</p>
 */
public final class TeamPrefix {

    /** 没有任何团队时使用的前缀。 */
    public static final String NO_TEAM = "[独立建造者]";

    /** 团队名前缀保留的字符数（按 Unicode code point）。 */
    public static final int PREFIX_CHARS = 2;

    /**
     * "按 uuid 查最早加入的团队名"的最小接口。
     *
     * <p>生产实现走 {@link TeamData#getTeamsOfPlayer(String)}；纯 JVM 测试注入内存桩。</p>
     */
    @FunctionalInterface
    public interface TeamNameLookup {

        /**
         * @param playerUuid 玩家 UUID
         * @return 该玩家最早加入的团队名；没有团队返回 {@code null}
         */
        String firstTeamName(String playerUuid);
    }

    /** 生产 lookup：延迟到真正调用时才触碰 {@link TeamData} 单例（类加载不触发 FabricLoader）。 */
    private static final TeamNameLookup PRODUCTION = uuid -> {
        final List<Team> teams = TeamData.getInstance().getTeamsOfPlayer(uuid);
        if (teams == null || teams.isEmpty()) {
            return null;
        }
        final Team first = teams.get(0);
        return first == null ? null : first.getName();
    };

    /** 当前 lookup（volatile：测试可替换；生产恒为 {@link #PRODUCTION}）。 */
    private static volatile TeamNameLookup lookup = PRODUCTION;

    private TeamPrefix() {
    }

    /**
     * 玩家聊天 / 显示用的团队前缀。
     *
     * <p><b>永不返回 null</b>：无团队 / 非法 uuid / 团队名为空 → {@link #NO_TEAM}。</p>
     *
     * @param playerUuid 玩家 UUID（{@link net.minecraft.entity.Entity#getUuidAsString()}）
     * @return 形如 {@code [红石]} 或 {@link #NO_TEAM}
     */
    public static String of(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return NO_TEAM;
        }
        final String firstTeamName = lookup.firstTeamName(playerUuid);
        if (firstTeamName == null || firstTeamName.isEmpty()) {
            return NO_TEAM;
        }
        final String prefix = firstChars(firstTeamName, PREFIX_CHARS);
        if (prefix.isEmpty()) {
            return NO_TEAM;
        }
        return "[" + prefix + "]";
    }

    /**
     * 取字符串前 {@code n} 个 Unicode code point。
     *
     * <p>null / 空串 / {@code n <= 0} → 空串；不足 {@code n} 个 → 原串。
     * 以 code point 为单位，不会把 emoji 的代理对从中间切开。</p>
     *
     * @param s 源字符串
     * @param n 需要的 code point 个数
     * @return 前 {@code n} 个 code point（可能是空串，但绝不是 null）
     */
    public static String firstChars(String s, int n) {
        if (s == null || s.isEmpty() || n <= 0) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    // =====================================================================
    // 测试 seam（包内可见）
    // =====================================================================

    /** 测试注入 lookup；传 null 恢复生产实现。 */
    static void setLookup(TeamNameLookup replacement) {
        lookup = replacement != null ? replacement : PRODUCTION;
    }

    /** 恢复生产 lookup。 */
    static void resetLookup() {
        lookup = PRODUCTION;
    }
}
