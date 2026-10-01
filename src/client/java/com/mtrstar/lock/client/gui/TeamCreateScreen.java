package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.team.Team;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** 创建团队（支持中文团队名，1.2.3）。 */
public class TeamCreateScreen extends MtrlockGuiScreen {

    private TextFieldWidget nameField;

    public TeamCreateScreen() {
        super(tr("gui.mtrlock.team.create.title"));
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        nameField = new TextFieldWidget(this.textRenderer, cx - 100, this.height / 2 - 24, 200, 20,
                tr("gui.mtrlock.team.create.name"));
        nameField.setMaxLength(Team.MAX_NAME_LENGTH);
        nameField.setPlaceholder(tr("gui.mtrlock.team.create.name"));
        addDrawableChild(nameField);
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.create.submit"), b -> submit())
                .dimensions(cx - 100, this.height / 2 + 4, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                .dimensions(cx + 5, this.height / 2 + 4, 95, 20).build());
    }

    private void submit() {
        sendTeam(TeamGuiAction.of(TeamActionType.CREATE, null, nameField.getText(), null, null, null));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 34, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("gui.mtrlock.team.create.hint"), this.width / 2, 50, 0xAAAAAA);
    }
}
