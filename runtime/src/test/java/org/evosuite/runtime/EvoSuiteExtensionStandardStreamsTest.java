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
package org.evosuite.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

import java.io.PrintStream;

/**
 * The extension hands System.out/err back after every test and after the class, with the
 * snapshot kept per invocation.
 */
public class EvoSuiteExtensionStandardStreamsTest {

    @EvoRunnerParameters
    public static class Annotated_ESTest {
    }

    private PrintStream out;
    private PrintStream err;

    @BeforeEach
    public void setUp() {
        out = java.lang.System.out;
        err = java.lang.System.err;
    }

    @AfterEach
    public void tearDown() {
        java.lang.System.setOut(out);
        java.lang.System.setErr(err);
    }

    private static PrintStream stream() {
        return new RedirectingStream(java.lang.System.out);
    }

    private static ExtensionContext context(String id) {
        ExtensionContext context = Mockito.mock(ExtensionContext.class);
        Mockito.when(context.getUniqueId()).thenReturn(id);
        return context;
    }

    @Test
    public void overlappingTestsKeepTheirOwnSnapshot() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        ExtensionContext a = context("[test:a]");
        ExtensionContext b = context("[test:b]");
        PrintStream seenByB = stream();

        extension.beforeEach(a);
        java.lang.System.setOut(seenByB);
        extension.beforeEach(b);
        java.lang.System.setOut(stream());

        extension.afterEach(a);
        Assertions.assertSame(out, java.lang.System.out, "test a got the stream of test b back");
        try {
            extension.afterEach(b);
        } catch (IllegalStateException e) {
            // The thread bookkeeping of the extension is not per invocation, and test a already
            // consumed it; the streams are restored regardless.
        }
        Assertions.assertSame(seenByB, java.lang.System.out);
    }

    @Test
    public void wrapperAddedWhileInitialisingTheClassIsRemovedAfterTheClass() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        ExtensionContext classContext = context("[class:c]");
        ExtensionContext testContext = context("[class:c]/[test:t]");

        extension.beforeAll(classContext);
        // a static initialiser run by beforeAll wraps the streams
        java.lang.System.setOut(stream());
        java.lang.System.setErr(stream());
        extension.beforeEach(testContext);
        extension.afterEach(testContext);
        extension.afterAll(classContext);

        Assertions.assertSame(out, java.lang.System.out);
        Assertions.assertSame(err, java.lang.System.err);
    }
}
