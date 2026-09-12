package com.mtrstar.lock.perm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link ChildParents} 从属索引的单元测试。 */
class ChildParentsTest {

    @AfterEach
    void tearDown() {
        ChildParents.clear();
    }

    @Test
    @DisplayName("put/get：记录并读回从属关系")
    void putGet() {
        ChildParents.put("platform:AAA", "station:BBB");
        assertEquals("station:BBB", ChildParents.get("platform:AAA"));
        assertEquals(1, ChildParents.size());
    }

    @Test
    @DisplayName("get(null) / put(null) 不 NPE")
    void nullSafe() {
        assertNull(ChildParents.get(null));
        ChildParents.put(null, "station:BBB");
        ChildParents.put("platform:AAA", null);
        assertEquals(0, ChildParents.size());
    }

    @Test
    @DisplayName("remove：删除单个子索引")
    void removeChild() {
        ChildParents.put("platform:AAA", "station:BBB");
        ChildParents.put("platform:CCC", "station:BBB");

        ChildParents.remove("platform:AAA");

        assertNull(ChildParents.get("platform:AAA"));
        assertEquals("station:BBB", ChildParents.get("platform:CCC"));
        assertEquals(1, ChildParents.size());
    }

    @Test
    @DisplayName("removeChildrenOf：删除父对象时清掉它下面所有子索引")
    void removeChildrenOfParent() {
        ChildParents.put("platform:AAA", "station:BBB");
        ChildParents.put("platform:CCC", "station:BBB");
        ChildParents.put("siding:DDD", "depot:EEE");

        ChildParents.removeChildrenOf("station:BBB");

        assertNull(ChildParents.get("platform:AAA"));
        assertNull(ChildParents.get("platform:CCC"));
        assertEquals("depot:EEE", ChildParents.get("siding:DDD")); // 不受影响
        assertEquals(1, ChildParents.size());
    }

    @Test
    @DisplayName("clear：清空索引")
    void clearAll() {
        ChildParents.put("platform:AAA", "station:BBB");
        ChildParents.clear();
        assertEquals(0, ChildParents.size());
        assertNull(ChildParents.get("platform:AAA"));
    }
}
