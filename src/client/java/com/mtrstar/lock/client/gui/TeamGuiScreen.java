package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 团队系统 GUI 主菜单（1.2.3）。
 *
 * <p>入口：{@code /mtrlock gui} 或 {@code /mtrlock gui team}。
 * 只展示服务端下发的快照（我的团队 / 队长 / 成员数 / 分享对象数），
 * 具体操作在子页面，全部由服务端校验。</p>
 */
public class TeamGuiScreen extends MtrlockGuiScreen {

    public TeamGuiScreen() {
        super(tr("gui.mtrlock.team.title"));
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        int y = this.height / 2 - 48;
        y = add(cx, y, tr("gui.mtrlock.team.hub.create").getString(), new TeamCreateScreen());
        y = add(cx, y, tr("gui.mtrlock.team.hub.apply").getString(), new TeamJoinScreen());
        y = add(cx, y, tr("gui.mtrlock.team.hub.invite").getString(), new TeamInviteScreen());
        y = add(cx, y, tr("gui.mtrlock.team.hub.pending").getString(), new TeamPendingScreen());
        y = add(cx, y, tr("gui.mtrlock.team.hub.members").getString(), new TeamMembersScreen());
        y = add(cx, y, tr("gui.mtrlock.team.hub.share").getString(), new TeamShareScreen());
        y = add(cx, y, tr("gui.mtrlock.team.hub.danger").getString(), new TeamDangerScreen());
        addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.hub.close"), b -> this.close())
                .dimensions(cx - 100, y, 200, 20).build());
    }

    private int add(int cx, int y, String label, Screen target) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> open(target))
                .dimensions(cx - 100, y, 200, 20).build());
        return y + 22;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final List<TeamGuiSnapshot.TeamEntry> teams = ClientGuiState.teamSnapshot().myTeams();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);

        final String header = tr("gui.mtrlock.team.hub.myTeams").getString()
                + " (" + teams.size() + "/3)";
        context.drawCenteredTextWithShadow(this.textRenderer, header, this.width / 2, 30, 0xCCCCCC);
        int y = 44;
        if (teams.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.none").getString(), this.width / 2, y, 0x888888);
            return;
        }
        for (TeamGuiSnapshot.TeamEntry team : teams) {
            final String ownerTag = team.owner() ? tr("gui.mtrlock.team.ownerTag").getString() : "";
            final String line = "[" + team.name() + "]" + ownerTag + "  "
                    + tr("gui.mtrlock.team.memberCount", team.memberCount()).getString() + "  "
                    + tr("gui.mtrlock.team.shareCount", team.shareCount()).getString();
            context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, y, 0xAAAAAA);
            y += 12;
        }
    }
}
