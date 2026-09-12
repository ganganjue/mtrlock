package com.mtrstar.lock.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtrstar.lock.perm.PendingCreators;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mod.packet.PacketRequestResponseBase;
import org.mtr.mod.packet.PacketUpdateData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin A：在 C2S 包到达服务端时，把“发请求的玩家”与“请求里要创建的对象 id”绑起来。
 *
 * <p>注入点：{@link PacketRequestResponseBase#runServer(MinecraftServer, ServerPlayerEntity)} 的 HEAD。</p>
 *
 * <p>为什么选这里：</p>
 * <ul>
 *   <li>所有 Request/Response 型包都继承 {@code PacketRequestResponseBase}，而 {@code runServer} 是它的
 *       <b>final</b> 服务端入口，参数里直接带 {@code ServerPlayerEntity}，是整条链路里唯一同时拥有玩家身份的地方。</li>
 *   <li>创建对象要排到 {@code Simulator.tick()} 里的 {@code UpdateDataRequest.update()} 才发生（见 Mixin B），
 *       那时玩家信息已经丢了，所以必须在这里先记下来。</li>
 *   <li>只关心 {@code PacketUpdateData}（创建/更新对象的包），其它包用 instanceof 直接跳过。</li>
 * </ul>
 *
 * <p>id 从哪来：{@code PacketRequestResponseBase} 有一个 {@code private final String content}，
 * 它是客户端发上来的 {@code UpdateDataRequest} 的 JSON。反编译确认字段名就是
 * {@code "stations"} / {@code "routes"} / {@code "depots"}，每个元素都是对象，
 * 且 {@code NameColorDataBaseSchema.serializeData()} 会写 {@code "id": <long>}。
 * 这里只提前取 id，不去解析成完整对象。</p>
 *
 * <p>本 Mixin 只做记录，不做任何权限判断 / 拦截。</p>
 */
@Mixin(value = PacketRequestResponseBase.class, remap = false)
public abstract class PacketRequestResponseBaseMixin {

    /**
     * 客户端发来的请求 JSON（由 {@code PacketRequestResponseBase(String)} 构造时写入）。
     * 用 {@code @Shadow} 直接读私有字段，不需要反射。
     */
    @Shadow
    @Final
    private String content;

    /**
     * 服务端收到 C2S 包时调用。HEAD 注入：在 MTR 自己处理（入队）之前先记录玩家 ↔ id。
     *
     * @param server 服务端（本方法用不到，但必须和原方法签名一致）
     * @param player 发请求的玩家，来自 {@code runServer} 的参数
     * @param ci     Mixin 回调
     */
    @Inject(
            method = "runServer(Lorg/mtr/mapping/holder/MinecraftServer;Lorg/mtr/mapping/holder/ServerPlayerEntity;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void mtrlock$capturePendingCreator(MinecraftServer server, ServerPlayerEntity player, CallbackInfo ci) {
        // 只处理“更新/创建数据”包，其它 Request/Response 包（RequestData、DeleteData、OpenDashboard…）直接跳过。
        if (!((Object) this instanceof PacketUpdateData)) {
            return;
        }
        if (player == null || content == null || content.isEmpty()) {
            return;
        }

        try {
            final JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            final String uuid = player.getUuidAsString();

            // 反编译 UpdateDataRequestSchema 确认的字段名：stations / routes / depots
            captureIds(root, "stations", uuid);
            captureIds(root, "routes", uuid);
            captureIds(root, "depots", uuid);

            // 顺手清掉过期条目，避免长期运行内存增长（正常一个 tick 内就会被 poll 掉）。
            PendingCreators.pruneExpired();
        } catch (Exception e) {
            // 记录失败绝不能影响 MTR 的正常创建流程：吞掉异常（可选：打到日志）。
        }
    }

    /** 从 root[key] 数组中取出每个对象的 "id"（long），登记到 PendingCreators。 */
    private static void captureIds(JsonObject root, String key, String uuid) {
        final JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return;
        }

        final JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                continue;
            }
            final JsonElement idElement = item.getAsJsonObject().get("id");
            if (idElement == null || !idElement.isJsonPrimitive()) {
                continue;
            }
            try {
                PendingCreators.put(idElement.getAsLong(), uuid);
            } catch (NumberFormatException ignored) {
                // 单项格式异常不影响其它对象
            }
        }
    }
}
