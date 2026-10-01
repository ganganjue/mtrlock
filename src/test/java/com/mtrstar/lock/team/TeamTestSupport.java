package com.mtrstar.lock.team;

import java.nio.file.Path;

/**
 * 测试支持：让 {@code gui} / {@code network} 等其它包的测试也能构造
 * {@link TeamData} / {@link ShareData} 的内存实例（它们的构造器是 team 包内可见）。
 *
 * <p>只存在于 test 源集，不进入生产代码。</p>
 */
public final class TeamTestSupport {

    private TeamTestSupport() {
    }

    public static TeamData teamData(Path file) {
        return new TeamData(file);
    }

    public static ShareData shareData(Path file,
                                      ShareData.CreatorLookup creators,
                                      ShareData.TeamExistsLookup teamExists) {
        return new ShareData(file, creators, teamExists);
    }
}
