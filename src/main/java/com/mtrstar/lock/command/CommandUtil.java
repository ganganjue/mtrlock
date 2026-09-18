package com.mtrstar.lock.command;

import java.util.Set;

/** 命令层的纯函数工具（可 JVM 单测）。 */
public final class CommandUtil {

    public static final Set<String> PREFIXES = Set.of("route", "station", "depot", "platform", "siding");

    private CommandUtil() {
    }

    public static boolean isValidObjectId(String id) {
        if (id == null) {
            return false;
        }
        final int idx = id.indexOf(':');
        if (idx <= 0 || idx >= id.length() - 1) {
            return false;
        }
        final String prefix = id.substring(0, idx);
        if (!PREFIXES.contains(prefix)) {
            return false;
        }
        final String hex = id.substring(idx + 1);
        if (hex.length() != 16) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            final char c = hex.charAt(i);
            final boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static String prefixOf(String objectId) {
        if (!isValidObjectId(objectId)) {
            return null;
        }
        return objectId.substring(0, objectId.indexOf(':'));
    }
}
