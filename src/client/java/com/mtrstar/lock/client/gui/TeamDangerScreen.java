package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.client.ClientOwnership;
import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/** 危险操作：退出 / 解散团队，均带二次确认（1.2.3）。 */
public class TeamDangerScreen extends MtrlockGuiScreen {

    private int teamIndex;

    public TeamDangerScreen() {
        super(tr("gui.mtrlock.team.danger.title"));
    }

    private List<TeamGuiSnapshot.TeamEntry> myTeams() {
        return ClientGuiState.teamSnapshot().myTeams();
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();
        if (teams.isEmpty()) {
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                    .dimensions(cx - 100, this.height / 2, 200, 20).build());
            return;
        }
        if (teamIndex >= teams.size()) {
            teamIndex = 0;
        }
        final TeamGuiSnapshot.TeamEntry team = teams.get(teamIndex);
        addDrawableChild(ButtonWidget.builder(Text.literal("[" + team.name() + "]"), b -> {
            teamIndex = (teamIndex + 1) % teams.size();
            clearAndInit();
        }).dimensions(cx - 100, 50, 200, 20).build());

        final boolean operator = ClientOwnership.isOperator();
        final ButtonWidget leave = ButtonWidget.builder(tr("gui.mtrlock.team.danger.leave"),
                        b -> confirmLeave(team))
                .dimensions(cx - 100, 84, 200, 20).build();
        leave.active = !team.owner();
        addDrawableChild(leave);

        final ButtonWidget disband = ButtonWidget.builder(tr("gui.mtrlock.team.danger.disband"),
                        b -> confirmDisband(team))
                .dimensions(cx - 100, 110, 200, 20).build();
        disband.active = team.owner() || operator;
        addDrawableChild(disband);

        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                .dimensions(cx - 100, 140, 200, 20).build());
    }

    private void confirmLeave(TeamGuiSnapshot.TeamEntry team) {
        open(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                sendTeam(TeamGuiAction.of(TeamActionType.LEAVE, team.teamId(), null, null, null, null));
                open(new TeamGuiScreen());
            } else {
                open(new TeamDangerScreen());
            }
        }, tr("gui.mtrlock.team.danger.leave"),
                tr("gui.mtrlock.team.danger.confirmLeave", team.name())));
    }

    private void confirmDisband(TeamGuiSnapshot.TeamEntry team) {
        open(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                sendTeam(TeamGuiAction.of(TeamActionType.DISBAND, team.teamId(), null, null, null, null));
                open(new TeamGuiScreen());
            } else {
                open(new TeamDangerScreen());
            }
        }, tr("gui.mtrlock.team.danger.disband"),
                tr("gui.mtrlock.team.danger.confirmDisband", team.name())));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);
        if (myTeams().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.none"), this.width / 2, this.height / 2 - 20, 0xAAAAAA);
        }
    }
}
