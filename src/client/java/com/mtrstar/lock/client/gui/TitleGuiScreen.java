package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.gui.TitleActionType;
import com.mtrstar.lock.network.payload.PlayerEntry;
import com.mtrstar.lock.network.payload.TitleGuiAction;
import com.mtrstar.lock.network.payload.TitleGuiSnapshot;
import com.mtrstar.lock.team.TitleData;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 称号系统 GUI（统一界面，仅 OP 3+，1.2.3）。
 *
 * <p>本版实际生效的是<b>纯文本称号</b>的设置与清除；颜色区（16 原版色 + HEX + 最近使用）
 * 按要求置灰并标注“1.2.4 开放”，不参与发包。</p>
 */
public class TitleGuiScreen extends MtrlockGuiScreen {

    private static final int PER_PAGE = 6;

    /** 16 原版染料色（ARGB，仅展示，颜色本版不生效）。 */
    private static final int[] DYE_COLORS = {
            0xFFFFFF, 0xD87F33, 0xB468D8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C,
            0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919
    };

    private String searchText = "";
    private String selectedUuid;
    private String titleText = "";
    private int page;
    private TextFieldWidget searchField;
    private TextFieldWidget titleField;

    public TitleGuiScreen() {
        super(tr("gui.mtrlock.title.title"));
    }

    private TitleGuiSnapshot snapshot() {
        return ClientGuiState.titleSnapshot();
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

    @Override
    protected void init() {
        final int cx = this.width / 2;

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
            final ButtonWidget pick = ButtonWidget.builder(net.minecraft.text.Text.literal(player.name()),
                            b -> select(player.uuid()))
                    .dimensions(cx - 200, y, 150, 18).build();
            pick.active = !player.uuid().equals(target());
            addDrawableChild(pick);
            y += 20;
        }
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.literal("<"), b -> {
                page = Math.max(0, page - 1);
                clearAndInit();
            }).dimensions(cx - 200, y + 2, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.literal(">"), b -> {
                page = Math.min(pages - 1, page + 1);
                clearAndInit();
            }).dimensions(cx - 156, y + 2, 40, 18).build());
        }

        // 称号文本输入（本版唯一生效的编辑项）
        titleField = new TextFieldWidget(this.textRenderer, cx + 20, 60, 170, 18,
                tr("gui.mtrlock.title.input"));
        titleField.setMaxLength(TitleData.MAX_TITLE_LENGTH);
        titleField.setText(titleText);
        titleField.setPlaceholder(tr("gui.mtrlock.title.input"));
        titleField.setChangedListener(value -> titleText = value);
        addDrawableChild(titleField);

        // 颜色区：HEX 输入框置灰不可编辑，16 原版色在 render 里画成灰度块
        final TextFieldWidget hex = new TextFieldWidget(this.textRenderer, cx + 20, 128, 90, 18,
                tr("gui.mtrlock.title.color"));
        hex.setMaxLength(7);
        hex.setText("#RRGGBB");
        hex.setEditable(false);
        hex.active = false;
        addDrawableChild(hex);

        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.title.save"), b -> save())
                .dimensions(cx + 20, 176, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.title.clear"), b -> clear())
                .dimensions(cx + 104, 176, 86, 20).build());
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.title.cancel"), b -> this.close())
                .dimensions(cx + 20, 200, 170, 20).build());
    }

    private void select(String uuid) {
        selectedUuid = uuid;
        titleText = "";
        sendTitle(TitleGuiAction.of(TitleActionType.REQUEST_SYNC, uuid, null));
    }

    private void save() {
        final String t = target();
        if (t == null || t.isEmpty()) {
            return;
        }
        sendTitle(TitleGuiAction.of(TitleActionType.SET, t, titleText));
    }

    private void clear() {
        final String t = target();
        if (t == null || t.isEmpty()) {
            return;
        }
        sendTitle(TitleGuiAction.of(TitleActionType.CLEAR, t, null));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 14, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.online"), cx - 200, 22, 0xCCCCCC);

        final String current = snapshot().currentTitle();
        context.drawTextWithShadow(this.textRenderer,
                tr("gui.mtrlock.title.current").getString() + ": "
                        + (current == null || current.isEmpty() ? tr("gui.mtrlock.title.none").getString() : current),
                cx + 20, 44, 0xFFD700);

        // 颜色区（置灰，本版不生效）
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.color"), cx + 20, 96, 0x888888);
        int swatchX = cx + 20;
        for (int color : DYE_COLORS) {
            context.fill(swatchX, 108, swatchX + 8, 116, 0x66000000 | (color & 0xFFFFFF));
            swatchX += 10;
        }
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.colorDisabled"), cx + 20, 118, 0x777777);
        context.drawTextWithShadow(this.textRenderer,
                tr("gui.mtrlock.title.recent").getString() + " — " + tr("gui.mtrlock.title.none").getString(),
                cx + 116, 132, 0x777777);

        // 预览（本版仅文本）
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.title.preview"), cx + 20, 162, 0xCCCCCC);
        context.drawTextWithShadow(this.textRenderer, "[" + titleText + "]", cx + 80, 162, 0x55FF55);
    }
}
