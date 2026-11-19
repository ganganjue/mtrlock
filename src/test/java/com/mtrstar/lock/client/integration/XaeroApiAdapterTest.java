package com.mtrstar.lock.client.integration;

import com.mtrstar.lock.Mtrlock;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link XaeroApiAdapter} 单测（1.5.3）。
 *
 * <p>通过测试用 Backend 替换官方 API 后端，避免单元测试加载 Xaero 运行期类；
 * 日志使用 Log4j appender 捕获，验证安装判断、WARN 限频与正常调用次数。</p>
 */
class XaeroApiAdapterTest {

    private Logger logger;
    private Level previousLevel;
    private CapturingAppender appender;

    @BeforeEach
    void setUp() {
        XaeroApiAdapter.setBackendForTesting(null);
        XaeroApiAdapter.setModLoadedCheckerForTesting(null);
        XaeroApiAdapter.reset();

        // 先触发 Mtrlock.LOGGER 初始化，避免 Log4j 首次配置覆盖测试设置的 DEBUG 级别。
        Mtrlock.LOGGER.getName();
        logger = (Logger) LogManager.getLogger("mtrlock");
        previousLevel = logger.getLevel();
        appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.removeAppender(appender);
            appender.stop();
            if (previousLevel != null) {
                logger.setLevel(previousLevel);
            }
        }
        XaeroApiAdapter.setBackendForTesting(null);
        XaeroApiAdapter.setModLoadedCheckerForTesting(null);
        XaeroApiAdapter.reset();
    }

    @Test
    @DisplayName("Xaero 未安装 → 静默返回 null，不调用后端、不 WARN")
    void xaeroNotInstalledIsSilent() {
        final FakeBackend backend = new FakeBackend();
        backend.manager = new Object();
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> false);
        XaeroApiAdapter.setBackendForTesting(backend);

        assertNull(XaeroApiAdapter.currentManager());
        assertFalse(XaeroApiAdapter.isInstalled());
        assertEquals(0, backend.currentManagerCalls.get(), "未安装时不应触碰官方后端");
        assertEquals(0, appender.count(Level.WARN, null), "未安装时不应 WARN");
    }

    @Test
    @DisplayName("Xaero 已安装但 API manager 为 null → WARN 一次")
    void installedButManagerNullWarnsOnce() {
        final FakeBackend backend = new FakeBackend();
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);

        assertNull(XaeroApiAdapter.currentManager());
        assertNull(XaeroApiAdapter.currentManager());

        assertEquals(2, backend.currentManagerCalls.get());
        assertEquals(1, appender.count(Level.WARN, "第三方 waypoint manager 为 null"),
                "manager 为 null 只应 WARN 一次");
    }

    @Test
    @DisplayName("正常路径 → addWaypoint 调用官方后端一次")
    void normalPathAddCallsBackendOnce() {
        final FakeBackend backend = new FakeBackend();
        final Object manager = new Object();
        backend.manager = manager;
        backend.addResult = true;
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);
        final XaeroWaypointSpec spec = new XaeroWaypointSpec(
                "station:TEST", "测试站", 1, 2, XaeroWaypointSpec.Kind.STATION);

        assertSame(manager, XaeroApiAdapter.currentManager());
        assertTrue(XaeroApiAdapter.addWaypoint(manager, spec.kind().originPath, spec));

        assertEquals(1, backend.addWaypointCalls.get(), "正常路径 add 只应调用一次");
        assertEquals(spec, backend.lastSpec);
        assertEquals(spec.kind().originPath, backend.lastOriginPath);
    }

    @Test
    @DisplayName("正常路径 → clearOrigin 调用官方后端一次")
    void normalPathClearCallsBackendOnce() {
        final FakeBackend backend = new FakeBackend();
        final Object manager = new Object();
        backend.manager = manager;
        backend.clearResult = true;
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);

        assertTrue(XaeroApiAdapter.clearOrigin(manager, "xaero_stations"));
        assertEquals(1, backend.clearOriginCalls.get(), "正常路径 clearOrigin 只应调用一次");
        assertSame(manager, backend.lastClearManager);
        assertEquals("xaero_stations", backend.lastClearOriginPath);
    }

    @Test
    @DisplayName("reset 后 manager 为 null 的 WARN 限频重新生效")
    void resetAllowsManagerNullWarnAgain() {
        final FakeBackend backend = new FakeBackend();
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);

        assertNull(XaeroApiAdapter.currentManager());
        XaeroApiAdapter.reset();
        assertNull(XaeroApiAdapter.currentManager());

        assertEquals(2, appender.count(Level.WARN, "第三方 waypoint manager 为 null"),
                "reset 后应允许重新 WARN 一次");
    }

    @Test
    @DisplayName("Backend.currentManager 抛 LinkageError → disabled，WARN 一次，后续不再调用后端")
    void currentManagerLinkageErrorDisables() {
        final FakeBackend backend = new FakeBackend();
        backend.currentManagerLinkageError = new LinkageError("test linkage failure");
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);

        assertNull(XaeroApiAdapter.currentManager());
        assertTrue(XaeroApiAdapter.isDisabled());
        assertNull(XaeroApiAdapter.currentManager());

        assertEquals(1, backend.currentManagerCalls.get(), "LinkageError 后不应再调用后端");
        assertEquals(1, appender.count(Level.WARN, "Xaero 官方 API 类加载失败，禁用集成"),
                "LinkageError 只应 WARN 一次");
    }

    @Test
    @DisplayName("Backend.currentManager 抛 RuntimeException → 不 disabled，WARN 一次，后续会重试")
    void currentManagerRuntimeExceptionRetries() {
        final FakeBackend backend = new FakeBackend();
        backend.currentManagerRuntimeException = new RuntimeException("transient failure");
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);

        assertNull(XaeroApiAdapter.currentManager());
        assertFalse(XaeroApiAdapter.isDisabled());
        assertNull(XaeroApiAdapter.currentManager());

        assertEquals(2, backend.currentManagerCalls.get(), "RuntimeException 后应允许重试后端");
        assertEquals(1, appender.count(Level.WARN, "获取 Xaero 第三方 waypoint manager 失败"),
                "同一 RuntimeException 只应 WARN 一次");
    }

    @Test
    @DisplayName("不同操作分别限频：currentManager 与 addWaypoint 的 WARN 互不影响")
    void differentOperationsWarnIndependently() {
        final FakeBackend backend = new FakeBackend();
        backend.currentManagerRuntimeException = new RuntimeException("currentManager failure");
        backend.addException = new RuntimeException("addWaypoint failure");
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);
        final XaeroWaypointSpec spec = new XaeroWaypointSpec(
                "station:TEST", "测试站", 1, 2, XaeroWaypointSpec.Kind.STATION);

        assertNull(XaeroApiAdapter.currentManager());
        assertFalse(XaeroApiAdapter.addWaypoint(new Object(), spec.kind().originPath, spec));

        assertEquals(1, appender.count(Level.WARN, "获取 Xaero 第三方 waypoint manager 失败"),
                "currentManager 异常应产生一条 WARN");
        assertEquals(1, appender.count(Level.WARN, "添加 Xaero waypoint 失败"),
                "addWaypoint 异常应产生另一条 WARN");
    }

    @Test
    @DisplayName("reset 清空 warnedOperations，同一操作异常可再次 WARN")
    void resetClearsWarnedOperations() {
        final FakeBackend backend = new FakeBackend();
        backend.addException = new RuntimeException("addWaypoint failure");
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);
        final XaeroWaypointSpec spec = new XaeroWaypointSpec(
                "station:TEST", "测试站", 1, 2, XaeroWaypointSpec.Kind.STATION);

        assertFalse(XaeroApiAdapter.addWaypoint(new Object(), spec.kind().originPath, spec));
        XaeroApiAdapter.reset();
        assertFalse(XaeroApiAdapter.addWaypoint(new Object(), spec.kind().originPath, spec));

        assertEquals(2, appender.count(Level.WARN, "添加 Xaero waypoint 失败"),
                "reset 后同一操作应允许再次 WARN");
    }

    @Test
    @DisplayName("官方后端抛异常 → WARN 一次并返回 false")
    void backendExceptionWarnsOnce() {
        final FakeBackend backend = new FakeBackend();
        backend.manager = new Object();
        backend.addException = new RuntimeException("test failure");
        XaeroApiAdapter.setModLoadedCheckerForTesting(() -> true);
        XaeroApiAdapter.setBackendForTesting(backend);
        final XaeroWaypointSpec spec = new XaeroWaypointSpec(
                "station:TEST", "测试站", 1, 2, XaeroWaypointSpec.Kind.STATION);

        final Object manager = XaeroApiAdapter.currentManager();
        assertFalse(XaeroApiAdapter.addWaypoint(manager, spec.kind().originPath, spec));
        assertFalse(XaeroApiAdapter.addWaypoint(manager, spec.kind().originPath, spec));

        assertEquals(2, backend.addWaypointCalls.get());
        assertEquals(1, appender.count(Level.WARN, "添加 Xaero waypoint 失败"),
                "同一操作异常只应 WARN 一次");
    }

    private static final class FakeBackend implements XaeroApiAdapter.Backend {

        private Object manager;
        private boolean addResult;
        private boolean clearResult;
        private RuntimeException addException;
        private LinkageError currentManagerLinkageError;
        private RuntimeException currentManagerRuntimeException;
        private final AtomicInteger currentManagerCalls = new AtomicInteger();
        private final AtomicInteger addWaypointCalls = new AtomicInteger();
        private final AtomicInteger clearOriginCalls = new AtomicInteger();
        private XaeroWaypointSpec lastSpec;
        private String lastOriginPath;
        private Object lastClearManager;
        private String lastClearOriginPath;

        @Override
        public Object currentManager() {
            currentManagerCalls.incrementAndGet();
            if (currentManagerLinkageError != null) {
                throw currentManagerLinkageError;
            }
            if (currentManagerRuntimeException != null) {
                throw currentManagerRuntimeException;
            }
            return manager;
        }

        @Override
        public boolean clearOrigin(Object manager, String originPath) {
            clearOriginCalls.incrementAndGet();
            lastClearManager = manager;
            lastClearOriginPath = originPath;
            return clearResult;
        }

        @Override
        public boolean addWaypoint(Object manager, String originPath, XaeroWaypointSpec spec) {
            addWaypointCalls.incrementAndGet();
            lastSpec = spec;
            lastOriginPath = originPath;
            if (addException != null) {
                throw addException;
            }
            return addResult;
        }
    }

    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        private CapturingAppender() {
            super("mtrlock-xaero-api-test", null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        private long count(Level level, String messagePart) {
            synchronized (events) {
                return events.stream()
                        .filter(event -> event.getLevel().equals(level))
                        .filter(event -> messagePart == null
                                || event.getMessage().getFormattedMessage().contains(messagePart))
                        .count();
            }
        }
    }
}
