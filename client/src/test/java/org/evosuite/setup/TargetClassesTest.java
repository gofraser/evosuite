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

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TargetClassesTest {

    private String oldTarget;

    @BeforeEach
    public void setUp() {
        oldTarget = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = "com.example.Foo";
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = oldTarget;
    }

    @Test
    public void cutIsTheTargetAndItsNestedClasses() {
        assertTrue(TargetClasses.isCut("com.example.Foo"));
        assertTrue(TargetClasses.isCut("com.example.Foo$Inner"));
        assertTrue(TargetClasses.isCut("com.example.Foo$Inner$1"));
    }

    @Test
    public void cutRejectsSuffixAndPrefixLookalikes() {
        assertFalse(TargetClasses.isCut("other.com.example.Foo"));
        assertFalse(TargetClasses.isCut("com.example.FooBar"));
        assertFalse(TargetClasses.isCut("com.example.Fo"));
    }

    @Test
    public void unsetTargetMatchesNothingNarrowlyAndEverythingWhenUnset() {
        Properties.TARGET_CLASS = "";
        assertFalse(TargetClasses.isCut("com.example.Foo"));
        assertTrue(TargetClasses.isCutOrUnset("com.example.Foo"));
    }

    @Test
    public void setTargetIsNotWidenedByCutOrUnset() {
        assertTrue(TargetClasses.isCutOrUnset("com.example.Foo$Inner"));
        assertFalse(TargetClasses.isCutOrUnset("com.example.Other"));
    }

    @Test
    public void explicitTargetOverloadHandlesNullAndEmpty() {
        assertTrue(TargetClasses.isCut("a.B$C", "a.B"));
        assertFalse(TargetClasses.isCut("a.B", null));
        assertFalse(TargetClasses.isCut("a.B", ""));
    }

    @Test
    public void targetScopeIncludesTheCut() {
        assertTrue(TargetClasses.isTargetScope("com.example.Foo$Inner"));
        assertFalse(TargetClasses.isTargetScope("com.example.Other"));
    }
}
