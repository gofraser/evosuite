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
package org.evosuite.setup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class under test compiled for Java 6 or 7 comes from a build whose generated suite is compiled
 * at that source level, where calling a static interface method such as
 * {@code Predicate.isEqual(...)} is a compile error.
 */
class TestUsageCheckerStaticInterfaceMethodTest {

    @AfterEach
    void reset() {
        TestUsageChecker.setTargetClassFileMajorVersionForTesting(null);
    }

    @Test
    void staticInterfaceMethodIsExcludedForPreJava8Target() throws Exception {
        Method isEqual = Predicate.class.getMethod("isEqual", Object.class);
        TestUsageChecker.setTargetClassFileMajorVersionForTesting(50);
        assertFalse(TestUsageChecker.canUse(isEqual, Predicate.class));
    }

    @Test
    void staticInterfaceMethodIsKeptForJava8Target() throws Exception {
        Method isEqual = Predicate.class.getMethod("isEqual", Object.class);
        TestUsageChecker.setTargetClassFileMajorVersionForTesting(52);
        assertTrue(TestUsageChecker.canUse(isEqual, Predicate.class));
    }

    @Test
    void instanceInterfaceMethodsAreKeptForPreJava8Target() throws Exception {
        TestUsageChecker.setTargetClassFileMajorVersionForTesting(50);
        Method negate = Predicate.class.getMethod("negate");
        assertTrue(TestUsageChecker.canUse(negate, Predicate.class));
    }

    @Test
    void readsMajorVersionFromClassFile() throws Exception {
        int major = TestUsageChecker.readClassFileMajorVersion(
                getClass().getClassLoader(), getClass().getName());
        assertTrue(major >= TestUsageChecker.JAVA_8_CLASS_FILE_MAJOR, "was " + major);
        assertEquals(-1, TestUsageChecker.readClassFileMajorVersion(
                getClass().getClassLoader(), "no.such.Clazz"));
    }
}
