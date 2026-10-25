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
package org.evosuite.llm.response;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins which failure families an error text belongs to, so that the hint blocks and the
 * repair-skip decisions keep reacting to the same messages.
 */
class RepairFailureClassificationTest {

    private static final String JAVAC_CLASS_SYMBOL =
            "Foo.java:3: error: cannot find symbol\n  symbol:   class Widgetz\n  location: class FooTest";
    private static final String JAVAC_METHOD_SYMBOL =
            "Foo.java:3: error: cannot find symbol\n  symbol:   method area2()\n  location: class FooTest";

    private static final String[][] CASES = {
            {null, ""},
            {"", ""},
            {"java.lang.NullPointerException: because \"x\" is null", ""},
            {"java.lang.NoClassDefFoundError: org/apache/Missing", "dependency,initialization"},
            {"java.lang.NoClassDefFoundError: Could not initialize class com.dep.Poisoned",
                    "dependency,initialization"},
            {"java.lang.ClassNotFoundException: com.dep.Gone", "dependency,initialization"},
            {"java.lang.ExceptionInInitializerError", "initialization"},
            {"java.lang.UnsatisfiedLinkError: no native in java.library.path", "initialization"},
            {"java.lang.LinkageError: loader constraint", "initialization"},
            {JAVAC_CLASS_SYMBOL, "dependency"},
            {JAVAC_METHOD_SYMBOL, ""},
            {"java.awt.AWTError: Local GraphicsEnvironment must not be null", "initialization,headless"},
            {"java.awt.HeadlessException", "initialization,headless"},
            {"the graphicsenvironment is gone", "headless"},
            {"AWTError in lower case: awterror", "headless"},
            {"java.lang.InstantiationException: com.example.Widget", "instantiation"},
            {"com.example.Widget is abstract; cannot be instantiated", "instantiation"},
            {"Mockito cannot instantiate this type", "instantiation"},
            {"org.mockito.exceptions.misusing.NotAMockException: not a mock", "mocking"},
            {"org.mockito.exceptions.misusing.UnfinishedStubbingException", "mocking"},
            {"org.mockito.exceptions.base.MissingMethodInvocationException", "mocking"},
            {"java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 5", "indexed"},
            {"java.lang.IndexOutOfBoundsException: 3", "indexed"},
            {"java.lang.NegativeArraySizeException: -1", "indexed"},
            {"java.lang.NoClassDefFoundError: Foo\njava.lang.ArrayIndexOutOfBoundsException: 1\n"
                    + "org.mockito.exceptions.misusing.NotAMockException",
                    "dependency,initialization,mocking,indexed"},
    };

    @Test
    void errorTextsFallIntoTheExpectedFamilies() {
        for (String[] c : CASES) {
            assertEquals(c[1], families(c[0]), "families of: " + c[0]);
        }
    }

    private static String families(String error) {
        RepairFailureKinds kinds = RepairFailureKinds.of(error);
        List<String> found = new ArrayList<>();
        if (kinds.isDependencyMissing()) {
            found.add("dependency");
        }
        if (kinds.isInitializationFailure()) {
            found.add("initialization");
        }
        if (kinds.isHeadlessGui()) {
            found.add("headless");
        }
        if (kinds.isInstantiationFailure()) {
            found.add("instantiation");
        }
        if (kinds.isMockingMisuse()) {
            found.add("mocking");
        }
        if (kinds.isIndexedFixtureShape()) {
            found.add("indexed");
        }
        return String.join(",", found);
    }
}
