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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.junit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * javac catches OutOfMemoryError itself and prints "An exception has occurred in the compiler"
 * instead of judging the test. Treating that as "the test does not compile" deleted good tests:
 * 43_lilith's LoggingProto, whose tests pull in a 32 KB protobuf builder, lost tests that way.
 */
public class JUnitAnalyzerCompilerCrashTest {

    private static boolean crashed(String compilerOutput) throws Exception {
        Method m = JUnitAnalyzer.class.getDeclaredMethod("crashedInsteadOfRejecting", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, compilerOutput);
    }

    @Test
    public void compilerOutOfMemoryIsACrash() throws Exception {
        assertTrue(crashed("An exception has occurred in the compiler (17.0.20.1). "
                + "Please file a bug against the Java compiler via the Java bug reporting page\n"
                + "java.lang.OutOfMemoryError: Java heap space\n"));
    }

    @Test
    public void compilerStackOverflowIsACrash() throws Exception {
        assertTrue(crashed("java.lang.StackOverflowError\n\tat com.sun.tools.javac.comp.Attr.attribTree"));
    }

    @Test
    public void compilerOutOfMemoryIsMemoryExhaustion() {
        // What javac printed when its heap, shared with the client, ran out while opening a jar.
        assertTrue(JUnitAnalyzer.reportsOutOfMemory("An exception has occurred in the compiler (17.0.20.1).\n"
                + "java.lang.OutOfMemoryError: Java heap space\n"
                + "\tat jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.initCEN(ZipFileSystem.java:1536)\n"));
        assertFalse(JUnitAnalyzer.reportsOutOfMemory("java.lang.StackOverflowError\n"));
        assertFalse(JUnitAnalyzer.reportsOutOfMemory(null));
    }

    @Test
    public void outOfMemoryInsideAJavacInfrastructureFailureIsRecognised() {
        RuntimeException wrapped = new RuntimeException("provider failed",
                new java.io.UncheckedIOException(new java.io.IOException(
                        new OutOfMemoryError("Java heap space"))));
        assertTrue(JUnitAnalyzer.isOutOfMemory(wrapped));
        assertTrue(JUnitAnalyzer.isOutOfMemory(new OutOfMemoryError()));
        assertFalse(JUnitAnalyzer.isOutOfMemory(new java.util.NoSuchElementException()));
        assertFalse(JUnitAnalyzer.isOutOfMemory(null));
    }

    @Test
    public void ordinaryCompilationErrorsAreNotACrash() throws Exception {
        assertFalse(crashed(""));
        assertFalse(crashed(null));
        assertFalse(crashed("warning: [options] bootstrap class path not set in conjunction with -source 8"));
    }
}
