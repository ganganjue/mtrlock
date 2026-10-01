package com.mtrstar.lock.team;

/**
 * 称号（自定义称呼）操作的<b>唯一实现</b>（1.2.3）。
 *
 * <p>命令层（{@code /mtrlock title ...}）与 GUI 称号界面都调用本类，保证
 * “无 OP 3+ 不可打开、不可操作”在两条路径上一致。</p>
 *
 * <p>权限规则与 1.2.0 命令完全一致：只有 OP 权限等级 ≥ 3 才能设置 / 清除；
 * 查看称号不需要权限。打开 GUI 的权限判定用 {@link #canOpen(boolean)}。</p>
 *
 * <p>纯逻辑：不依赖 Minecraft / Fabric，可纯 JVM 单测（{@link TitleData} 支持注入临时文件）。</p>
 */
public final class TitleActions {

    private final TitleData titles;

    public TitleActions(TitleData titles) {
        this.titles = titles;
    }

    private static volatile TitleActions production;

    public static TitleActions get() {
        TitleActions current = production;
        if (current == null) {
            synchronized (TitleActions.class) {
                current = production;
                if (current == null) {
                    current = new TitleActions(TitleData.getInstance());
                    production = current;
                }
            }
        }
        return current;
    }

    /** 是否允许打开称号 GUI（仅 OP 3+）。 */
    public static boolean canOpen(boolean actorIsAdmin) {
        return actorIsAdmin;
    }

    /**
     * 设置称号（OCR 3+）。
     *
     * @param actorUuid    操作者 UUID（审计用；授权只看 {@code actorIsAdmin}）
     * @param actorIsAdmin 操作者是否 OP 3+
     * @param targetUuid   目标玩家 UUID
     * @param title        新称号文本（≤ {@link TitleData#MAX_TITLE_LENGTH} 个 code point）
     */
    public ActionResult setTitle(String actorUuid, boolean actorIsAdmin, String targetUuid, String title) {
        if (!canOpen(actorIsAdmin)) {
            return ActionResult.fail(ResultCode.NEED_ADMIN);
        }
        if (targetUuid == null || targetUuid.isEmpty()) {
            return ActionResult.fail(ResultCode.TARGET_NOT_ONLINE);
        }
        return titles.setTitle(targetUuid, title)
                ? ActionResult.ok(ResultCode.TITLE_SET)
                : ActionResult.fail(ResultCode.TITLE_INVALID);
    }

    /**
     * 设置称号文本 + 颜色（仅 OP 3+）。
     *
     * <p>{@code colorInput} 为 null / 空表示<b>清除颜色</b>；GUI 的「保存」走这一条。
     * 命令 {@code /mtrlock title <玩家> <称呼>} 不带颜色时走 4 参重载（保留原颜色）。</p>
     *
     * @param actorUuid    操作者 UUID（审计用）
     * @param actorIsAdmin 操作者是否 OP 3+
     * @param targetUuid   目标玩家 UUID
     * @param title        称号文本
     * @param colorInput   颜色输入（{@code red} / {@code &a} / {@code #RRGGBB} / {@code &x...}）；null / 空 = 无色
     */
    public ActionResult setTitle(String actorUuid, boolean actorIsAdmin, String targetUuid,
                                 String title, String colorInput) {
        if (!canOpen(actorIsAdmin)) {
            return ActionResult.fail(ResultCode.NEED_ADMIN);
        }
        if (targetUuid == null || targetUuid.isEmpty()) {
            return ActionResult.fail(ResultCode.TARGET_NOT_ONLINE);
        }
        final ColorParser.Result parsed = parseColorInput(colorInput);
        if (!parsed.valid()) {
            return ActionResult.fail(ResultCode.COLOR_INVALID);
        }
        return titles.setTitle(targetUuid, title, parsed.color())
                ? ActionResult.ok(ResultCode.TITLE_SET)
                : ActionResult.fail(ResultCode.TITLE_INVALID);
    }

    /**
     * 只设置 / 清除颜色（仅 OP 3+，且目标必须已有称号）。
     *
     * <p>{@code colorInput} 为 null / 空或 {@code reset} / {@code none} 时清除颜色。</p>
     */
    public ActionResult setColor(String actorUuid, boolean actorIsAdmin, String targetUuid, String colorInput) {
        if (!canOpen(actorIsAdmin)) {
            return ActionResult.fail(ResultCode.NEED_ADMIN);
        }
        if (targetUuid == null || targetUuid.isEmpty()) {
            return ActionResult.fail(ResultCode.TARGET_NOT_ONLINE);
        }
        final ColorParser.Result parsed = parseColorInput(colorInput);
        if (!parsed.valid()) {
            return ActionResult.fail(ResultCode.COLOR_INVALID);
        }
        if (!titles.hasTitle(targetUuid)) {
            return ActionResult.fail(ResultCode.TITLE_REQUIRED);
        }
        if (parsed.color() == null) {
            titles.setColor(targetUuid, null);
            return ActionResult.ok(ResultCode.COLOR_RESET);
        }
        return titles.setColor(targetUuid, parsed.color())
                ? ActionResult.ok(ResultCode.COLOR_SET)
                : ActionResult.fail(ResultCode.COLOR_INVALID);
    }

    /** 清除颜色、保留称号文本（仅 OP 3+，且目标必须已有称号）。 */
    public ActionResult resetColor(String actorUuid, boolean actorIsAdmin, String targetUuid) {
        if (!canOpen(actorIsAdmin)) {
            return ActionResult.fail(ResultCode.NEED_ADMIN);
        }
        if (targetUuid == null || targetUuid.isEmpty()) {
            return ActionResult.fail(ResultCode.TARGET_NOT_ONLINE);
        }
        if (!titles.hasTitle(targetUuid)) {
            return ActionResult.fail(ResultCode.TITLE_REQUIRED);
        }
        titles.setColor(targetUuid, null);
        return ActionResult.ok(ResultCode.COLOR_RESET);
    }

    /**
     * 颜色输入解析：在 {@link ColorParser} 之外额外接受「清除颜色」的写法。
     *
     * <p>{@code null} / 空 / {@code reset} / {@code none} / {@code clear} / {@code off} /
     * {@code -} / {@code 无} 都表示「无色」；其它交给 {@link ColorParser}。</p>
     */
    static ColorParser.Result parseColorInput(String input) {
        if (input != null) {
            switch (input.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "reset":
                case "none":
                case "clear":
                case "off":
                case "无":
                case "-":
                    return new ColorParser.Result(true, null);
                default:
                    break;
            }
        }
        return ColorParser.parse(input);
    }

    /**
     * 清除称号（仅 OP 3+）。
     *
     * @param actorUuid    操作者 UUID（审计用）
     * @param actorIsAdmin 操作者是否 OP 3+
     * @param targetUuid   目标玩家 UUID
     */
    public ActionResult clearTitle(String actorUuid, boolean actorIsAdmin, String targetUuid) {
        if (!canOpen(actorIsAdmin)) {
            return ActionResult.fail(ResultCode.NEED_ADMIN);
        }
        if (targetUuid == null || targetUuid.isEmpty()) {
            return ActionResult.fail(ResultCode.TARGET_NOT_ONLINE);
        }
        return titles.clearTitle(targetUuid)
                ? ActionResult.ok(ResultCode.TITLE_CLEARED)
                : ActionResult.fail(ResultCode.NO_TITLE);
    }
}
