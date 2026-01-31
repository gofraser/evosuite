package org.evosuite.utils;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ListUtilTest {

    @Test
    public void testAnyEquals() {
        List<String> list = Arrays.asList("a", "b", "c");
        assertTrue(ListUtil.anyEquals(list, "b"));
        assertFalse(ListUtil.anyEquals(list, "d"));
    }

    @Test
    public void testAnyEqualsWithNulls() {
        List<String> list = Arrays.asList("a", null, "c");
        assertTrue(ListUtil.anyEquals(list, null));
        assertTrue(ListUtil.anyEquals(list, "a"));
        assertFalse(ListUtil.anyEquals(list, "b"));
    }

    @Test
    public void testAnyEqualsEmpty() {
        assertFalse(ListUtil.anyEquals(Collections.emptyList(), "a"));
    }
}
