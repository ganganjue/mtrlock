package com.mtrstar.lock.team;

import java.util.List;

/**
 * 玩家显示名前缀（服务端：聊天栏 / tab / 加入离开 / 死亡消息）。
 *
 * <p>优先级：<b>自定义称呼（{@link TitleData}）&gt; 团队前缀 &gt; {@link #NO_TEAM}</b>。</p>
 * <ul>
 *   <li>uuid 非法 → {@link #NO_TEAM}；</li>
 *   <li>有自定义称呼 → {@code "[" + title + "]"}，<b>完整显示、不截断</b>
 *       （管理员起的称呼应完整展示）；</li>
 *   <li>否则取"最早加入的团队"（{@link TeamData#getTeamsOfPlayer(String)} 已按 createdAt
 *       稳定排序，第一个即最早），把团队名前 {@value #PREFIX_CHARS} 个 Unicode code point
 *       包进方括号，例如“红石铁路局” → {@code [红石]}；</li>
 *   <li>都没有 → {@link #NO_TEAM}。</li>
 * </ul>
 *
 * <p>本类<b>纯函数</b>为主（{@link #firstChars(String, int)} / {@link #of(String)}），
 * 两个外部数据源（称呼、团队名）分别通过 {@link TitleLookup} / {@link TeamNameLookup}
 * seam 注入：生产默认走 {@link TitleData} / {@link TeamData} 单例，纯 JVM 测试走桩——
 * 因此本类<b>类加载时不会触碰 FabricLoader</b>（默认 lookup 是 lambda，只有真正调用时
 * 才会用到那些单例）。</p>
 */
public final class TeamPrefix {

    /**
     * 没有任何称呼 / 团队时使用的前缀。
     *
     * <p>1.2.0：<b>空串</b>——无团队无称呼时不再显示任何前缀（包括方括号 / 空格）。</p>
     */
    public static final String NO_TEAM = "";

    /** 团队名前缀保留的字符数（按 Unicode code point）。 */
    public static final int PREFIX_CHARS = 2;

    /**
     * 解析后的显示前缀（1.2.4）。
     *
     * @param text  前缀文本（不含方括号）：称呼原文，或团队名前两字
     * @param color 称呼颜色 {@code #rrggbb}；团队前缀 / 无色为 null
     * @param title 是否来自自定义称呼（团队前缀为 false）
     */
    public record Resolved(String text, String color, boolean title) {
    }

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

    /**
     * "按 uuid 查自定义称呼"的最小接口。
     *
     * <p>生产实现走 {@link TitleData#getTitle(String)}；纯 JVM 测试注入内存桩。</p>
     */
    @FunctionalInterface
    public interface TitleLookup {

        /**
         * @param playerUuid 玩家 UUID
         * @return 该玩家的自定义称呼；没有返回 {@code null}
         */
        String titleOf(String playerUuid);

        /**
         * 该玩家称呼的颜色（{@code #rrggbb}）；没有 / 无色返回 {@code null}。
         *
         * <p>默认返回 null：1.2.3 的测试 lambda / 注入桩只实现 {@link #titleOf(String)} 也能编译。</p>
         */
        default String colorOf(String playerUuid) {
            return null;
        }
    }

    /** 生产团队 lookup：延迟到真正调用时才触碰 {@link TeamData} 单例。 */
    private static final TeamNameLookup PRODUCTION_TEAMS = uuid -> {
        final List<Team> teams = TeamData.getInstance().getTeamsOfPlayer(uuid);
        if (teams == null || teams.isEmpty()) {
            return null;
        }
        final Team first = teams.get(0);
        return first == null ? null : first.getName();
    };

    /** 生产称呼 lookup：延迟到真正调用时才触碰 {@link TitleData} 单例。 */
    // 注意：必须写成 lambda，而不是 TitleData.getInstance()::getTitle ——
    // 绑定方法引用会在 TeamPrefix 类初始化时【立即】调用 getInstance()（触发 FabricLoader）；
    // lambda 则延迟到真正调用 of() 时才触碰单例。
    private static final TitleLookup PRODUCTION_TITLES = new TitleLookup() {
        @Override
        public String titleOf(String playerUuid) {
            return TitleData.getInstance().getTitle(playerUuid);
        }

        @Override
        public String colorOf(String playerUuid) {
            return TitleData.getInstance().getColor(playerUuid);
        }
    };

