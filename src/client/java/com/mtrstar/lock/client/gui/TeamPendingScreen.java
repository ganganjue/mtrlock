package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.List;

/** 待处理：收到的申请（批准 / 拒绝）与收到的邀请（接受 / 拒绝），1.2.3。 */
public class TeamPendingScreen extends MtrlockGuiScreen {

    private static final int PER_PAGE = 5;

    private int appPage;
    private int invPage;

    public TeamPendingScreen() {
        super(tr("gui.mtrlock.team.pending.title"));
    }

    @Override
    protected void init() {
        final List<TeamGuiSnapshot.PendingEntry> applications = ClientGuiState.teamSnapshot().applications();
        final List<TeamGuiSnapshot.PendingEntry> invitations = ClientGuiState.teamSnapshot().invitations();
        final int leftX = this.width / 2 - 200;
        final int rightX = this.width / 2 + 10;

        int y = 56;
        int appPages = pageCount(applications.size(), PER_PAGE);
        if (appPage >= appPages) {
            appPage = appPages - 1;
        }
        for (TeamGuiSnapshot.PendingEntry entry : page(applications, appPage, PER_PAGE)) {
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.pending.approve"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.APPROVE, entry.teamId(), null,
                                    entry.playerUuid(), null, null)))
                    .dimensions(leftX + 150, y, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.pending.deny"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.DENY, entry.teamId(), null,
                                    entry.playerUuid(), null, null)))
                    .dimensions(leftX + 194, y, 40, 18).build());
            y += 20;
        }
        if (appPages > 1) {
            addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.literal("<"), b -> {
                appPage = Math.max(0, appPage - 1);
                clearAndInit();
            }).dimensions(leftX + 150, y + 2, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.literal(">"), b -> {
                appPage = Math.min(appPages - 1, appPage + 1);
                clearAndInit();
            }).dimensions(leftX + 194, y + 2, 40, 18).build());
        }

        y = 56;
        int invPages = pageCount(invitations.size(), PER_PAGE);
        if (invPage >= invPages) {
            invPage = invPages - 1;
        }
        for (TeamGuiSnapshot.PendingEntry entry : page(invitations, invPage, PER_PAGE)) {
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.pending.accept"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.ACCEPT, entry.teamId(), null, null, null, null)))
                    .dimensions(rightX + 150, y, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.pending.decline"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.DECLINE, entry.teamId(), null, null, null, null)))
                    .dimensions(rightX + 194, y, 40, 18).build());
            y += 20;
        }
        if (invPages > 1) {
            addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.literal("<"), b -> {
                invPage = Math.max(0, invPage - 1);
                clearAndInit();
            }).dimensions(rightX + 150, y + 2, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(net.minecraft.text.Text.literal(">"), b -> {
                invPage = Math.min(invPages - 1, invPage + 1);
                clearAndInit();
            }).dimensions(rightX + 194, y + 2, 40, 18).build());
        }

        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                .dimensions(this.width / 2 - 100, this.height - 44, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final List<TeamGuiSnapshot.PendingEntry> applications = ClientGuiState.teamSnapshot().applications();
        final List<TeamGuiSnapshot.PendingEntry> invitations = ClientGuiState.teamSnapshot().invitations();
        final int leftX = this.width / 2 - 200;
        final int rightX = this.width / 2 + 10;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.team.pending.applications"),
                leftX, 40, 0xCCCCCC);
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.team.pending.invitations"),
                rightX, 40, 0xCCCCCC);

        int y = 56;
        final List<TeamGuiSnapshot.PendingEntry> shownApps = page(applications, appPage, PER_PAGE);
        if (shownApps.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.team.pending.empty"),
                    leftX, y + 4, 0x888888);
        }
        for (TeamGuiSnapshot.PendingEntry entry : shownApps) {
            context.drawTextWithShadow(this.textRenderer,
                    entry.playerName() + " → [" + entry.teamName() + "]", leftX, y + 4, 0xAAAAAA);
            y += 20;
        }

        y = 56;
        final List<TeamGuiSnapshot.PendingEntry> shownInvs = page(invitations, invPage, PER_PAGE);
        if (shownInvs.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlock.team.pending.empty"),
                    rightX, y + 4, 0x888888);
        }
        for (TeamGuiSnapshot.PendingEntry entry : shownInvs) {
            context.drawTextWithShadow(this.textRenderer,
                    "[" + entry.teamName() + "] ← " + entry.playerName(), rightX, y + 4, 0xAAAAAA);
            y += 20;
        }
    }
}
