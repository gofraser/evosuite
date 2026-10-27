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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableSnippetEngineAssertionCompileTest {

    private static final Map<String, Type> TYPES = Collections.<String, Type>singletonMap("v0", String.class);
    private static final Map<String, Object> VALUES = Collections.<String, Object>singletonMap("v0", "text");

    @Test
    void assertionCallingNonexistentMethodIsRejected() {
        assertThrows(ExecutableSnippetEngine.SnippetCompilationException.class,
                () -> ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                        "org.junit.jupiter.api.Assertions.assertTrue(v0.noSuchMethod());", TYPES, VALUES));
    }

    @Test
    void assertionWithUnknownTypeIsRejected() {
        assertThrows(ExecutableSnippetEngine.SnippetCompilationException.class,
                () -> ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                        "org.junit.jupiter.api.Assertions.assertNotNull(new NoSuchType());", TYPES, VALUES));
    }

    @Test
    void assertionReferencingJdkInternalsDoesNotHold() throws Throwable {
        assertFalse(ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                "org.junit.jupiter.api.Assertions.assertEquals(5, v0.length()"
                        + " + (sun.misc.Unsafe.class == null ? 1 : 0));", TYPES, VALUES));
    }

    @Test
    void compilableAssertionStillEvaluates() throws Throwable {
        assertTrue(ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                "org.junit.jupiter.api.Assertions.assertEquals(4, v0.length());", TYPES, VALUES));
        assertFalse(ExecutableSnippetEngine.INSTANCE.evaluateAssertion(
                "org.junit.jupiter.api.Assertions.assertEquals(5, v0.length());", TYPES, VALUES));
    }
}
