package org.evosuite.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArrayUtilTest {

    @Test
    public void testContains() {
        Integer[] array = new Integer[] {1, 2, 3};
        assertTrue(ArrayUtil.contains(array, 1));
        assertFalse(ArrayUtil.contains(array, 4));
    }

    @Test
    public void testContainsWithNulls() {
        Integer[] array = new Integer[] {1, null, 3};
        assertTrue(ArrayUtil.contains(array, null));
        assertTrue(ArrayUtil.contains(array, 1));
    }

    @Test
    public void testContainsStrictType() {
        // This test asserts the DESIRED behavior (strict checking).
        // It currently fails because of the bug/loose check in ArrayUtil.contains.
        Integer[] array = new Integer[] {1, 2, 3};
        assertFalse("Should not match String '1' to Integer 1", ArrayUtil.contains(array, "1"));
    }
}
