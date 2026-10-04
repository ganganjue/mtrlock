package com.mtrstar.lock.protect;

import com.mtrstar.lock.perm.PermissionGuard;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * 方块破坏 / 放置的权限判定（纯逻辑，1.3.0）。
 *
 * <p>只回答一个问题：给定「覆盖了这个方块的若干 objectId」，当前玩家能不能动这个方块。
 * 复用现有权限体系（{@link com.mtrstar.lock.perm.PermissionChecker}）：
 * 生产上 {@code hasCreator} 走 {@code OwnershipData::hasCreator}，
 * {@code permission} 走 {@code PermissionChecker.editPermissionFor(player)}
 * （管理员 / 创建者 / 团队成员 三条规则都在里面，本类不新增权限分支）。</p>
 *
 * <p>规则：</p>
 * <ol>
 *   <li>没有任何对象覆盖 → 放行（普通地面）；</li>
 *   <li>逐个覆盖对象：<b>没有归属记录</b>的跳过（网页创建 / 模组安装前创建 → fail-open）；</li>
 *   <li>有归属记录且 {@code canEdit == false} → <b>立刻拒绝</b>；</li>
 *   <li>全部通过 / 全部被跳过 → 放行。</li>
 * </ol>
 *
 * <p>关于重叠：车站与车厂矩形可能重叠。采用「<b>任一覆盖对象拒绝即拒绝</b>」的保守语义，
 * 避免玩家靠一个自己拥有的小车厂覆盖到别人的车站里来绕过保护。</p>
 *
 * <p>fail-open：{@code hasCreator} / {@code permission} 为 null（未初始化 / 异常降级）时放行，
 * 与项目其它 fail-open 策略一致。</p>
 */
public final class BlockProtection {

    private BlockProtection() {
    }

    /**
     * 能否修改（破坏 / 放置）该方块。
     *
     * @param coveringObjectIds 覆盖该方块的对象 id；null / 空 → 放行
     * @param hasCreator        某 objectId 是否已有归属记录；null → 放行
     * @param permission        某 objectId 是否能被当前玩家编辑；null → 放行
     * @return 允许返回 true
     */
    public static boolean canModify(Collection<String> coveringObjectIds,
                                    Predicate<String> hasCreator,
                                    PermissionGuard.EditPermission permission) {
        if (coveringObjectIds == null || coveringObjectIds.isEmpty()) {
            return true;
        }
        if (hasCreator == null || permission == null) {
            return true;
        }
        for (String objectId : coveringObjectIds) {
            if (objectId == null || objectId.isEmpty()) {
                continue;
            }
            if (!hasCreator.test(objectId)) {
                continue; // 无归属 → 不保护
            }
            if (!permission.canEdit(objectId)) {
                return false; // 任一覆盖对象拒绝 → 拒绝
            }
        }
        return true;
    }
}
