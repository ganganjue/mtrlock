package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** 申请加入团队（手动输入团队名，1.2.3）。 */
public class TeamJoinScreen extends MtrlockGuiScreen {

    private TextFieldWidget nameField;

    public TeamJoinScreen() {
        super(tr("gui.mtrlock.team.apply.title"));
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        nameField = new TextFieldWidget(this.textRenderer, cx - 100, this.height / 2 - 24, 200, 20,
                tr("gui.mtrlock.team.apply.name"));
        nameField.setMaxLength(32);
        nameField.setPlaceholder(tr("gui.mtrlock.team.apply.name"));
        addDrawableChild(nameField);
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.apply.submit"), b -> submit())
                .dimensions(cx - 100, this.height / 2 + 4, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                .dimensions(cx + 5, this.height / 2 + 4, 95, 20).build());
    }

    private void submit() {
        sendTeam(TeamGuiAction.of(TeamActionType.APPLY, null, nameField.getText(), null, null, null));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 34, 0xFFFFFF);
    }
}
