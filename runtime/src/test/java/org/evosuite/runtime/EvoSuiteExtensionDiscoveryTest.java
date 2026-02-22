/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

public class EvoSuiteExtensionDiscoveryTest {

    static class Example_ESTest {
    }

    static class Example_ESTest_0 {
    }

    static class Example_Failed_ESTest {
    }

    static class UnrelatedName {
    }

    @Test
    public void testInferTargetClassFromMergedTestName() {
        String inferred = EvoSuiteExtension.inferTargetClassName(Example_ESTest.class);
        Assertions.assertEquals("org.evosuite.runtime.EvoSuiteExtensionDiscoveryTest$Example", inferred);
    }

    @Test
    public void testInferTargetClassFromPerFileTestName() {
        String inferred = EvoSuiteExtension.inferTargetClassName(Example_ESTest_0.class);
        Assertions.assertEquals("org.evosuite.runtime.EvoSuiteExtensionDiscoveryTest$Example", inferred);
    }

    @Test
    public void testInferTargetClassFromFailedTestName() {
        String inferred = EvoSuiteExtension.inferTargetClassName(Example_Failed_ESTest.class);
        Assertions.assertEquals("org.evosuite.runtime.EvoSuiteExtensionDiscoveryTest$Example", inferred);
    }

    @Test
    public void testDiscoverClassesFallsBackToExistingRuntimeSetting() {
        String previous = RuntimeSettings.className;
        try {
            RuntimeSettings.className = "org.example.FallbackCut";
            Set<String> discovered = EvoSuiteExtension.discoverClassesToInitialize(UnrelatedName.class);
            Assertions.assertEquals(1, discovered.size());
            Assertions.assertTrue(discovered.contains("org.example.FallbackCut"));
        } finally {
            RuntimeSettings.className = previous;
        }
    }

    @Test
    public void testResolveClassesUsesExplicitOrderWhenProvided() {
        String[] resolved = EvoSuiteExtension.resolveClassesToInitialize(
                Example_ESTest.class,
                "b.ClassTwo",
                "a.ClassOne",
                "c.ClassThree"
        );
        Assertions.assertEquals(
                Arrays.asList("b.ClassTwo", "a.ClassOne", "c.ClassThree"),
                Arrays.asList(resolved)
        );
    }
}
