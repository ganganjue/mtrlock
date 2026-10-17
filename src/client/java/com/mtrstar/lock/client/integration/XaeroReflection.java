package com.mtrstar.lock.client.integration;

import com.mtrstar.lock.Mtrlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Xaero Minimap 第三方 waypoint API 的反射入口。
 *
 * <p>Xaero 的内部包没有公开 ABI，因此本类不引用任何 Xaero 类型。所有反射目标在首次使用时
 * 懒加载并缓存；类/成员缺失时一次性 WARN 后禁用集成，调用期异常则静默重试，
 * 连续失败达到阈值后才禁用。</p>
 */
public final class XaeroReflection {

    private static final String LOG_TAG = "[mtrlock][xaero]";
    private static final int MAX_CONSECUTIVE_CALL_FAILURES = 5;
    private static final String IDENTIFIER_NAMESPACE = "mtrlock";

    private static volatile boolean disabled;
    private static volatile boolean warned;
    private static volatile boolean initialized;
    private static int consecutiveCallFailures;

    private static Class<?> clsMinimap;
    private static Field fldMinimap;
    private static Method mGetSession;
    private static Method mGetWorldManager;
    private static Method mGetCurrentWorld;
    private static Method mGetContainer;
    private static Method mGetTpManager;
    private static Method mClearOrigin;
    private static Method mGetWps;
    private static Method mAddWp;
    private static Constructor<?> ctorWp9;
    private static Constructor<?> ctorWp7;
    private static Method mSetTemporary;
    private static Method mSetYIncluded;
    private static Object enumGold;
    private static Object enumAqua;
    private static Object enumNormal;
    private static Class<?> clsIdentifier;
    private static Constructor<?> ctorIdentifier;

    private XaeroReflection() {
    }

    /**
     * 返回当前 Xaero 第三方 waypoint manager。
     *
     * <p>Xaero 未安装、世界尚未加载或反射链任一环返回 null 时均返回 null。
     * 世界未加载是正常等待状态，不产生 WARN。</p>
     */
    public static Object currentManager() {
        if (disabled) {
            return null;
        }
        if (!initialize()) {
            return null;
        }

        try {
            Object hudModule = fldMinimap.get(null);
            if (hudModule == null) {
                return null;
            }

            Object session = mGetSession.invoke(hudModule);
            if (session == null) {
                return null;
            }

            Object worldManager = mGetWorldManager.invoke(session);
            if (worldManager == null) {
                return null;
            }

            Object world = mGetCurrentWorld.invoke(worldManager);
            if (world == null) {
                return null;
            }

            Object container = mGetContainer.invoke(world);
            if (container == null) {
                return null;
            }

            Object manager = mGetTpManager.invoke(container);
            if (manager == null) {
                return null;
            }

            consecutiveCallFailures = 0;
            return manager;
        } catch (Throwable t) {
            onCallFailure("currentManager", t);
            return null;
        }
    }

    /**
     * 清除指定 origin 下的所有第三方 waypoint。
     *
     * @return 成功返回 true；manager 未就绪或调用失败返回 false
     */
    public static boolean clearOrigin(Object manager, String originPath) {
        if (disabled || manager == null) {
            return false;
        }
        if (!initialize()) {
            return false;
        }

        try {
            Object identifier = newIdentifier(originPath);
            mClearOrigin.invoke(manager, identifier);
            consecutiveCallFailures = 0;
            return true;
        } catch (Throwable t) {
            onCallFailure("clearOrigin", t);
            return false;
        }
    }

    /**
     * 向指定 origin 添加一个 waypoint。
     *
     * @return 成功返回 true；manager / spec 未就绪或调用失败返回 false
     */
    public static boolean addWaypoint(Object manager, String originPath, XaeroWaypointSpec spec) {
        if (disabled || manager == null || spec == null) {
            return false;
        }
        if (!initialize()) {
            return false;
        }

        try {
            Object identifier = newIdentifier(originPath);
            Object waypoints = mGetWps.invoke(manager, identifier);
            if (waypoints == null) {
                return false;
            }

            Object waypoint = newWaypoint(spec);
            mAddWp.invoke(waypoints, spec.id(), waypoint);
            consecutiveCallFailures = 0;
            return true;
        } catch (Throwable t) {
            onCallFailure("addWaypoint", t);
            return false;
        }
    }

