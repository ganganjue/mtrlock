package com.mtrstar.lock.gui;

/**
 * 团队 GUI 的操作类型（1.2.3）。
 *
 * <p>与 {@code TeamActions} 的方法一一对应；{@code TeamGuiDispatcher} 负责映射，
 * 因此 GUI 与命令走的是同一段服务端逻辑。</p>
 */
public enum TeamActionType {

    /** 创建团队（用 {@code teamName}）。 */
    CREATE,
    /** 申请加入（用 {@code teamName}）。 */
    APPLY,
    /** 批准申请（用 {@code teamId} + {@code targetUuid}）。 */
    APPROVE,
    /** 拒绝申请（用 {@code teamId} + {@code targetUuid}）。 */
    DENY,
    /** 邀请成员（用 {@code teamId} + {@code targetUuid} / {@code targetName}）。 */
    INVITE,
    /** 接受邀请（用 {@code teamId}）。 */
    ACCEPT,
    /** 拒绝邀请（用 {@code teamId}）。 */
    DECLINE,
    /** 退出团队（用 {@code teamId}）。 */
    LEAVE,
    /** 踢出成员（用 {@code teamId} + {@code targetUuid}）。 */
    KICK,
    /** 转让队长（用 {@code teamId} + {@code targetUuid}）。 */
    TRANSFER,
    /** 解散团队（用 {@code teamId}）。 */
    DISBAND,
    /** 分享对象（用 {@code teamId} + {@code objectId}）。 */
    SHARE,
    /** 取消分享（用 {@code teamId} + {@code objectId}）。 */
    UNSHARE,
    /** 仅请求一次全量同步，不改数据。 */
    REQUEST_SYNC;

    /** 是否只读（不修改数据）。 */
    public boolean isReadOnly() {
        return this == REQUEST_SYNC;
    }
}