    /** 当前团队 lookup（volatile：测试可替换；生产恒为 {@link #PRODUCTION_TEAMS}）。 */
    private static volatile TeamNameLookup teamLookup = PRODUCTION_TEAMS;

    /** 当前称呼 lookup（volatile：测试可替换；生产恒为 {@link #PRODUCTION_TITLES}）。 */
    private static volatile TitleLookup titleLookup = PRODUCTION_TITLES;

    private TeamPrefix() {
    }

    /**
     * 玩家显示用的前缀。
     *
     * <p><b>永不返回 null</b>：称呼 / 团队都没有、或非法 uuid → {@link #NO_TEAM}（空串）。</p>
     *
     * @param playerUuid 玩家 UUID（{@link net.minecraft.entity.Entity#getUuidAsString()}）
     * @return 形如 {@code [红石局长]}（称呼，完整）/ {@code [红石]}（团队，截两字）/ {@link #NO_TEAM}
     */
    public static String of(String playerUuid) {
        return of(playerUuid, titleLookup, teamLookup);
    }

    /**
     * 与 {@link #of(String)} 完全同逻辑，但由调用方直接注入称呼 / 团队来源。
     *
     * <p>这是给单元测试与 Placeholder 兼容层（{@code com.mtrstar.lock.compat.MtrlockPlaceholders}）
     * 使用的 seam：生产调用仍走 {@link #of(String)}（单例 lookup），
     * 这里不触碰 {@link TitleData} / {@link TeamData} 单例，纯 JVM 可测。</p>
     *
     * @param playerUuid 玩家 UUID
     * @param titles     称呼来源；{@code null} 视为“无称呼”
     * @param teams      团队来源；{@code null} 视为“无团队”
     * @return 同 {@link #of(String)}，永不返回 null
     */
    public static String of(String playerUuid, TitleLookup titles, TeamNameLookup teams) {
        final Resolved resolved = resolve(playerUuid, titles, teams);
        return resolved == null || resolved.text().isEmpty() ? NO_TEAM : "[" + resolved.text() + "]";
    }

    /**
     * 与 {@link #of(String)} 同优先级，但额外返回颜色（1.2.4）。
     *
     * <p>优先级不变：<b>自定义称呼 &gt; 团队前缀 &gt; 无</b>，颜色只影响样式。
     * 没有任何前缀时返回 {@code null}。</p>
     */
    public static Resolved resolve(String playerUuid) {
        return resolve(playerUuid, titleLookup, teamLookup);
    }

    /** {@link #resolve(String)} 的注入版（测试 / 客户端复用）。 */
    public static Resolved resolve(String playerUuid, TitleLookup titles, TeamNameLookup teams) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return null;
        }

        // 1) 自定义称呼优先，完整显示、不截断
        final String title = titles == null ? null : titles.titleOf(playerUuid);
        if (title != null && !title.isEmpty()) {
            return new Resolved(title, titles.colorOf(playerUuid), true);
        }

        // 2) 团队名前两字（不参与颜色）
        final String firstTeamName = teams == null ? null : teams.firstTeamName(playerUuid);
        if (firstTeamName == null || firstTeamName.isEmpty()) {
            return null;
        }
        final String prefix = firstChars(firstTeamName, PREFIX_CHARS);
        return prefix.isEmpty() ? null : new Resolved(prefix, null, false);
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

    /** 测试注入团队 lookup；传 null 恢复生产实现。 */
    static void setLookup(TeamNameLookup replacement) {
        teamLookup = replacement != null ? replacement : PRODUCTION_TEAMS;
    }

    /** 测试注入称呼 lookup；传 null 恢复生产实现。 */
    static void setTitleLookup(TitleLookup replacement) {
        titleLookup = replacement != null ? replacement : PRODUCTION_TITLES;
    }

    /** 恢复两个生产 lookup。 */
    static void resetLookup() {
        teamLookup = PRODUCTION_TEAMS;
        titleLookup = PRODUCTION_TITLES;
    }
}
