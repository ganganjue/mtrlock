package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.1 新增结果码 {@link ResultCode#DEPOT_OPERATION_NO_PERMISSION} 的单元测试。
 *
 * <p>覆盖：lang key 命名、中文文案的 {@code %s} 占位与格式化、非成功码、
 * zh/en lang 文件都有该键（{@code ResultCodeLangCoverageTest} 之外再显式断言一次），
 * 以及「结果码只增不改」（GUI 协议用 {@code ResultCode.valueOf(name)} 走名字，不能改名）。</p>
 */
class DepotOperationResultCodeTest {

    private static final ResultCode CODE = ResultCode.DEPOT_OPERATION_NO_PERMISSION;
    private static final String LANG_KEY = "gui.mtrlock.result.depot_operation_no_permission";

    @Test
    @DisplayName("langKey：与枚举名一致的小写下划线形式")
    void langKeyNaming() {
        assertEquals(LANG_KEY, ResultMessages.langKey(CODE));
    }

    @Test
    @DisplayName("中文文案：含 %s 占位符，格式化后带上 depot objectId")
    void chineseMessageFormats() {
        final String template = ResultMessages.zh(CODE);
        assertTrue(template.contains("%s"), template);
        assertEquals("你没有权限对车厂「depot:0B0829457F350DE9」执行此操作",
                String.format(template, "depot:0B0829457F350DE9"));
    }

    @Test
    @DisplayName("是失败码（isSuccess = false），GUI 才能正确判红")
    void isFailure() {
        assertFalse(CODE.isSuccess());
    }

    @Test
    @DisplayName("zh_cn / en_us 都有该 lang 键且都带 %s 占位符")
    void langKeysPresentInBothLanguages() throws IOException {
        for (String path : new String[]{"/assets/mtrlock/lang/zh_cn.json", "/assets/mtrlock/lang/en_us.json"}) {
            final String json = readResource(path);
            assertTrue(json.contains("\"" + LANG_KEY + "\""), path + " 缺少键 " + LANG_KEY);
            final int keyIndex = json.indexOf("\"" + LANG_KEY + "\"");
            final String line = json.substring(keyIndex, json.indexOf('\n', keyIndex));
            assertTrue(line.contains("%s"), path + " 的文案应带 %s 占位符: " + line);
        }
    }

    @Test
    @DisplayName("结果码只增不改：旧名字仍可解析，新码追加在末尾")
    void appendOnly() {
        // GUI 协议用 ResultCode.valueOf(name) 传输（不传序号），所以旧名字必须保持可解析
        for (String legacy : new String[]{"TEAM_CREATED", "TEAM_CREATE_FAILED", "NEED_ADMIN",
                "UNKNOWN_ACTION", "COLOR_SET", "TITLE_REQUIRED"}) {
            assertSame(ResultCode.valueOf(legacy).name(), legacy);
        }
        assertEquals(CODE, ResultCode.valueOf("DEPOT_OPERATION_NO_PERMISSION"));
        assertEquals(ResultCode.values().length - 1, CODE.ordinal(), "新码应追加在枚举末尾");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = DepotOperationResultCodeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
