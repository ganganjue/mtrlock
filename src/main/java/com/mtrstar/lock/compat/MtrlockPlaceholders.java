package com.mtrstar.lock.compat;

import com.mtrstar.lock.team.Team;
import com.mtrstar.lock.team.TeamData;
import com.mtrstar.lock.team.TeamPrefix;
import com.mtrstar.lock.team.TitleData;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Function;

/**
 * 与 StyledChat / StyledPlayerList 共存时注册的 Placeholder（placeholder-api 2.x）。
 *
 * <p>装了两者之一时 {@link DisplayModDetector} 会让三个显示 Mixin 全部早退，
 * 由本类把前缀暴露成占位符，服主在 StyledChat / StyledPlayerList 配置里引用：</p>
 * <ul>
 *   <li>{@code %mtrlock_prefix%} —— 完整前缀（如 {@code [红石]} / {@code [服主]}），无则空串；</li>
 *   <li>{@code %mtrlock_title%}  —— 自定义称呼（不含方括号），无则空串；</li>
 *   <li>{@code %mtrlock_team%}   —— 团队名前两字（不含方括号），无则空串。</li>
 * </ul>
 *
 * <p>前缀优先级复用 {@link TeamPrefix#of(String, TeamPrefix.TitleLookup, TeamPrefix.TeamNameLookup)}
 * （称呼 &gt; 团队 &gt; 空串）；称呼 / 团队分别走 {@link TitleData} / {@link TeamData}。
 * 所有取值都对 null 安全（玩家 null / 无团队 / 无称呼 → 空串）。</p>
 *
 * <p>本类只在 {@code DisplayModDetector.hasConflictingDisplayMod()} 为 true 时被调用；
 * placeholder-api 作为 <b>compileOnly</b> 软依赖，未装冲突模组时不会加载本类。</p>
 */
public final class MtrlockPlaceholders {

    /** 完整前缀占位符名（不带方括号外再包一层）。 */
    public static final String PREFIX_PLACEHOLDER = "mtrlock_prefix";

    /** 自定义称呼占位符名。 */
    public static final String TITLE_PLACEHOLDER = "mtrlock_title";

    /** 团队名前两字占位符名。 */
    public static final String TEAM_PLACEHOLDER = "mtrlock_team";

    /** 生产称呼来源：延迟到真正求值时才触碰 {@link TitleData} 单例。 */
    private static final TeamPrefix.TitleLookup PRODUCTION_TITLES =
            uuid -> TitleData.getInstance().getTitle(uuid);

    /** 生产团队来源：延迟到真正求值时才触碰 {@link TeamData} 单例（最早加入的团队）。 */
    private static final TeamPrefix.TeamNameLookup PRODUCTION_TEAMS = uuid -> {
        final List<Team> teams = TeamData.getInstance().getTeamsOfPlayer(uuid);
        if (teams == null || teams.isEmpty()) {
            return null;
        }
        final Team first = teams.get(0);
        return first == null ? null : first.getName();
    };

    private MtrlockPlaceholders() {
    }

    /** 注册三个占位符；只在检测到冲突模组时调用一次。 */
    public static void register() {
        register(PREFIX_PLACEHOLDER,
                context -> prefixValue(playerUuid(context), PRODUCTION_TITLES, PRODUCTION_TEAMS));
        register(TITLE_PLACEHOLDER,
                context -> titleValue(playerUuid(context), PRODUCTION_TITLES));
        register(TEAM_PLACEHOLDER,
                context -> teamValue(playerUuid(context), PRODUCTION_TEAMS));
    }

    private static void register(String name, Function<PlaceholderContext, String> resolver) {
        // 注意：placeholder-api 查表用 Identifier.tryParse(占位符名)。
        // 形如 %mtrlock_prefix% 的写法不带冒号，Identifier 会默认命名空间为 minecraft，
        // 因此这里用单参数构造（= minecraft:mtrlock_prefix）才能与用户写的 %mtrlock_prefix% 对上。
        Placeholders.register(new Identifier(name),
                (context, argument) -> PlaceholderResult.value(resolver.apply(context)));
    }

    /** 从占位符上下文取玩家 UUID；玩家 / 上下文为 null → null（后续一律回退空串）。 */
    private static String playerUuid(PlaceholderContext context) {
        if (context == null) {
            return null;
        }
        final ServerPlayerEntity player = context.player();
        return player == null ? null : player.getUuidAsString();
    }

    // =====================================================================
    // 纯逻辑 seam（单元测试直接调用，不触碰单例 / 不依赖 placeholder-api 运行时）
    // =====================================================================

    /**
     * {@code %mtrlock_prefix%} 的取值逻辑：复用 {@link TeamPrefix} 的优先级
     * （称呼 &gt; 团队 &gt; 空串）。
     *
     * @param playerUuid 玩家 UUID，可为 null
     * @param titles     称呼来源，可为 null
     * @param teams      团队来源，可为 null
     * @return 形如 {@code [红石]} / {@code [服主]}；无数据时返回空串
     */
    public static String prefixValue(String playerUuid, TeamPrefix.TitleLookup titles,
                                     TeamPrefix.TeamNameLookup teams) {
        return TeamPrefix.of(playerUuid, titles, teams);
    }

    /**
     * {@code %mtrlock_title%} 的取值逻辑：自定义称呼（不含方括号）。
     *
     * @param playerUuid 玩家 UUID，可为 null
     * @param titles     称呼来源，可为 null
     * @return 称呼原文；无称呼 / 空称呼 → 空串
     */
    public static String titleValue(String playerUuid, TeamPrefix.TitleLookup titles) {
        if (playerUuid == null || playerUuid.isEmpty() || titles == null) {
            return "";
        }
        final String title = titles.titleOf(playerUuid);
        return title == null ? "" : title;
    }

    /**
     * {@code %mtrlock_team%} 的取值逻辑：团队名前两字（不含方括号）。
     *
     * @param playerUuid 玩家 UUID，可为 null
     * @param teams      团队来源，可为 null
     * @return 团队名前 {@link TeamPrefix#PREFIX_CHARS} 个 code point；无团队 → 空串
     */
    public static String teamValue(String playerUuid, TeamPrefix.TeamNameLookup teams) {
        if (playerUuid == null || playerUuid.isEmpty() || teams == null) {
            return "";
        }
        final String name = teams.firstTeamName(playerUuid);
        return name == null ? "" : TeamPrefix.firstChars(name, TeamPrefix.PREFIX_CHARS);
    }
}
