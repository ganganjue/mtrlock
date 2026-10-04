package com.mtrstar.lock.refs;

import java.util.List;

/**
 * 线路引用移除 / 恢复的提示文案（1.4.0）。
 *
 * <p>纯函数，返回语言键 + 参数：生产侧由 {@code DataChildParentMixin} 用
 * {@code Text.translatable(key, args)} 发给在线 owner，同时写服务器日志。
 * 与 {@code TeamCommand.ok/err} 一样，命令与提示都不走硬编码字符串拼接的国际化分支，
 * 而是把可翻译的部分放在 lang 文件里。</p>
 *
 * <p>语言键：</p>
 * <ul>
 *   <li>{@value #KEY_REMOVED}：权限被撤销，引用被临时移除；参数 = 线路 id 列表（可能截断）；</li>
 *   <li>{@value #KEY_RESTORED}：权限恢复，引用自动加回；参数 = 线路 id 列表（可能截断）。</li>
 * </ul>
 */
public final class RefsNotices {

    /** 「引用被移除」文案的 lang key。 */
    public static final String KEY_REMOVED = "mtrlock.refs.removed_notice";

    /** 「引用已恢复」文案的 lang key。 */
    public static final String KEY_RESTORED = "mtrlock.refs.restored_notice";

    /** 聊天栏里最多列出多少条线路，避免刷屏。 */
    public static final int MAX_ROUTES_IN_MESSAGE = 5;

    private RefsNotices() {
    }

    /** 一次提示：语言键 + 参数（线路 id 摘要）。 */
    public record Notice(String key, String routesSummary, int routeCount) {
    }

    /**
     * 由对账结果生成对 owner 的聊天提示（没有改动时返回 null）。
     *
     * @param result 一次对账的结果
     * @return 移除提示 + 恢复提示（可能只有一个）；无改动返回空列表
     */
    public static List<Notice> noticesFor(RouteRefReconciler.Result result) {
        if (result == null) {
            return List.of();
        }
        final List<Notice> notices = new java.util.ArrayList<>(2);
        if (!result.removed().isEmpty()) {
            notices.add(new Notice(KEY_REMOVED, summarize(result.removed()), distinctRoutes(result.removed())));
        }
        if (!result.restored().isEmpty()) {
            notices.add(new Notice(KEY_RESTORED, summarize(result.restored()), distinctRoutes(result.restored())));
        }
        return notices;
    }

    /** 线路 id 摘要：去重后按出现顺序列出，超出 {@value #MAX_ROUTES_IN_MESSAGE} 条时截断。 */
    public static String summarize(List<RouteRefReconciler.Ref> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        final List<String> routeIds = new java.util.ArrayList<>();
        for (RouteRefReconciler.Ref ref : refs) {
            if (ref != null && ref.routeId() != null && !routeIds.contains(ref.routeId())) {
                routeIds.add(ref.routeId());
            }
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < routeIds.size() && i < MAX_ROUTES_IN_MESSAGE; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(routeIds.get(i));
        }
        if (routeIds.size() > MAX_ROUTES_IN_MESSAGE) {
            builder.append(" 等 ").append(routeIds.size()).append(" 条");
        }
        return builder.toString();
    }

    /** 去重后的线路条数。 */
    static int distinctRoutes(List<RouteRefReconciler.Ref> refs) {
        final List<String> routeIds = new java.util.ArrayList<>();
        for (RouteRefReconciler.Ref ref : refs) {
            if (ref != null && ref.routeId() != null && !routeIds.contains(ref.routeId())) {
                routeIds.add(ref.routeId());
            }
        }
        return routeIds.size();
    }
}
