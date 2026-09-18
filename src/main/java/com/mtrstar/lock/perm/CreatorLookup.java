package com.mtrstar.lock.perm;

/**
 * 查询对象创建者的最小抽象（纯逻辑）。
 *
 * <p>供 {@link PermissionChecker} 的可测试重载复用：生产实现走
 * {@code OwnershipData.getInstance()::getCreator}，测试注入简单桩。
 * 与 {@link PermissionChecker.OwnershipLookup} 的区别：那个还带 {@code hasCreator}（用于
 * fail-open 的“已有归属记录”判断），本接口只关心“创建者是谁”。</p>
 */
@FunctionalInterface
public interface CreatorLookup {

    /**
     * 取某对象的创建者 UUID。
     *
     * @param objectId 对象 id，形如 {@code route:0B0829457F350DE9}
     * @return 创建者 UUID；无记录返回 {@code null}
     */
    String getCreator(String objectId);
}
