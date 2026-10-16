package com.mtrstar.lock.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 1.4.2：MTR /mtr 车厂命令保护的启动自检。
 *
 * <p>{@link MtrDepotCommandMixin} 用 {@code require = 0} 兼容 MTR 4.0.0 / 4.0.5 不同的
 * lambda 编号；代价是如果未来 MTR 改名，注入会静默失败。这个 Mixin config plugin 在
 * {@code org.mtr.mod.Init} 被 transform 前检查实际存在的
 * {@code lambda$depotOperationFromCommand$*} 方法，并在 postApply 时记录/告警。</p>
 */
public final class MtrlockMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("mtrlock");
    private static final String MTR_INIT = "org.mtr.mod.Init";
    private static final String DEPOT_LAMBDA_22 = "lambda$depotOperationFromCommand$22";
    private static final String DEPOT_LAMBDA_23 = "lambda$depotOperationFromCommand$23";
    private static final String DEPOT_LAMBDA_25 = "lambda$depotOperationFromCommand$25";

    /** preApply 时抓到的本版本实际 lambda 名。 */
    private final Set<String> foundDepotLambdas = new LinkedHashSet<>();
    /** targetClassName 不需要 visited；用局部变量即可。 */

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!MTR_INIT.equals(targetClassName)) {
            return;
        }
        foundDepotLambdas.clear();
        if (targetClass == null || targetClass.methods == null) {
            return;
        }
        for (MethodNode method : targetClass.methods) {
            if (method == null || method.name == null) {
                continue;
            }
            if (DEPOT_LAMBDA_22.equals(method.name)
                    || DEPOT_LAMBDA_23.equals(method.name)
                    || DEPOT_LAMBDA_25.equals(method.name)) {
                foundDepotLambdas.add(method.name);
            }
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!MTR_INIT.equals(targetClassName)) {
            return;
        }
        final List<String> found = new ArrayList<>(foundDepotLambdas);
        if (found.isEmpty()) {
            LOGGER.warn("[mtrlock] MTR 车厂命令保护未生效：目标 lambda 未找到，"
                    + "可能 MTR 版本已更新，请检查 MtrDepotCommandMixin");
        } else if (found.size() < 2) {
            LOGGER.warn("[mtrlock] MTR 车厂命令保护只找到部分目标 lambda: {}，"
                    + "可能有命令路径未覆盖，请检查 MtrDepotCommandMixin", found);
        } else {
            LOGGER.info("[mtrlock] MTR 车厂命令保护已找到目标 lambda: {}", found);
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }
}
