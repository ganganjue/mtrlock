package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.gui.TeamActionType;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TeamGuiSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** 分享管理：按对象类型筛选 + 分享 / 取消分享，1.2.3。 */
public class TeamShareScreen extends MtrlockGuiScreen {

    private static final int PER_PAGE = 6;

    /** null = 全部；否则为 route / station / depot / platform / siding。 */
    private String filterPrefix;
    private int teamIndex;
    private int page;

    public TeamShareScreen() {
        super(tr("gui.mtrlock.team.share.title"));
    }

    private List<TeamGuiSnapshot.TeamEntry> myTeams() {
        return ClientGuiState.teamSnapshot().myTeams();
    }

    private List<String> filteredObjects() {
        final List<String> result = new ArrayList<>();
        for (String objectId : ClientGuiState.teamSnapshot().myObjectIds()) {
            if (filterPrefix == null || objectId.startsWith(filterPrefix + ":")) {
                result.add(objectId);
            }
        }
        return result;
    }

    private boolean isShared(String objectId, String teamId) {
        for (TeamGuiSnapshot.ShareEntry entry : ClientGuiState.teamSnapshot().shares()) {
            if (objectId.equals(entry.objectId()) && teamId.equals(entry.teamId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();

        // 筛选按钮
        addFilter(cx - 200, 40, tr("gui.mtrlock.team.share.all").getString(), null);
        addFilter(cx - 140, 40, tr("gui.mtrlock.team.share.route").getString(), "route");
        addFilter(cx - 80, 40, tr("gui.mtrlock.team.share.station").getString(), "station");
        addFilter(cx - 20, 40, tr("gui.mtrlock.team.share.depot").getString(), "depot");
        addFilter(cx + 40, 40, tr("gui.mtrlock.team.share.platform").getString(), "platform");
        addFilter(cx + 100, 40, tr("gui.mtrlock.team.share.siding").getString(), "siding");

        if (teams.isEmpty()) {
            addDrawableChild(ButtonWidget.builder(tr("gui.mtrlock.team.danger.cancel"), b -> open(new TeamGuiScreen()))
                    .dimensions(cx - 100, this.height - 44, 200, 20).build());
            return;
        }
        if (teamIndex >= teams.size()) {
            teamIndex = 0;
        }
        final TeamGuiSnapshot.TeamEntry team = teams.get(teamIndex);
        addDrawableChild(ButtonWidget.builder(Text.literal("[" + team.name() + "]"), b -> {
            teamIndex = (teamIndex + 1) % teams.size();
            clearAndInit();
        }).dimensions(cx - 100, 64, 200, 20).build());

        final List<String> objects = filteredObjects();
        final int pages = pageCount(objects.size(), PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int y = 90;
        for (String objectId : page(objects, page, PER_PAGE)) {
            final boolean shared = isShared(objectId, team.teamId());

            final ButtonWidget share = ButtonWidget.builder(tr("gui.mtrlock.team.share.share"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.SHARE, team.teamId(), null,
                                    null, null, objectId)))
                    .dimensions(cx + 20, y, 60, 18).build();
            share.active = !shared;
            addDrawableChild(share);

            final ButtonWidget unshare = ButtonWidget.builder(tr("gui.mtrlock.team.share.unshare"),
                            b -> sendTeam(TeamGuiAction.of(TeamActionType.UNSHARE, team.teamId(), null,
                                    null, null, objectId)))
                    .dimensions(cx + 84, y, 76, 18).build();
            unshare.active = shared;
            addDrawableChild(unshare);
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

    private void addFilter(int x, int y, String label, String prefix) {
        final ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> {
            filterPrefix = prefix;
            page = 0;
            clearAndInit();
        }).dimensions(x, y, 56, 18).build();
        button.active = !java.util.Objects.equals(filterPrefix, prefix);
        addDrawableChild(button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 14, 0xFFFFFF);

        final List<TeamGuiSnapshot.TeamEntry> teams = myTeams();
        if (teams.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.share.noTeam"), cx, this.height / 2 - 20, 0xAAAAAA);
            return;
        }
        final List<String> objects = page(filteredObjects(), page, PER_PAGE);
        if (objects.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlock.team.share.noObject"), cx - 60, 94, 0x888888);
        }
        int y = 90;
        final String teamId = teams.get(teamIndex).teamId();
        for (String objectId : objects) {
            final String mark = isShared(objectId, teamId) ? "✔ " : "";
            context.drawTextWithShadow(this.textRenderer, mark + objectId, cx - 190, y + 4, 0xCCCCCC);
            y += 20;
        }
    }
}
