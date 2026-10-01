package com.mtrstar.lock.client.gui;

import com.mtrstar.lock.network.payload.GuiResult;
import com.mtrstar.lock.network.payload.TeamGuiAction;
import com.mtrstar.lock.network.payload.TitleGuiAction;
import com.mtrstar.lock.team.ResultMessages;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/**
 * GUI Screen 基类（1.2.3）。
 *
 * <p>统一处理：</p>
 * <ul>
 *   <li>发包前不乐观更新：发送后进入“处理中…”，等服务端 {@code GuiResult} + 快照；</li>
 *   <li>底部状态栏展示最近一次服务端校验结果（成功绿 / 失败红）；</li>
 *   <li>{@link GuiRefreshable}：收到新数据后 {@code clearAndInit()} 重绘。</li>
 * </ul>
 */
public abstract class MtrlockGuiScreen extends Screen implements GuiRefreshable {

    private boolean pending;

    protected MtrlockGuiScreen(Text title) {
        super(title);
    }

    protected static Text tr(String key, Object... args) {
        return Text.translatable(key, args);
    }

    /** 发一次团队操作（进入处理中状态）。 */
    protected void sendTeam(TeamGuiAction action) {
        pending = true;
        ClientGuiNetworking.sendTeamAction(action);
        clearAndInit();
    }

    /** 发一次称号操作（进入处理中状态）。 */
    protected void sendTitle(TitleGuiAction action) {
        pending = true;
        ClientGuiNetworking.sendTitleAction(action);
        clearAndInit();
    }

    @Override
    public void onGuiDataChanged() {
        pending = false;
        clearAndInit();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        final GuiResult result = ClientGuiState.lastResult();
        final Text status;
        final int color;
        if (pending) {
            status = tr("gui.mtrlock.pending");
            color = 0xFFFF55;
        } else if (result != null) {
            status = tr(ResultMessages.langKey(result.code()));
            color = result.ok() ? 0x55FF55 : 0xFF5555;
        } else {
            status = null;
            color = 0;
        }
        if (status != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, this.height - 22, color);
        }
    }

    /** 打开另一个 Screen。 */
    protected void open(Screen screen) {
        if (this.client != null) {
            this.client.setScreen(screen);
        }
    }

    /** 取分页片段。 */
    protected static <T> List<T> page(List<T> list, int page, int perPage) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        final int from = Math.max(0, page * perPage);
        if (from >= list.size()) {
            return List.of();
        }
        final int to = Math.min(list.size(), from + perPage);
        return list.subList(from, to);
    }

    /** 总页数（至少 1）。 */
    protected static int pageCount(int total, int perPage) {
        if (perPage <= 0) {
            return 1;
        }
        return Math.max(1, (total + perPage - 1) / perPage);
    }
}
