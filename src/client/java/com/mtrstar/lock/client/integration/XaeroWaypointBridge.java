package com.mtrstar.lock.client.integration;

import com.mtrstar.lock.Mtrlock;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.List;

/**
 * 周期性地把 MTR 客户端数据中的车站 / 车厂同步为 Xaero 第三方 waypoint。
 *
 * <p>每 {@value #REFRESH_INTERVAL_TICKS} tick 刷新一次；只有指纹或 Xaero manager 实例变化时
 * 才清理旧 origin 并全量重建。所有异常均在桥内静默处理，不影响游戏本体。</p>
 */
public final class XaeroWaypointBridge {

    private static final int REFRESH_INTERVAL_TICKS = 40;
    private static final String LOG_TAG = "[mtrlock][xaero]";

    private static int tickCounter;
    private static String lastFingerprint;
    private static Object lastManager;
    private static boolean lastSuccess;

    private XaeroWaypointBridge() {
    }

    /** 由客户端 tick 事件调用；内部每 40 tick 执行一次同步。 */
    public static void tick() {
        try {
            if (XaeroReflection.isDisabled()) {
                return;
            }
            tickCounter++;
            if (tickCounter % REFRESH_INTERVAL_TICKS != 0) {
                return;
            }
            refresh();
        } catch (Throwable t) {
            Mtrlock.LOGGER.warn(LOG_TAG + " tick 异常（静默）", t);
        }
    }

    /** 断线重连时清除本次连接的状态，并允许 Xaero 反射层重新探测。 */
    public static void reset() {
        tickCounter = 0;
        lastFingerprint = null;
        lastManager = null;
        lastSuccess = false;
        XaeroReflection.reset();
    }

    private static void refresh() {
        try {
            MinecraftClientData data = MinecraftClientData.getInstance();
            if (data == null) {
                return;
            }

            List<XaeroWaypointSpec> specs = collectSpecs(data);
            String fingerprint = XaeroWaypointSpec.fingerprint(specs);
            Object manager = XaeroReflection.currentManager();
            if (!XaeroWaypointSpec.shouldRebuild(fingerprint, manager, lastFingerprint, lastManager)) {
                return;
            }

            boolean ok = true;
            ok &= XaeroReflection.clearOrigin(
                    manager, XaeroWaypointSpec.Kind.STATION.originPath);
            ok &= XaeroReflection.clearOrigin(
                    manager, XaeroWaypointSpec.Kind.DEPOT.originPath);
            if (!ok) {
                return;
            }

            for (XaeroWaypointSpec spec : specs) {
                if (!XaeroReflection.addWaypoint(manager, spec.kind().originPath, spec)) {
                    ok = false;
                    break;
                }
            }

            if (ok) {
                lastFingerprint = fingerprint;
                lastManager = manager;
                lastSuccess = true;
                if (Mtrlock.LOGGER.isDebugEnabled()) {
                    Mtrlock.LOGGER.debug(LOG_TAG + " 重建完成: " + specs.size() + " 个 waypoint");
                }
            }
        } catch (Throwable t) {
            Mtrlock.LOGGER.warn(LOG_TAG + " refresh 异常（静默）", t);
        }
    }

    private static List<XaeroWaypointSpec> collectSpecs(MinecraftClientData data) {
        List<XaeroWaypointSpec> out = new ArrayList<>();

        if (data.stations != null) {
            for (Station station : data.stations) {
                if (station == null) {
                    continue;
                }
                try {
                    boolean valid = station.isValid();
                    if (!valid) {
                        continue;
                    }
                    Position center = station.getCenter();
                    long x = center == null ? Long.MIN_VALUE : center.getX();
                    long z = center == null ? Long.MIN_VALUE : center.getZ();
                    XaeroWaypointSpec.tryOf(
                                    XaeroWaypointSpec.Kind.STATION,
                                    station.getHexId(),
                                    station.getName(),
                                    valid,
                                    x,
                                    z)
                            .ifPresent(out::add);
                } catch (Throwable ignored) {
                    // 单条对象异常不影响其余车站
                }
            }
        }

        if (data.depots != null) {
            for (Depot depot : data.depots) {
                if (depot == null) {
                    continue;
                }
                try {
                    boolean valid = depot.isValid();
                    if (!valid) {
                        continue;
                    }
                    Position center = depot.getCenter();
                    long x = center == null ? Long.MIN_VALUE : center.getX();
                    long z = center == null ? Long.MIN_VALUE : center.getZ();
                    XaeroWaypointSpec.tryOf(
                                    XaeroWaypointSpec.Kind.DEPOT,
                                    depot.getHexId(),
                                    depot.getName(),
                                    valid,
                                    x,
                                    z)
                            .ifPresent(out::add);
                } catch (Throwable ignored) {
                    // 单条对象异常不影响其余车厂
                }
            }
        }

        return out;
    }
}
