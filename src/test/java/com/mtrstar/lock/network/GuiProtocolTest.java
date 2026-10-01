package com.mtrstar.lock.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GuiProtocol} 单元测试（1.2.3）：协议版本校验。 */
class GuiProtocolTest {

    @Test
    @DisplayName("当前版本与自身兼容")
    void currentCompatible() {
        assertEquals(1, GuiProtocol.VERSION);
        assertTrue(GuiProtocol.isCompatible(GuiProtocol.VERSION));
    }

    @Test
    @DisplayName("旧版 / 新版 / 0 / 负数都不兼容")
    void mismatch() {
        assertFalse(GuiProtocol.isCompatible(GuiProtocol.VERSION - 1));
        assertFalse(GuiProtocol.isCompatible(GuiProtocol.VERSION + 1));
        assertFalse(GuiProtocol.isCompatible(0));
        assertFalse(GuiProtocol.isCompatible(-1));
        assertFalse(GuiProtocol.isCompatible(Integer.MAX_VALUE));
    }
}
