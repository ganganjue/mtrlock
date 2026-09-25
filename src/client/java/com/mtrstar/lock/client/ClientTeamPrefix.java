package com.mtrstar.lock.client;

import com.mtrstar.lock.team.TeamPrefix;

import java.util.Map;
import java.util.Set;

/**
 * 客户端玩家名前缀（头顶名字 / 客户端显示名用）。
 *
 * <p>优先级与服务端 {@link TeamPrefix} 一致：
 * <b>自定义称呼（{@link ClientOwnership#getTitle(String)}）&gt; 团队前缀 &gt; {@link #NO_TEAM}</b>。
 * 称呼完整显示、不截断；团队名才截前两字。</p>
 *
 * <p>服务端团队前缀走 {@code TeamData}，取“最早加入的团队”；客户端没有 {@code createdAt}
 * 信息，只有 S2C 同步来的 {@code teamId → members} 与 {@code teamId → name}，因此这里用
 * <b>teamId 字典序最小</b>的所属团队近似“最早加入”。视觉上可用。</p>
 *
 * <p>数据来源：{@link ClientOwnership}（titles / teamMembers / teamNames），由
 * {@code MtrlockClient} 在收到 {@code mtrlock:sync_ownership} 时填充。
 * 查不到 → {@link #NO_TEAM}，永不返回 null。</p>
 *
 * <p>纯静态缓存读取，不触碰 {@code FabricLoader} / {@code TeamData} / {@code TitleData}，可纯 JVM 测试。</p>
 */
public final class ClientTeamPrefix {

    /** 没有任何称呼 / 团队时使用的前缀（与服务端 {@link TeamPrefix#NO_TEAM} 一致）。 */
    public static final String NO_TEAM = "[独立建造者]";

    /** 团队名前缀保留的字符数（按 Unicode code point）。 */
    public static final int PREFIX_CHARS = 2;

    private ClientTeamPrefix() {
    }

    /**
     * 玩家在客户端显示用的前缀。
     *
     * <p><b>永不返回 null</b>：称呼 / 团队都没有、或非法 uuid → {@link #NO_TEAM}。</p>
     *
     * @param playerUuid 玩家 UUID（{@link net.minecraft.entity.Entity#getUuidAsString()}）
     * @return 形如 {@code [红石局长]}（称呼，完整）/ {@code [红石]}（团队，截两字）/ {@link #NO_TEAM}
     */
    public static String of(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return NO_TEAM;
        }

        // 1) 自定义称呼优先，完整显示、不截断
        final String title = ClientOwnership.getTitle(playerUuid);
        if (title != null && !title.isEmpty()) {
            return "[" + title + "]";
        }

        // 2) 团队名前两字
        final String teamId = earliestTeamId(playerUuid);
        if (teamId == null) {
            return NO_TEAM;
        }
        final String name = ClientOwnership.getTeamName(teamId);
        if (name == null || name.isEmpty()) {
            return NO_TEAM;
        }
        final String prefix = firstChars(name, PREFIX_CHARS);
        return prefix.isEmpty() ? NO_TEAM : "[" + prefix + "]";
    }

    /**
     * 遍历本地团队快照，找包含该玩家、且 teamId 字典序最小的团队。
     *
     * @return teamId；该玩家不在任何团队时返回 null
     */
    private static String earliestTeamId(String playerUuid) {
        String best = null;
        for (Map.Entry<String, Set<String>> entry : ClientOwnership.getTeamMembers().entrySet()) {
            final String teamId = entry.getKey();
            final Set<String> members = entry.getValue();
            if (teamId == null || members == null || !members.contains(playerUuid)) {
                continue;
            }
            if (best == null || teamId.compareTo(best) < 0) {
                best = teamId;
            }
        }
        return best;
    }

    /**
     * 取前 {@code n} 个 Unicode code point（复用服务端纯函数，避免重复实现；
     * 注意只调 {@link TeamPrefix#firstChars(String, int)}，不会触发 {@code TeamData}）。
     */
    private static String firstChars(String s, int n) {
        return TeamPrefix.firstChars(s, n);
    }
}
