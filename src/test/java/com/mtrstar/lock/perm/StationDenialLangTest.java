package com.mtrstar.lock.perm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.2：客户端与服务端共用的站台权限文案 key 必须同时在双语 lang 文件中存在。 */
class StationDenialLangTest {

    private static final String KEY = "\"" + PermissionGuard.STATION_ADDITION_DENY_KEY + "\"";

    @Test
    @DisplayName("站台权限专用文案 key 在 en_us / zh_cn 中存在且一致")
    void stationPermissionKeyExistsInBothLanguages() throws Exception {
        final String en = Files.readString(Path.of("src/main/resources/assets/mtrlock/lang/en_us.json"));
        final String zh = Files.readString(Path.of("src/main/resources/assets/mtrlock/lang/zh_cn.json"));

        assertTrue(en.contains(KEY), "en_us.json 缺少 " + KEY);
        assertTrue(zh.contains(KEY), "zh_cn.json 缺少 " + KEY);
    }
}
