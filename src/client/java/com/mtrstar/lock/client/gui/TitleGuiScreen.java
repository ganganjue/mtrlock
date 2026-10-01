package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.client.ClientOwnership;
import com.mtrstar.lock.gui.TitleActionType;
import com.mtrstar.lock.network.payload.PlayerEntry;
import com.mtrstar.lock.network.payload.TitleGuiAction;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;
import com.mtrstar.lock.team.ColorParser;
import com.mtrstar.lock.team.TitleData;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 称号系统 GUI（统一界面，仅 OP 3+；1.2.4 激活颜色）。
 *
 * <p>1.2.3 已有的页面结构不变，本版把颜色区真正接上：16 原版色网格 + HEX 输入 +
 * 最近使用 + 重置颜色 + 实时预览。所有操作仍走服务端 {@code TitleActions}，
 * 客户端只发包、不乐观更新（选色只是本地 UI 状态，保存时才发给服务端）。</p>
 */
public class TitleGuiScreen extends MtrlockGuiScreen {

    private static final int PER_PAGE = 6;
    private static final int SWATCH_SIZE = 16;
    private static final int SWATCH_GAP = 2;
    private static final int SWATCH_COLS = 8;

    private String searchText = "";
    private String selectedUuid;
    private String titleText = "";
    /** 本地选中的颜色（{@code #rrggbb}）或 null（无色）；保存时才发给服务端。 */
    private String pendingColor;
    /** 已从快照载入过编辑值的目标（避免刷新时覆盖用户输入）。 */
    private String loadedForUuid;
    private String localError;
    private int page;

    private final List<Swatch> swatches = new ArrayList<>();

    private TextFieldWidget searchField;
    private TextFieldWidget titleField;
    private TextFieldWidget hexField;

    /** 一个可点击色块。 */
    private record Swatch(int x, int y, int size, String color) {
    }

    public TitleGuiScreen() {
        super(tr("gui.mtrlock.title.title"));
    }

    private TitleGuiSnapshot snapshot() {
        return ClientGuiState.titleSnapshot();
    }

    /** 是否可编辑（服务端只会给 OP 3+ 发 OpenGui，这里再兜一层）。 */
    private boolean canEdit() {
        return ClientOwnership.isOperator();
    }

    private List<PlayerEntry> filteredPlayers() {
        final List<PlayerEntry> result = new ArrayList<>();
        final String needle = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        for (PlayerEntry player : snapshot().onlinePlayers()) {
            if (needle.isEmpty() || player.name().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(player);
            }
        }
        return result;
    }

    private String target() {
        if (selectedUuid != null && !selectedUuid.isEmpty()) {
            return selectedUuid;
        }
        return snapshot().targetUuid();
    }