    public static boolean isDisabled() {
        return disabled;
    }

    /** 断线重连后允许重新探测新的 Xaero session。 */
    public static synchronized void reset() {
        disabled = false;
        warned = false;
        initialized = false;
        consecutiveCallFailures = 0;

        clsMinimap = null;
        fldMinimap = null;
        mGetSession = null;
        mGetWorldManager = null;
        mGetCurrentWorld = null;
        mGetContainer = null;
        mGetTpManager = null;
        mClearOrigin = null;
        mGetWps = null;
        mAddWp = null;
        ctorWp9 = null;
        ctorWp7 = null;
        mSetTemporary = null;
        mSetYIncluded = null;
        enumGold = null;
        enumAqua = null;
        enumNormal = null;
        clsIdentifier = null;
        ctorIdentifier = null;
    }

    private static synchronized boolean initialize() {
        if (disabled) {
            return false;
        }
        if (initialized) {
            return true;
        }

        String lookup = "unknown";
        try {
            lookup = "class xaero.hud.minimap.BuiltInHudModules";
            clsMinimap = cls("xaero.hud.minimap.BuiltInHudModules");

            lookup = "field xaero.hud.minimap.BuiltInHudModules.MINIMAP";
            fldMinimap = field(clsMinimap, "MINIMAP");

            Class<?> clsHudModule = cls("xaero.hud.module.HudModule");
            lookup = "method xaero.hud.module.HudModule#getCurrentSession()";
            mGetSession = method(clsHudModule, "getCurrentSession");

            Class<?> clsSession = mGetSession.getReturnType();
            lookup = "method " + clsSession.getName() + "#getWorldManager()";
            mGetWorldManager = method(clsSession, "getWorldManager");

            Class<?> clsWorldManager = mGetWorldManager.getReturnType();
            lookup = "method " + clsWorldManager.getName() + "#getCurrentWorld()";
            mGetCurrentWorld = method(clsWorldManager, "getCurrentWorld");

            Class<?> clsWorld = mGetCurrentWorld.getReturnType();
            lookup = "method " + clsWorld.getName() + "#getContainer()";
            mGetContainer = method(clsWorld, "getContainer");

            Class<?> clsContainer = mGetContainer.getReturnType();
            lookup = "method " + clsContainer.getName() + "#getThirdPartyWaypointManager()";
            mGetTpManager = method(clsContainer, "getThirdPartyWaypointManager");

            Class<?> clsManager = mGetTpManager.getReturnType();
            lookup = "class net.minecraft.util.Identifier";
            clsIdentifier = cls("net.minecraft.util.Identifier");
            lookup = "constructor net.minecraft.util.Identifier(String, String)";
            ctorIdentifier = ctor(clsIdentifier, String.class, String.class);

            lookup = "method " + clsManager.getName() + "#clearOrigin(Identifier)";
            mClearOrigin = method(clsManager, "clearOrigin", clsIdentifier);

            lookup = "method " + clsManager.getName() + "#get(Identifier)";
            mGetWps = method(clsManager, "get", clsIdentifier);

            Class<?> clsWaypoints = mGetWps.getReturnType();
            lookup = "class xaero.common.minimap.waypoints.Waypoint";
            Class<?> clsWaypoint = cls("xaero.common.minimap.waypoints.Waypoint");

            lookup = "class xaero.hud.minimap.waypoint.WaypointColor";
            Class<?> clsColor = cls("xaero.hud.minimap.waypoint.WaypointColor");
            lookup = "class xaero.hud.minimap.waypoint.WaypointPurpose";
            Class<?> clsPurpose = cls("xaero.hud.minimap.waypoint.WaypointPurpose");

            lookup = "method " + clsWaypoints.getName() + "#add(String, Waypoint)";
            mAddWp = method(clsWaypoints, "add", String.class, clsWaypoint);

            lookup = "constructor Waypoint(int,int,int,String,String,WaypointColor,WaypointPurpose,boolean,boolean)";
            try {
                ctorWp9 = ctor(clsWaypoint, int.class, int.class, int.class, String.class, String.class,
                        clsColor, clsPurpose, boolean.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                ctorWp9 = null;
            }

            if (ctorWp9 == null) {
                lookup = "constructor Waypoint(int,int,int,String,String,WaypointColor,WaypointPurpose)";
                ctorWp7 = ctor(clsWaypoint, int.class, int.class, int.class, String.class, String.class,
                        clsColor, clsPurpose);

                lookup = "method Waypoint#setTemporary(boolean)";
                mSetTemporary = method(clsWaypoint, "setTemporary", boolean.class);
                lookup = "method Waypoint#setYIncluded(boolean)";
                mSetYIncluded = method(clsWaypoint, "setYIncluded", boolean.class);
            }

            lookup = "enum xaero.hud.minimap.waypoint.WaypointColor.GOLD";
            enumGold = enumValue(clsColor, "GOLD");
            lookup = "enum xaero.hud.minimap.waypoint.WaypointColor.AQUA";
            enumAqua = enumValue(clsColor, "AQUA");
            lookup = "enum xaero.hud.minimap.waypoint.WaypointPurpose.NORMAL";
            enumNormal = enumValue(clsPurpose, "NORMAL");

            initialized = true;
            return true;
        } catch (ClassNotFoundException cnfe) {
            // Xaero 未安装：正常场景，静默禁用，不打日志
            disabled = true;
            return false;
        } catch (Throwable t) {
            onLookupFailure(lookup, t);
            return false;
        }
    }

    private static Object newIdentifier(String originPath) throws ReflectiveOperationException {
        return ctorIdentifier.newInstance(IDENTIFIER_NAMESPACE, originPath);
    }

    private static Object newWaypoint(XaeroWaypointSpec spec) throws ReflectiveOperationException {
        Object color = spec.kind() == XaeroWaypointSpec.Kind.STATION ? enumGold : enumAqua;
        if (ctorWp9 != null) {
            return ctorWp9.newInstance(
                    spec.x(), XaeroWaypointSpec.WAYPOINT_Y, spec.z(),
                    spec.name(), spec.kind().symbol, color, enumNormal, true, false);
        }

        Object waypoint = ctorWp7.newInstance(
                spec.x(), XaeroWaypointSpec.WAYPOINT_Y, spec.z(),
                spec.name(), spec.kind().symbol, color, enumNormal);
        mSetTemporary.invoke(waypoint, true);
        mSetYIncluded.invoke(waypoint, false);
        return waypoint;
    }

    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, XaeroReflection.class.getClassLoader());
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Constructor<?> ctor(Class<?> owner, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<?> constructor = owner.getConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> enumClass, String constantName) {
        return Enum.valueOf((Class<? extends Enum>) enumClass, constantName);
    }

    private static void onLookupFailure(String lookup, Throwable cause) {
        if (!warned) {
            Mtrlock.LOGGER.warn(
                    LOG_TAG + " Xaero 反射查找失败，禁用集成: " + lookup, cause);
            warned = true;
        }
        disabled = true;
    }

    private static void onCallFailure(String operation, Throwable cause) {
        consecutiveCallFailures++;
        if (!warned) {
            Mtrlock.LOGGER.warn(
                    LOG_TAG + " Xaero 调用失败（操作=" + operation + "），本轮静默重试", cause);
            warned = true;
        }
        if (consecutiveCallFailures >= MAX_CONSECUTIVE_CALL_FAILURES) {
            disabled = true;
            Mtrlock.LOGGER.warn(
                    LOG_TAG + " Xaero 连续 " + consecutiveCallFailures
                            + " 次调用失败，禁用集成（操作=" + operation + "）");
        }
    }
}
