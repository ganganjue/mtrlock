package com.mtrstar.lock.team;

import net.minecraft.text.Text;

/**
 * 把 {@link TeamPrefix.Resolved} 渲染成 Minecraft {@link Text} 前缀（1.2.4）。
 *
 * <p>服务端（聊天栏 / tab / 加入离开）与客户端（头顶名字）共用同一段逻辑，
 * 保证「称号颜色」在四处显示一致：<b>方括号不着色，只有称号文本着色</b>；
 * 团队前缀不带颜色。</p>
 */
public final class PrefixDisplay {

    private PrefixDisplay() {
    }

    /** 形如 {@code [称号]} 的 Text；没有任何前缀时返回空 Text。 */
    public static Text of(TeamPrefix.Resolved resolved) {
        if (resolved == null || resolved.text() == null || resolved.text().isEmpty()) {
            return Text.empty();
        }
        return Text.literal("[")
                .append(ColorFormatter.component(resolved.text(), resolved.color()))
                .append(Text.literal("]"));
    }
}
