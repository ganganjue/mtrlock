package com.mtrstar.lock.client.integration;

import com.mtrstar.lock.Mtrlock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.MinimapWorldManager;
import xaero.hud.minimap.world.container.MinimapWorldContainer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Xaero 官方第三方 waypoint API 适配层（1.5.3）。
 *
 * <p>直接引用 Xaero 26.6.0 的公开 API，不再使用反射。Xaero 未安装时静默跳过；
 * 已安装但 API 未就绪或调用失败时只 WARN 一次。</p>
 *
 * <p>本适配器依赖 Xaero's Minimap 的 {@code xaero.hud.minimap} 包。单独安装
 * Xaero's World Map 不支持第三方 waypoint 集成，此时 {@link #isInstalled()} 返回
 * false，集成静默跳过。</p>
 */
public final class XaeroApiAdapter {

    private static final String LOG_TAG = "[mtrlock][xaero]";
    private static final String IDENTIFIER_NAMESPACE = "mtrlock";

    /**
     * 官方 Waypoint(int, int, int, String, String, int) 构造器的颜色索引。
     *
     * <p>colorId 6 / 11 来自 Xaero 26.6.0 WaypointColor.ordinal()（GOLD=6、AQUA=11）。
     * Waypoint(int colorId) 构造器内部走 WaypointColor.fromIndex(int)，两者映射是否
     * 完全一致需 in-game 校准。若显示颜色不符，查 fromIndex 的实际映射后修正。</p>
     */
    private static final int STATION_COLOR_ID = 6;
    private static final int DEPOT_COLOR_ID = 11;

    private static volatile BooleanSupplier modLoadedChecker = XaeroApiAdapter::detectXaeroInstalled;
    private static volatile Backend backendOverride;
    private static volatile Backend officialBackend;
    private static volatile boolean disabled;
    private static volatile boolean initWarned;
    private static volatile boolean managerNullWarned;
    private static final Set<String> warnedOperations = ConcurrentHashMap.newKeySet();

    private XaeroApiAdapter() {
    }

