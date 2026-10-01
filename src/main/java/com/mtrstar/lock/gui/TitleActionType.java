package com.mtrstar.lock.gui;

/** 称号 GUI 的操作类型（1.2.3）。 */
public enum TitleActionType {

    /** 设置称号（用 {@code targetUuid} + {@code title}）。 */
    SET,
    /** 清除称号（用 {@code targetUuid}）。 */
    CLEAR,
    /** 仅请求一次全量同步。 */
    REQUEST_SYNC;

    /** 是否只读（不修改数据）。 */
    public boolean isReadOnly() {
        return this == REQUEST_SYNC;
    }
}
