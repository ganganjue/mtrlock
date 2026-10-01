package com.mtrstar.lock.team;

import java.util.Objects;

/**
 * 团队 / 分享 / 称号操作的统一返回值（1.2.3）。
 *
 * <p>只携带“成功与否 + {@link ResultCode}”，不携带玩家可见文案——文案由调用方决定：
 * 命令层用 {@link ResultMessages#zh(ResultCode)} 保持与旧版一致的中文提示；
 * GUI 层用 {@link ResultMessages#langKey(ResultCode)} 走 lang 文件。</p>
 *
 * <p>纯数据 record，不依赖 Minecraft / Fabric，可纯 JVM 单测。</p>
 *
 * @param ok   操作是否成功
 * @param code 结果码（永不为 null）
 */
public record ActionResult(boolean ok, ResultCode code) {

    public ActionResult {
        Objects.requireNonNull(code, "code");
    }

    /** 成功结果。 */
    public static ActionResult ok(ResultCode code) {
        return new ActionResult(true, code);
    }

    /** 失败结果。 */
    public static ActionResult fail(ResultCode code) {
        return new ActionResult(false, code);
    }
}
