package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.client.ClientOwnership;
import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.PlayerEntry;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** 成员管理：踢出 / 转让队长（仅队长；OP 3+ 兜底可管理全部团队），1.2.3。 */
public class TeamMembersScreen extends MtrlockGuiScreen {

    private static final int PER_PAGE = 6;

    private int teamIndex;
    private int page;

    public TeamMembersScreen() {
        super(tr("gui.mtrlock.team.members.title"));
    }

    private List<TeamGuiSnapshot.TeamEntry> manageableTeams() {
        final List<TeamGuiSnapshot.TeamEntry> result = new ArrayList<>();
        final boolean operator = ClientOwnership.isOperator();
        for (TeamGuiSnapshot.TeamEntry team : ClientGuiState.teamSnapshot().myTeams()) {
            if (team.owner() || operator) {
                result.add(team);
            }
        }
        return result;
    }

    private String selfUuid() {
        return this.client != null && this.client.player != null ? this.client.player.getUuidAsString() : "";
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final List<TeamGuiSnapshot.TeamEntry> teams = manageableTeams();
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
            page = 0;
            clearAndInit();
        }).dimensions(cx - 100, 44, 200, 20).build());

        final List<PlayerEntry> members = team.members();
        final int pages = pageCount(members.size(), PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        final String self = selfUuid();
        int y = 72;
        for (PlayerEntry member : page(members, page, PER_PAGE)) {
            final boolean isOwner = member.uuid().equals(team.ownerUuid());
            final ButtonWidget kick = ButtonWidget.builder(tr("gui.mtrlock.team.members.kick"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.KICK, team.teamId(), null,
                                    member.uuid(), null, null)))
                    .dimensions(cx + 30, y, 60, 18).build();
            kick.active = !isOwner && !member.uuid().equals(self);
            addDrawableChild(kick);

            final ButtonWidget transfer = ButtonWidget.builder(tr("gui.mtrlock.team.members.transfer"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.TRANSFER, team.teamId(), null,
                                    member.uuid(), null, null)))
                    .dimensions(cx + 96, y, 90, 18).build();
            transfer.active = !isOwner;
            addDrawableChild(transfer);
            y += 20;
        }
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
                page = Math.max(0, page - 1);
                clearAndInit();
            }).dimensions(cx - 100, this.height - 70, 40, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
                page = Math.min(pages - 1, page + 1);
                clearAndInit();
            }).dimensions(cx + 60, this.height - 70, 40, 18).build());
        }
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                .dimensions(cx - 100, this.height - 44, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 14, 0xFFFFFF);
        final List<TeamGuiSnapshot.TeamEntry> teams = manageableTeams();
        if (teams.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.members.empty"), cx, this.height / 2 - 20, 0xAAAAAA);
            return;
        }
        final TeamGuiSnapshot.TeamEntry team = teams.get(teamIndex);
        final List<PlayerEntry> members = page(team.members(), page, PER_PAGE);
        if (members.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.members.empty"), cx, 76, 0x888888);
        }
        int y = 72;
        for (PlayerEntry member : members) {
            final String ownerTag = member.uuid().equals(team.ownerUuid())
                    ? tr("gui.mtrlock.team.ownerTag").getString() : "";
            context.drawTextWithShadow(this.textRenderer, member.name() + ownerTag, cx - 98, y + 4, 0xCCCCCC);
            y += 20;
        }
    }
}
