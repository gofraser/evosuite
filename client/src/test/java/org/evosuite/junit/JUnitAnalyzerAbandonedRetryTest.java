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

import org.evosuite.Properties;
import org.evosuite.instrumentation.NonInstrumentingClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the runner of a check was abandoned it may still be executing through the loader of
 * that check. The retry with an instrumenting loader that a headless failure triggers must not
 * run then: its cleanup would clear the abandonment and leave the original loader to be
 * released under the orphan.
 */
public class JUnitAnalyzerAbandonedRetryTest {

    private boolean savedSeparateClassLoader;
    private Properties.OutputFormat savedFormat;

    @BeforeEach
    public void setUp() {
        savedSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
        savedFormat = Properties.TEST_FORMAT;
        Properties.USE_SEPARATE_CLASSLOADER = false;
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        JUnitCheckState.resetForPhase();
    }

    @AfterEach
    public void tearDown() throws Exception {
        Properties.USE_SEPARATE_CLASSLOADER = savedSeparateClassLoader;
        Properties.TEST_FORMAT = savedFormat;
        field("checkRunnerAbandoned").setBoolean(null, false);
        field("loader").set(null, null);
        JUnitCheckState.resetForPhase();
    }

    /** Fails like a test that needs a display on a headless machine. */
    public static class NeedsDisplay {
        @org.junit.Test
        public void test0() {
            throw new java.awt.HeadlessException();
        }
    }

    private static Field field(String name) throws Exception {
        Field f = JUnitAnalyzer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test
    public void noInstrumentedRetryOnceTheRunnerWasAbandoned() throws Exception {
        Object original = new NonInstrumentingClassLoader();
        field("loader").set(null, original);
        field("checkRunnerAbandoned").setBoolean(null, true);

        Method run = JUnitAnalyzer.class.getDeclaredMethod(
                "runTests", Class[].class, File.class, Collection.class);
        run.setAccessible(true);
        run.invoke(null, new Class<?>[]{NeedsDisplay.class}, new File("."),
                Collections.singletonList(new File("does-not-exist/Missing.class")));

        assertSame(original, field("loader").get(null));
        assertTrue(field("checkRunnerAbandoned").getBoolean(null),
                "the retry released the loader and cleared the abandonment");
    }
}
