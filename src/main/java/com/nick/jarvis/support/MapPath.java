package com.nick.jarvis.support;

import java.util.List;
import java.util.Map;

/**
 * 在 JSON 解析出来的 Map/List 结构里按路径取值，某一层不存在时返回 null，而不是抛空指针。
 *   MapPath.get(json, "competitions", 0, "status", "type", "completed")
 */
public final class MapPath {

    private MapPath() {
    }

    public static Object get(Object root, Object... path) {
        Object current = root;
        for (Object key : path) {
            if (current instanceof Map<?, ?> map && key instanceof String name) {
                current = map.get(name);
            } else if (current instanceof List<?> list && key instanceof Integer index) {
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    public static String string(Object root, Object... path) {
        Object value = get(root, path);
        return value == null ? null : String.valueOf(value);
    }

    public static List<?> list(Object root, Object... path) {
        return get(root, path) instanceof List<?> list ? list : List.of();
    }

    public static boolean bool(Object root, Object... path) {
        return Boolean.TRUE.equals(get(root, path));
    }
}
