package org.evosuite.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class StringUtilTest {

    @Test
    public void testGetCommonPrefix() {
        assertEquals("i am a ", StringUtil.getCommonPrefix(new String[] {"i am a machine", "i am a robot"}));
        assertEquals("", StringUtil.getCommonPrefix(new String[] {"foo", "bar"}));
        assertEquals("abc", StringUtil.getCommonPrefix(new String[] {"abc"}));
        assertEquals("", StringUtil.getCommonPrefix(new String[] {}));
        assertEquals("", StringUtil.getCommonPrefix(null));
    }

    @Test
    public void testIndexOfDifference() {
        assertEquals(7, StringUtil.indexOfDifference(new String[] {"i am a machine", "i am a robot"}));
        assertEquals(-1, StringUtil.indexOfDifference(new String[] {"abc", "abc"}));
        assertEquals(0, StringUtil.indexOfDifference(new String[] {"abc", "xyz"}));
    }
}
