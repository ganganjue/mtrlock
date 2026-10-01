package com.mtrstar.lock.network;

import com.mtrstar.lock.Mtrlock;
import net.minecraft.util.Identifier;

/**
 * GUI 自定义通道（1.2.3）。
 *
 * <p>与 {@code sync_ownership} 分开，互不影响旧的 S2C 布局；
 * 通道 {@link Identifier} 属于 Minecraft 类型，所以单独放在这里，
 * 纯逻辑（版本 / 结果码 / 限流）不依赖本类。</p>
 */
public final class GuiChannels {

    /** S2C：打开 GUI（含协议版本 + GUI 类型）。 */
    public static final Identifier OPEN_GUI = new Identifier(Mtrlock.MOD_ID, "gui/open");

    /** S2C：团队 GUI 全量快照。 */
    public static final Identifier TEAM_SYNC = new Identifier(Mtrlock.MOD_ID, "gui/team_sync");

    /** S2C：称号 GUI 全量快照。 */
    public static final Identifier TITLE_SYNC = new Identifier(Mtrlock.MOD_ID, "gui/title_sync");

    /** S2C：GUI 操作结果。 */
    public static final Identifier ACTION_RESULT = new Identifier(Mtrlock.MOD_ID, "gui/action_result");

    /** C2S：团队 GUI 操作。 */
    public static final Identifier TEAM_ACTION = new Identifier(Mtrlock.MOD_ID, "gui/team_action");

    /** C2S：称号 GUI 操作。 */
    public static final Identifier TITLE_ACTION = new Identifier(Mtrlock.MOD_ID, "gui/title_action");

    private GuiChannels() {
    }
}
