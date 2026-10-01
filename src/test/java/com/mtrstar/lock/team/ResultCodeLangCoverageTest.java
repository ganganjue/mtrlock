package com.mtrstar.lock.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResultCode 与 lang 文件覆盖断言（1.2.4，约束 2）。
 *
 * <p>用 <b>classloader</b> 读 {@code /assets/mtrlock/lang/zh_cn.json} / {@code en_us.json}
 * （即 {@code processResources} 的产物），不硬编码 {@code src/main/resources} 路径，
 * 保证在 CI / IDE / 打包后都能跑。新增任何 {@link ResultCode} 却忘记翻译时，
 * 这个测试会直接失败。</p>
 */
class ResultCodeLangCoverageTest {

    @Test
    @DisplayName("每个 ResultCode 都有中文 lang 键")
    void everyCodeHasChineseKey() throws IOException {
        final String zh = readResource("/assets/mtrlock/lang/zh_cn.json");
        for (ResultCode code : ResultCode.values()) {
            final String key = ResultMessages.langKey(code);
            assertTrue(zh.contains("\"" + key + "\""), "zh_cn.json 缺少键: " + key);
        }
    }

    @Test
    @DisplayName("每个 ResultCode 都有英文 lang 键")
    void everyCodeHasEnglishKey() throws IOException {
        final String en = readResource("/assets/mtrlock/lang/en_us.json");
        for (ResultCode code : ResultCode.values()) {
            final String key = ResultMessages.langKey(code);
            assertTrue(en.contains("\"" + key + "\""), "en_us.json 缺少键: " + key);
        }
    }

    @Test
    @DisplayName("1.2.3 的旧结果码 key 仍在（只增不改）")
    void legacyKeysStillPresent() throws IOException {
        final String zh = readResource("/assets/mtrlock/lang/zh_cn.json");
        // 抽样：1.2.3 就存在的码必须在
        for (ResultCode code : new ResultCode[]{
                ResultCode.TEAM_CREATED, ResultCode.APPLY_FAILED, ResultCode.NEED_ADMIN,
                ResultCode.PROTOCOL_MISMATCH, ResultCode.UNKNOWN_ACTION}) {
            assertTrue(zh.contains("\"" + ResultMessages.langKey(code) + "\""), code.name());
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = ResultCodeLangCoverageTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
