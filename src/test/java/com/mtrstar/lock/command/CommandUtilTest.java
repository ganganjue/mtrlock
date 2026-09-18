package com.mtrstar.lock.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandUtilTest {

    @Test
    @DisplayName("合法 objectId：5 种前缀 + 16 位大写 HEX")
    void valid() {
        assertTrue(CommandUtil.isValidObjectId("route:0B0829457F350DE9"));
        assertTrue(CommandUtil.isValidObjectId("station:695F49B3A0810590"));
        assertTrue(CommandUtil.isValidObjectId("depot:FC5F351C6978B956"));
        assertTrue(CommandUtil.isValidObjectId("platform:1A2B3C4D5E6F7080"));
        assertTrue(CommandUtil.isValidObjectId("siding:9F8E7D6C5B4A3210"));
    }

    @Test
    @DisplayName("非法：前缀不对 / 长度不对 / 小写 / 缺冒号")
    void invalid() {
        assertFalse(CommandUtil.isValidObjectId(null));
        assertFalse(CommandUtil.isValidObjectId(""));
        assertFalse(CommandUtil.isValidObjectId("foo:0B0829457F350DE9"));
        assertFalse(CommandUtil.isValidObjectId("route:0B0829457F350DE"));
        assertFalse(CommandUtil.isValidObjectId("route:0B0829457F350DE99"));
        assertFalse(CommandUtil.isValidObjectId("route:0b0829457f350de9"));
        assertFalse(CommandUtil.isValidObjectId("route0B0829457F350DE9"));
        assertFalse(CommandUtil.isValidObjectId(":0B0829457F350DE9"));
        assertFalse(CommandUtil.isValidObjectId("route:"));
        assertFalse(CommandUtil.isValidObjectId("route:GGGGGGGGGGGGGGGG"));
    }

    @Test
    @DisplayName("prefixOf：合法取前缀，非法返回 null")
    void prefixOf() {
        assertEquals("route", CommandUtil.prefixOf("route:0B0829457F350DE9"));
        assertEquals("station", CommandUtil.prefixOf("station:695F49B3A0810590"));
        assertNull(CommandUtil.prefixOf("bad"));
        assertNull(CommandUtil.prefixOf(null));
    }
}
