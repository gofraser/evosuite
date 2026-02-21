package org.evosuite.setup;

import org.evosuite.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAnonymousLocalClass {

    @Test
    public void testAnonymousClass() {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = "org.evosuite.setup.TestAnonymousLocalClass";
        Object o = new Object() {};
        boolean result = TestUsageChecker.canUse(o.getClass());
        Assertions.assertFalse(result);
    }

    @Test
    public void testLocalClass() {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = "org.evosuite.setup.TestAnonymousLocalClass";
        class Local {}
        boolean result = TestUsageChecker.canUse(Local.class);
        Assertions.assertFalse(result);
    }
}