    /** 是否安装 Xaero's Minimap；异常按未安装处理。 */
    public static boolean isInstalled() {
        try {
            final BooleanSupplier checker = modLoadedChecker;
            return checker != null && checker.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isDisabled() {
        return disabled;
    }

    /** 当前 Xaero 第三方 waypoint manager；未安装、世界未就绪或 API 失败时返回 null。 */
    public static Object currentManager() {
        if (disabled) {
            return null;
        }
        final Backend backend = backend();
        if (backend == null) {
            return null;
        }
        try {
            final Object manager = backend.currentManager();
            if (manager == null && !managerNullWarned) {
                Mtrlock.LOGGER.warn(LOG_TAG
                        + " Xaero 已安装但当前第三方 waypoint manager 为 null，可能是世界尚未加载或 API 不兼容");
                managerNullWarned = true;
            }
            return manager;
        } catch (LinkageError e) {
            disabled = true;
            if (!initWarned) {
                Mtrlock.LOGGER.warn(LOG_TAG + " Xaero 官方 API 类加载失败，禁用集成", e);
                initWarned = true;
            }
            return null;
        } catch (RuntimeException e) {
            warnOperationOnce("currentManager", "获取 Xaero 第三方 waypoint manager 失败", e);
            return null;
        }
    }

    /** 清除指定 origin 下的所有第三方 waypoint。 */
    public static boolean clearOrigin(Object manager, String originPath) {
        if (disabled || manager == null || originPath == null) {
            return false;
        }
        final Backend backend = backend();
        if (backend == null) {
            return false;
        }
        try {
            return backend.clearOrigin(manager, originPath);
        } catch (LinkageError | RuntimeException e) {
            warnOperationOnce("clearOrigin", "清除 Xaero waypoint origin 失败: " + originPath, e);
            return false;
        }
    }

    /** 向指定 origin 添加一个第三方 waypoint。 */
    public static boolean addWaypoint(Object manager, String originPath, XaeroWaypointSpec spec) {
        if (disabled || manager == null || originPath == null || spec == null) {
            return false;
        }
        final Backend backend = backend();
        if (backend == null) {
            return false;
        }
        try {
            return backend.addWaypoint(manager, originPath, spec);
        } catch (LinkageError | RuntimeException e) {
            warnOperationOnce("addWaypoint", "添加 Xaero waypoint 失败: " + spec.id(), e);
            return false;
        }
    }

    /** 断线重连后清除诊断限频状态并允许重新探测 Xaero API。 */
    public static synchronized void reset() {
        disabled = false;
        initWarned = false;
        managerNullWarned = false;
        warnedOperations.clear();
        officialBackend = null;
    }

    private static Backend backend() {
        if (!isInstalled()) {
            return null;
        }
        final Backend override = backendOverride;
        if (override != null) {
            return override;
        }
        final Backend current = officialBackend;
        if (current != null) {
            return current;
        }
        synchronized (XaeroApiAdapter.class) {
            if (!isInstalled()) {
                return null;
            }
            if (officialBackend != null) {
                return officialBackend;
            }
            try {
                officialBackend = new OfficialBackend();
            } catch (LinkageError | RuntimeException e) {
                disabled = true;
                if (!initWarned) {
                    Mtrlock.LOGGER.warn(LOG_TAG
                            + " Xaero 已安装但官方第三方 waypoint API 初始化失败，可能是版本不兼容", e);
                    initWarned = true;
                }
                return null;
            }
            return officialBackend;
        }
    }

    private static void warnOperationOnce(String operation, String message, Throwable cause) {
        if (warnedOperations.add(operation)) {
            Mtrlock.LOGGER.warn(LOG_TAG + " " + message, cause);
        }
    }

    private static boolean detectXaeroInstalled() {
        try {
            return FabricLoader.getInstance().isModLoaded("xaerominimap");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 测试 seam：生产路径仍由 FabricLoader + OfficialBackend 驱动。 */
    static void setModLoadedCheckerForTesting(BooleanSupplier checker) {
        modLoadedChecker = checker != null ? checker : XaeroApiAdapter::detectXaeroInstalled;
    }

    /** 测试 seam：替换官方 API 后端，避免单元测试加载 Xaero 运行期类。 */
    static void setBackendForTesting(Backend backend) {
        backendOverride = backend;
    }

    /** 便于测试替换官方 API 后端的最小接口。 */
    interface Backend {
        Object currentManager();

        boolean clearOrigin(Object manager, String originPath);

        boolean addWaypoint(Object manager, String originPath, XaeroWaypointSpec spec);
    }

    /**
     * 直接 import 官方 API 的生产实现；仅在 Xaero 已安装且未被测试替换时加载。
     *
     * <p>OfficialBackend 的方法签名不暴露任何 Xaero 类型，{@code new OfficialBackend()}
     * 不会触发 Xaero 类解析；Xaero 类型只在 currentManager / clearOrigin / addWaypoint
     * 方法体首次执行时由 JVM 懒加载。未安装 Xaero 时由 {@link #isInstalled()} 提前拦截，
     * 不会走到这里。这是 JVM 懒解析的实现行为，不是 Java 规范保证，若未来 JVM 或
     * Xaero 打包方式变化需重新评估。</p>
     */
    private static final class OfficialBackend implements Backend {

        @Override
        public Object currentManager() {
            final MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                return null;
            }
            final MinimapWorldManager worldManager = session.getWorldManager();
            if (worldManager == null) {
                return null;
            }
            final MinimapWorld world = worldManager.getCurrentWorld();
            if (world == null) {
                return null;
            }
            final MinimapWorldContainer container = world.getContainer();
            if (container == null) {
                return null;
            }
            return container.getThirdPartyWaypointManager();
        }

        @Override
        public boolean clearOrigin(Object manager, String originPath) {
            if (!(manager instanceof ThirdPartyWaypointManager thirdPartyManager)) {
                return false;
            }
            thirdPartyManager.clearOrigin(identifier(originPath));
            return true;
        }

        @Override
        public boolean addWaypoint(Object manager, String originPath, XaeroWaypointSpec spec) {
            if (!(manager instanceof ThirdPartyWaypointManager thirdPartyManager)) {
                return false;
            }
            final ThirdPartyWaypoints waypoints = thirdPartyManager.get(identifier(originPath));
            if (waypoints == null) {
                return false;
            }
            final Waypoint waypoint = new Waypoint(
                    spec.x(),
                    XaeroWaypointSpec.WAYPOINT_Y,
                    spec.z(),
                    spec.name(),
                    spec.kind().symbol,
                    colorId(spec));
            waypoint.setTemporary(true);
            waypoint.setYIncluded(false);
            waypoints.add(spec.id(), waypoint);
            return true;
        }

        private static Identifier identifier(String originPath) {
            return new Identifier(IDENTIFIER_NAMESPACE, originPath);
        }

        private static int colorId(XaeroWaypointSpec spec) {
            return spec.kind() == XaeroWaypointSpec.Kind.STATION ? STATION_COLOR_ID : DEPOT_COLOR_ID;
        }
    }
}
