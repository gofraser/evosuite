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
package org.evosuite.junit;

import org.evosuite.runtime.TooManyResourcesException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code under test that wraps the runtime's resource limit in an exception of its own throws it
 * only when the limit meets that handler, so the JUnit check must treat it as the limit.
 */
public class JUnitAnalyzerWrappedLimitTest {

    @Test
    public void aWrapperThatQuotesTheResourceLimitCountsAsTheLimit() {
        String limit = TooManyResourcesException.class.getName();
        String quoted = "javax.persistence.PersistenceException: " + limit
                + ": Loop has been executed more times than the allowed 10000";

        assertTrue(JUnitFailurePolicy.isWrappedTooManyResourcesFailure(
                new JUnitFailure(quoted, "javax.persistence.PersistenceException", "test0", false, "")));
        assertTrue(JUnitFailurePolicy.isWrappedTooManyResourcesFailure(
                new JUnitFailure("wrapped", "java.lang.IllegalStateException", "test0", false,
                        "java.lang.IllegalStateException: wrapped\nCaused by: " + limit + ": Loop")));
    }

    @Test
    public void otherFailuresAreNotTheLimit() {
        assertFalse(JUnitFailurePolicy.isWrappedTooManyResourcesFailure(
                new JUnitFailure("boom", "java.lang.IllegalStateException", "test0", false,
                        "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)")));
        assertFalse(JUnitFailurePolicy.isWrappedTooManyResourcesFailure(
                new JUnitFailure(null, "java.lang.IllegalStateException", "test0", false, null)));
        assertFalse(JUnitFailurePolicy.isWrappedTooManyResourcesFailure(null));
    }

    public static class Probe {
        @org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit
        public void marked() {
        }

        public void unmarked() {
        }
    }

    @Test
    public void aStackOverflowIsAcceptedOnlyForATestMarkedAsEndingOnTheLimit() {
        Class<?>[] classes = {Probe.class};
        String cls = Probe.class.getName();
        String overflow = StackOverflowError.class.getName();

        String wrappedTrace = "java.lang.RuntimeException: x\nCaused by: " + overflow + "\n\tat Foo.bar(Foo.java:1)";

        assertTrue(JUnitFailurePolicy.isStackOverflowOfTestEndingOnLimit(
                new JUnitFailure(null, overflow, cls, "marked", false, ""), classes));
        assertTrue(JUnitFailurePolicy.isStackOverflowOfTestEndingOnLimit(
                new JUnitFailure("x", "java.lang.RuntimeException", cls, "marked", false, wrappedTrace), classes));
        assertFalse(JUnitFailurePolicy.isStackOverflowOfTestEndingOnLimit(
                new JUnitFailure(null, overflow, cls, "unmarked", false, ""), classes));
        assertFalse(JUnitFailurePolicy.isStackOverflowOfTestEndingOnLimit(
                new JUnitFailure(null, "java.lang.IllegalStateException", cls, "marked", false, ""), classes));
        assertFalse(JUnitFailurePolicy.isStackOverflowOfTestEndingOnLimit(
                new JUnitFailure(null, overflow, cls, "marked", false, ""), null));
    }
}
