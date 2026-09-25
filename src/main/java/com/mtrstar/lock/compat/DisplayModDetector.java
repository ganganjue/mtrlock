package com.mtrstar.lock.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.HashSet;
import java.util.Set;

/**
 * 检测会与 mtrlock 内置“显示名前缀 Mixin”重复加前缀的模组。
 *
 * <p>当服务器/客户端装了以下任一显示类模组时，mtrlock <b>不注入</b>显示 Mixin，
 * 改由 {@link MtrlockPlaceholders} 注册 Placeholder，交给对方在自己配置里引用：</p>
 * <ul>
 *   <li>StyledChat（mod id {@value #STYLED_CHAT}）：接管聊天栏 / 加入 / 离开 / 死亡消息；</li>
 *   <li>StyledPlayerList（mod id {@value #STYLED_PLAYER_LIST}）：接管 tab 列表玩家名。</li>
 * </ul>
 *
 * <p>{@link #hasConflictingDisplayMod(Set)} 是纯函数重载，不触碰 FabricLoader，可在纯 JVM 下单测；
 * 无参版本结果会缓存（模组列表在运行期不变），避免每次 {@code getDisplayName()} 都遍历模组。</p>
 */
public final class DisplayModDetector {

    /** StyledChat 的 mod id。 */
    public static final String STYLED_CHAT = "styledchat";

    /** StyledPlayerList 的 mod id。 */
    public static final String STYLED_PLAYER_LIST = "styledplayerlist";

    /** 无参检测结果缓存（null = 尚未计算；volatile 保证多线程可见）。 */
    private static volatile Boolean cached;

    private DisplayModDetector() {
    }

    /**
     * 当前运行环境是否存在冲突模组（结果缓存）。
     *
     * @return 装了 StyledChat 或 StyledPlayerList 时返回 true
     */
    public static boolean hasConflictingDisplayMod() {
        Boolean result = cached;
        if (result == null) {
            final Set<String> loadedModIds = new HashSet<>();
            FabricLoader.getInstance().getAllMods()
                    .forEach(mod -> loadedModIds.add(mod.getMetadata().getId()));
            result = hasConflictingDisplayMod(loadedModIds);
            cached = result;
        }
        return result;
    }

    /**
     * 纯函数重载：给定一组已加载的 mod id，判断是否存在冲突模组。
     *
     * @param loadedMods 已加载 mod id 集合；null / 空集合返回 false
     * @return 含 {@value #STYLED_CHAT} 或 {@value #STYLED_PLAYER_LIST} 时返回 true
     */
    public static boolean hasConflictingDisplayMod(Set<String> loadedMods) {
        if (loadedMods == null || loadedMods.isEmpty()) {
            return false;
        }
        return loadedMods.contains(STYLED_CHAT) || loadedMods.contains(STYLED_PLAYER_LIST);
    }
}
