/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase.execution.reset;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassReInitializeExecutorFallbackTest {

    private final String defaultTargetClass = Properties.TARGET_CLASS;
    private final String defaultProjectPrefix = Properties.PROJECT_PREFIX;
    private final String defaultTargetClassPrefix = Properties.TARGET_CLASS_PREFIX;

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.PROJECT_PREFIX = defaultProjectPrefix;
        Properties.TARGET_CLASS_PREFIX = defaultTargetClassPrefix;
        ClassReInitializer.resetSingleton();
    }

    @Test
    public void testSkipsReflectiveFallbackForThirdPartyClass() throws Exception {
        Properties.TARGET_CLASS = Cut.class.getName();
        Properties.PROJECT_PREFIX = "org.evosuite";
        Properties.TARGET_CLASS_PREFIX = "";

        assertTrue(invokeShouldSkipReflectiveFallback("org.apache.xmlbeans.XmlBeans"));
    }

    @Test
    public void testAllowsReflectiveFallbackForTargetProjectClass() throws Exception {
        Properties.TARGET_CLASS = Cut.class.getName();
        Properties.PROJECT_PREFIX = "org.evosuite";
        Properties.TARGET_CLASS_PREFIX = "";

        assertFalse(invokeShouldSkipReflectiveFallback(Sibling.class.getName()));
    }

    @Test
    public void testSkipsReflectiveFallbackForJdkClass() throws Exception {
        Properties.TARGET_CLASS = Cut.class.getName();
        Properties.PROJECT_PREFIX = "org.evosuite";
        Properties.TARGET_CLASS_PREFIX = "";

        assertTrue(invokeShouldSkipReflectiveFallback("java.lang.String"));
    }

    private static boolean invokeShouldSkipReflectiveFallback(String className) throws Exception {
        Method method = ClassReInitializeExecutor.class
                .getDeclaredMethod("shouldSkipReflectiveFallback", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, className);
    }

    static class Cut {
        static int value = 1;
    }

    static class Sibling {
        static Object value = new Object();
    }
}