    /** 选中目标的第一份快照到达后，把文本 / 颜色载入本地编辑状态。 */
    private void syncFromSnapshotIfNeeded() {
        final String t = target();
        if (t == null || t.equals(loadedForUuid)) {
            return;
        }
        final TitleGuiSnapshot snap = snapshot();
        if (!t.equals(snap.targetUuid())) {
            return;
        }
        titleText = snap.currentTitle() == null ? "" : snap.currentTitle();
        pendingColor = ColorParser.normalize(snap.currentColor());
        loadedForUuid = t;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        syncFromSnapshotIfNeeded();

        // ---------------- 左列：玩家搜索 / 在线列表 ----------------
        searchField = new TextFieldWidget(this.textRenderer, cx - 200, 34, 140, 18,
                tr("gui.mtrlock.title.search"));
        searchField.setMaxLength(16);
        searchField.setText(searchText);
        searchField.setPlaceholder(tr("gui.mtrlock.title.search"));
        addDrawableChild(searchField);
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.title.search"), b -> {
            searchText = searchField.getText();
            page = 0;
            clearAndInit();
        }).dimensions(cx - 56, 34, 56, 18).build());

        final List<PlayerEntry> players = filteredPlayers();
        final int pages = pageCount(players.size(), PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int y = 58;
        for (PlayerEntry player : page(players, page, PER_PAGE)) {
            final ButtonWidget pick = ButtonWidget.builder(Text.literal(player.name()),
                            b -> select(player.uuid()))
                    .dimensions(cx - 200, y, 150, 18).build();
            pick.active = !player.uuid().equals(target());
            addDrawableChild(pick);
            y += 20;
        }
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
                page = Math.max(0, page - 1);
                clearAndInit();
            }).dimensions(cx - 200, y + 2, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
                page = Math.min(pages - 1, page + 1);
                clearAndInit();
            }).dimensions(cx - 156, y + 2, 40, 18).build());
        }

        // ---------------- 右列：称号文本 ----------------
        titleField = new TextFieldWidget(this.textRenderer, cx + 20, 50, 170, 18,
                tr("gui.mtrlock.title.input"));
        titleField.setMaxLength(TitleData.MAX_TITLE_LENGTH);
        titleField.setText(titleText);
        titleField.setPlaceholder(tr("gui.mtrlock.title.input"));
        titleField.setChangedListener(value -> titleText = value);
        titleField.active = canEdit();
        addDrawableChild(titleField);

        // ---------------- 右列：颜色网格 + 最近使用 ----------------
        swatches.clear();
        final int sx0 = cx + 20;
        final int sy0 = 86;
        for (int i = 0; i < ColorParser.COLOR_HEX.length; i++) {
            swatches.add(new Swatch(
                    sx0 + (i % SWATCH_COLS) * (SWATCH_SIZE + SWATCH_GAP),
                    sy0 + (i / SWATCH_COLS) * (SWATCH_SIZE + SWATCH_GAP),
                    SWATCH_SIZE, ColorParser.COLOR_HEX[i]));
        }
        final List<String> recent = ClientGuiState.recentColors();
        final int recentY = sy0 + 2 * (SWATCH_SIZE + SWATCH_GAP) + 8;
        for (int i = 0; i < recent.size() && i < ClientGuiState.MAX_RECENT_COLORS; i++) {
            swatches.add(new Swatch(sx0 + i * (SWATCH_SIZE + SWATCH_GAP), recentY, SWATCH_SIZE, recent.get(i)));
        }

        // HEX 输入 + 应用
        hexField = new TextFieldWidget(this.textRenderer, cx + 20, 150, 90, 18,
                tr("gui.mtrlock.title.hex"));
        hexField.setMaxLength(7);
        hexField.setPlaceholder(tr("gui.mtrlock.title.hex"));
        hexField.active = canEdit();
        addDrawableChild(hexField);
        final ButtonWidget applyHex = ButtonWidget.builder(tr("gui.mtrlock.title.applyHex"), b -> applyHex())
                .dimensions(cx + 114, 150, 76, 18).build();
        applyHex.active = canEdit();
        addDrawableChild(applyHex);

        // 重置颜色
        final ButtonWidget reset = ButtonWidget.builder(tr("gui.mtrlock.title.resetColor"), b -> resetColor())
                .dimensions(cx + 20, 174, 170, 20).build();
        reset.active = canEdit();
        addDrawableChild(reset);

        // 保存 / 清除
        final ButtonWidget save = ButtonWidget.builder(tr("gui.mtrlock.title.save"), b -> save())
                .dimensions(cx + 20, 198, 80, 20).build();
        save.active = canEdit();
        addDrawableChild(save);
        final ButtonWidget clear = ButtonWidget.builder(tr("gui.mtrlock.title.clear"), b -> clear())
                .dimensions(cx + 104, 198, 86, 20).build();
        clear.active = canEdit();
        addDrawableChild(clear);

        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.title.cancel"), b -> this.close())
                .dimensions(cx + 20, 222, 170, 20).build());
    }

    private void select(String uuid) {
        selectedUuid = uuid;
        loadedForUuid = null;
        titleText = "";
        pendingColor = null;
        localError = null;
        sendTitle(TitleGuiAction.of(TitleActionType.REQUEST_SYNC, uuid, null, null));
    }

    private void applyHex() {
        final ColorParser.Result parsed = ColorParser.parse(hexField.getText());
        if (!parsed.valid()) {
            localError = "gui.mtrlock.title.colorInvalid";
            clearAndInit();
            return;
        }
        pendingColor = parsed.color();
        localError = null;
        ClientGuiState.rememberColor(pendingColor);
        clearAndInit();
    }

    private void save() {
        final String t = target();
        if (t == null || t.isEmpty()) {
            return;
        }
        ClientGuiState.rememberColor(pendingColor);
        sendTitle(TitleGuiAction.of(TitleActionType.SET, t, titleText, pendingColor));
    }

    private void resetColor() {
        final String t = target();
        if (t == null || t.isEmpty()) {
            return;
        }
        pendingColor = null;
        localError = null;
        sendTitle(TitleGuiAction.of(TitleActionType.RESET_COLOR, t, null, null));
    }

    private void clear() {
        final String t = target();
        if (t == null || t.isEmpty()) {
            return;
        }
        sendTitle(TitleGuiAction.of(TitleActionType.CLEAR, t, null, null));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (canEdit()) {
            for (Swatch swatch : swatches) {
                if (mouseX >= swatch.x() && mouseX < swatch.x() + swatch.size()
                        && mouseY >= swatch.y() && mouseY < swatch.y() + swatch.size()) {
                    pendingColor = swatch.color();
                    localError = null;
                    ClientGuiState.rememberColor(pendingColor);
                    clearAndInit();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 14, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.online"), cx - 200, 22, 0xCCCCCC);

        // 当前称号 + 颜色
        final String current = snapshot().currentTitle();
        final String currentColor = snapshot().currentColor();
        final String currentText = (current == null || current.isEmpty())
                ? tr("gui.mtrlock.title.none").getString()
                : current;
        context.drawTextWithShadow(this.textRenderer,
                tr("gui.mtrlock.title.current").getString() + ": " + currentText,
                cx + 20, 36, currentColor == null ? 0xFFD700 : ColorParser.rgb(currentColor));

        // 颜色区
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.color"), cx + 20, 74, 0xCCCCCC);
        for (Swatch swatch : swatches) {
            final int rgb = ColorParser.rgb(swatch.color());
            context.fill(swatch.x(), swatch.y(), swatch.x() + swatch.size(), swatch.y() + swatch.size(),
                    0xFF000000 | (rgb & 0xFFFFFF));
            final boolean selected = swatch.color().equals(pendingColor);
            context.drawBorder(swatch.x() - 1, swatch.y() - 1, swatch.size() + 2, swatch.size() + 2,
                    selected ? 0xFFFFFFFF : 0xFF333333);
        }
        final List<String> recent = ClientGuiState.recentColors();
        if (!recent.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.recent"),
                    cx + 20 + 3 * (SWATCH_SIZE + SWATCH_GAP) + 30,
                    swatchRecentLabelY(), 0xAAAAAA);
        }
        if (localError != null) {
            context.drawTextWithShadow(this.textRenderer, tr(localError), cx + 20, 168, 0xFF5555);
        }

        // 实时预览（聊天栏 / tab / 头顶）
        final String preview = titleText.isEmpty() ? tr("gui.mtrlock.title.none").getString() : titleText;
        final int previewColor = pendingColor == null ? 0xFFFF55 : ColorParser.rgb(pendingColor);
        int py = this.height - 74;
        context.drawTextWithShadow(this.textRenderer,
                tr("gui.mtrlock.title.preview"), cx - 200, py - 12, 0xCCCCCC);
        context.drawTextWithShadow(this.textRenderer,
                "[" + preview + "] 玩家名：大家好", cx - 200, py, previewColor);
        context.drawTextWithShadow(this.textRenderer,
                "tab  [" + preview + "] 玩家名", cx - 200, py + 12, previewColor);
        context.drawTextWithShadow(this.textRenderer,
                "头顶 [" + preview + "] 玩家名", cx - 200, py + 24, previewColor);
    }

    private static int swatchRecentLabelY() {
        return 86 + 2 * (SWATCH_SIZE + SWATCH_GAP) + 8 + 4;
    }
}
