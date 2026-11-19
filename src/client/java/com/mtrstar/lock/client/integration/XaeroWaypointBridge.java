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
    private static final int DATA_NULL_WARN_THRESHOLD = 5;
    private static final String LOG_TAG = "[mtrlock][xaero]";

    private static int tickCounter;
    private static String lastFingerprint;
    private static Object lastManager;
    private static boolean lastSuccess;
    private static long lastSuccessMillis;
    private static boolean disabledWarned;
    private static int dataNullRefreshCount;
    private static boolean dataNullWarned;
    private static boolean clearOriginWarned;
    private static boolean addWaypointWarned;
    private static boolean skippedSpecsWarned;

    private XaeroWaypointBridge() {
    }

    /** 由客户端 tick 事件调用；内部每 40 tick 执行一次同步。 */
    public static void tick() {
        try {
            if (!XaeroApiAdapter.isInstalled()) {
                return;
            }
            if (XaeroApiAdapter.isDisabled()) {
                if (!disabledWarned) {
                    Mtrlock.LOGGER.warn(LOG_TAG + " Xaero 集成已禁用，不再尝试注册 waypoint");
                    disabledWarned = true;
                }
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
        lastSuccessMillis = 0L;
        disabledWarned = false;
        dataNullRefreshCount = 0;
        dataNullWarned = false;
        clearOriginWarned = false;
        addWaypointWarned = false;
        skippedSpecsWarned = false;
        XaeroApiAdapter.reset();
    }

    private static void refresh() {
        try {
            MinecraftClientData data = MinecraftClientData.getInstance();
            if (data == null) {
                dataNullRefreshCount++;
                if (dataNullRefreshCount >= DATA_NULL_WARN_THRESHOLD && !dataNullWarned) {
                    Mtrlock.LOGGER.warn(LOG_TAG + " MTR 客户端数据持续未就绪，Xaero waypoint 无法注册");
                    dataNullWarned = true;
                }
                return;
            }
            dataNullRefreshCount = 0;

            Mtrlock.LOGGER.debug(LOG_TAG + " 刷新状态：lastSuccess={}, lastSuccessMillis={}",
                    lastSuccess, lastSuccessMillis);

            final int[] skippedSpecs = {0};
            List<XaeroWaypointSpec> specs = collectSpecs(data, skippedSpecs);
            if (skippedSpecs[0] > 0 && !skippedSpecsWarned) {
                Mtrlock.LOGGER.warn(LOG_TAG + " 本次刷新有 " + skippedSpecs[0]
                        + " 条 station/depot 因异常被跳过");
                skippedSpecsWarned = true;
            }
            String fingerprint = XaeroWaypointSpec.fingerprint(specs);
            Object manager = XaeroApiAdapter.currentManager();
            if (manager == null) {
                Mtrlock.LOGGER.debug(LOG_TAG + " manager 为 null，跳过本次刷新：fingerprint={}", fingerprint);
                return;
            }
            if (!XaeroWaypointSpec.shouldRebuild(fingerprint, manager, lastFingerprint, lastManager)) {
                return;
            }

            final boolean stationCleared = XaeroApiAdapter.clearOrigin(
                    manager, XaeroWaypointSpec.Kind.STATION.originPath);
            final boolean depotCleared = XaeroApiAdapter.clearOrigin(
                    manager, XaeroWaypointSpec.Kind.DEPOT.originPath);
            boolean ok = stationCleared && depotCleared;
            if (!ok) {
                if (!clearOriginWarned) {
                    final String failedOrigin = !stationCleared
                            ? XaeroWaypointSpec.Kind.STATION.originPath
                            : XaeroWaypointSpec.Kind.DEPOT.originPath;
                    Mtrlock.LOGGER.warn(LOG_TAG
                            + " clearOrigin 失败，origin=" + failedOrigin + "，本次不注册 waypoint");
                    clearOriginWarned = true;
                }
                return;
            }

            int addedCount = 0;
            for (XaeroWaypointSpec spec : specs) {
                if (!XaeroApiAdapter.addWaypoint(manager, spec.kind().originPath, spec)) {
                    if (!addWaypointWarned) {
                        Mtrlock.LOGGER.warn(LOG_TAG + " addWaypoint 失败，spec.id=" + spec.id()
                                + "，已添加数量=" + addedCount + "/" + specs.size());
                        addWaypointWarned = true;
                    }
                    ok = false;
                    break;
                }
                addedCount++;
            }

            if (ok) {
                lastFingerprint = fingerprint;
                lastManager = manager;
                lastSuccess = true;
                lastSuccessMillis = System.currentTimeMillis();
                logRefreshSuccessDiagnostics(specs.size(), addedCount, manager != null);
            }
        } catch (Throwable t) {
            Mtrlock.LOGGER.warn(LOG_TAG + " refresh 异常（静默）", t);
        }
    }

    static void logRefreshSuccessDiagnostics(int specsSize, int addedCount, boolean managerReady) {
        if (specsSize == 0) {
            Mtrlock.LOGGER.debug(LOG_TAG
                    + " specs 为空，无 station/depot 可注册：managerReady=" + managerReady);
            return;
        }
        Mtrlock.LOGGER.debug(LOG_TAG + " 重建完成: " + specsSize
                + " 个 waypoint, added=" + addedCount + ", managerReady=" + managerReady);
    }

    private static List<XaeroWaypointSpec> collectSpecs(MinecraftClientData data, int[] skippedSpecs) {
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
                    skippedSpecs[0]++;
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
                    skippedSpecs[0]++;
                    // 单条对象异常不影响其余车厂
                }
            }
        }

        return out;
    }
}
