package com.mtrstar.lock.client.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record XaeroWaypointSpec(String id, String name, int x, int z, Kind kind) {

    /** MTR 无有效 y，统一传 64，并置 yIncluded=false。 */
    public static final int WAYPOINT_Y = 64;

    public enum Kind {
        STATION("站", "GOLD", "xaero_stations"),
        DEPOT("厂", "AQUA", "xaero_depots");

        public final String symbol;
        public final String colorName;
        public final String originPath;

        Kind(String symbol, String colorName, String originPath) {
            this.symbol = symbol;
            this.colorName = colorName;
            this.originPath = originPath;
        }
    }

    public XaeroWaypointSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (name == null) {
            name = "";
        }
    }

    /**
     * 稳定 ID：优先用 MTR 的 getHexId()（改名/移动不变，无碰撞）；
     * 退化时用 kind + 内容哈希兜底。
     */
    public static String stableId(Kind kind, String hexId, String name, int x, int z) {
        String h = hexId == null ? "" : hexId.trim();
        if (!h.isEmpty()) {
            return kind.name().toLowerCase() + ":" + h;
        }
        String n = name == null ? "" : name;
        return kind.name().toLowerCase() + ":h" + Integer.toHexString(Objects.hash(n, x, z));
    }

    /** 带 hexId 的过滤构造。返回 empty 表示跳过。 */
    public static Optional<XaeroWaypointSpec> tryOf(Kind kind, String hexId, String name,
                                                    boolean valid, long x, long z) {
        if (!valid) {
            return Optional.empty();
        }
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        String n = name == null ? "" : name;
        int xi = (int) x;
        int zi = (int) z;
        return Optional.of(new XaeroWaypointSpec(
                stableId(kind, hexId, n, xi, zi), n, xi, zi, kind));
    }

    /** 不带 hexId 的便捷重载。 */
    public static Optional<XaeroWaypointSpec> tryOf(Kind kind, String name, boolean valid, long x, long z) {
        return tryOf(kind, null, name, valid, x, z);
    }

    /** 顺序无关、长度前缀防碰撞的指纹。null / 空集合返回空串。 */
    public static String fingerprint(Collection<XaeroWaypointSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(specs.size());
        for (XaeroWaypointSpec s : specs) {
            parts.add(encode(s));
        }
        Collections.sort(parts);
        return String.join("\n", parts);
    }

    private static String encode(XaeroWaypointSpec s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.kind.name()).append('|');
        appendField(sb, s.id).append('|');
        appendField(sb, s.name).append('|');
        sb.append(s.x).append('|').append(s.z);
        return sb.toString();
    }

    private static StringBuilder appendField(StringBuilder sb, String v) {
        String nv = v == null ? "" : v;
        return sb.append(nv.length()).append(':').append(nv);
    }

    /** manager 未就绪返回 false；否则指纹变化或 manager 实例变化均重建。 */
    public static boolean shouldRebuild(String fingerprint, Object manager,
                                        String lastFingerprint, Object lastManager) {
        if (manager == null) {
            return false;
        }
        return !Objects.equals(fingerprint, lastFingerprint) || manager != lastManager;
    }
}
