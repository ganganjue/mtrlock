package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.PlayerEntry;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 邀请成员：在线玩家列表 + 手动输入（1.2.3）。 */
public class TeamInviteScreen extends MtrlockGuiScreen {

    private static final int PER_PAGE = 6;

    private int teamIndex;
    private int page;
    private String manualText = "";

    public TeamInviteScreen() {
        super(tr("gui.mtrlock.team.invite.title"));
    }

    private List<TeamGuiSnapshot.TeamEntry> myTeams() {
        return ClientGuiState.teamSnapshot().myTeams();
    }

    @Override
    protected void init() {
        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();
        final int cx = this.width / 2;

        if (teams.isEmpty()) {
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                    .dimensions(cx - 100, this.height / 2, 200, 20).build());
            return;
        }
        if (teamIndex >= teams.size()) {
            teamIndex = 0;
        }
        final TeamGuiSnapshot.TeamEntry team = teams.get(teamIndex);

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("[" + team.name() + "]"), b -> {
                            teamIndex = (teamIndex + 1) % teams.size();
                            page = 0;
                            clearAndInit();
                        })
                .dimensions(cx - 100, 44, 200, 20).build());

        final Set<String> members = new HashSet<>();
        for (PlayerEntry member : team.members()) {
            members.add(member.uuid());
        }
        final List<PlayerEntry> candidates = new ArrayList<>();
        for (PlayerEntry online : ClientGuiState.teamSnapshot().onlinePlayers()) {
            if (!members.contains(online.uuid())) {
                candidates.add(online);
            }
        }

        final int pages = pageCount(candidates.size(), PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int y = 72;
        final List<PlayerEntry> shown = page(candidates, page, PER_PAGE);
        for (PlayerEntry candidate : shown) {
            addDrawableChild(ButtonWidget.builder(
                            tr("gui.mtrlock.team.invite.submit"), b -> invite(candidate.uuid()))
                    .dimensions(cx + 20, y, 80, 20).build());
            y += 22;
        }

        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
                page = Math.max(0, page - 1);
                clearAndInit();
            }).dimensions(cx - 100, 72 + PER_PAGE * 22 + 2, 20, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
                page = Math.min(pages - 1, page + 1);
                clearAndInit();
            }).dimensions(cx + 80, 72 + PER_PAGE * 22 + 2, 20, 20).build());
        }

        final TextFieldWidget manual = new TextFieldWidget(this.textRenderer, cx - 100,
                this.height - 70, 130, 20, tr("gui.mtrlock.team.invite.manual"));
        manual.setMaxLength(16);
        manual.setText(manualText);
        manual.setPlaceholder(tr("gui.mtrlock.team.invite.manual"));
        manual.setChangedListener(value -> manualText = value);
        addDrawableChild(manual);
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.invite.submit"), b -> inviteManual(manual.getText()))
                .dimensions(cx + 32, this.height - 70, 68, 20).build());
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                .dimensions(cx - 100, this.height - 44, 200, 20).build());
    }

    private void invite(String targetUuid) {
        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();
        if (teams.isEmpty()) {
            return;
        }
        sendTeam(TeamGuiAction.of(TeamActionType.INVITE, teams.get(teamIndex).teamId(), null,
                targetUuid, null, null));
    }

    private void inviteManual(String name) {
        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();
        if (teams.isEmpty() || name == null || name.trim().isEmpty()) {
            return;
        }
        sendTeam(TeamGuiAction.of(TeamActionType.INVITE, teams.get(teamIndex).teamId(), null,
                null, name.trim(), null));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);

        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();
        if (teams.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.invite.noTeam"), this.width / 2, this.height / 2 - 20, 0xAAAAAA);
            return;
        }
        final TeamGuiSnapshot.TeamEntry team = teams.get(teamIndex);
        final Set<String> members = new HashSet<>();
        for (PlayerEntry member : team.members()) {
            members.add(member.uuid());
        }
        final List<PlayerEntry> candidates = new ArrayList<>();
        for (PlayerEntry online : ClientGuiState.teamSnapshot().onlinePlayers()) {
            if (!members.contains(online.uuid())) {
                candidates.add(online);
            }
        }
        final List<PlayerEntry> shown = page(candidates, page, PER_PAGE);
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("gui.mtrlock.team.invite.title"), this.width / 2, 30, 0xCCCCCC);
        int y = 72;
        if (shown.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.none").getString(), this.width / 2 - 40, y + 4, 0x888888);
        }
        for (PlayerEntry candidate : shown) {
            context.drawTextWithShadow(this.textRenderer, candidate.name(), this.width / 2 - 98, y + 6, 0xCCCCCC);
            y += 22;
        }
    }
}
