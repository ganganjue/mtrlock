package com.mtrstar.lock.network.payload;

import com.mtrstar.lock.gui.TeamActionType;

/**
 * C2S：{@code TeamGuiActionC2S} 的载荷（1.2.3）。
 *
 * <p>字段全部可空（按 action 取用），服务端会做二次校验：</p>
 * <ul>
 *   <li>{@code CREATE} / {@code APPLY}：用 {@code teamName}；</li>
 *   <li>{@code APPROVE}/{@code DENY}/{@code INVITE}/{@code KICK}/{@code TRANSFER}：
 *       用 {@code teamId} + {@code targetUuid}（GUI 从在线列表选）或 {@code targetName}（手动输入，服务端解析）；</li>
 *   <li>{@code ACCEPT}/{@code DECLINE}/{@code LEAVE}/{@code DISBAND}：用 {@code teamId}；</li>
 *   <li>{@code SHARE}/{@code UNSHARE}：用 {@code teamId} + {@code objectId}。</li>
 * </ul>
 *
 * <p>纯数据 record，不含 Minecraft 类型。</p>
 */
public record TeamGuiAction(int protocolVersion,
                            TeamActionType type,
                            String teamId,
                            String teamName,
                            String targetUuid,
                            String targetName,
                            String objectId) {

    /** 用当前协议版本构造。 */
    public static TeamGuiAction of(TeamActionType type, String teamId, String teamName,
                                   String targetUuid, String targetName, String objectId) {
        return new TeamGuiAction(com.mtrstar.lock.network.GuiProtocol.VERSION,
                type, teamId, teamName, targetUuid, targetName, objectId);
    }

    /** 只请求同步。 */
    public static TeamGuiAction sync() {
        return of(TeamActionType.REQUEST_SYNC, null, null, null, null, null);
    }
}
