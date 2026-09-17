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
package org.evosuite.testcase.fm;

import com.examples.with.different.packagename.fm.ExampleFinalClass;
import com.examples.with.different.packagename.fm.ExampleWithFinalMethod;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.runtime.instrumentation.RemoveFinalClassAdapter;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the largest Defects4J/JacksonDatabind failure class: EvoSuite stubbing
 * a Mockito mock on a method (or mocking a class) that was {@code final} in the original,
 * uninstrumented bytecode. {@link InstrumentingClassLoader} strips {@code final} so the search
 * itself can stub/mock such members, but that stripping happens only for classes loaded through
 * it; the generated JUnit test's own JVM is very likely to load the same class unstripped
 * (referenced classes are verified/loaded before the runtime agent can retransform them, and
 * retransformation cannot change modifiers anyway). So {@code when(mock).finalMethod(...)} calls
 * the real implementation instead of being intercepted (eg NPE from a null matcher argument), and
 * {@code mock(finalClass)} throws outright. {@link RemoveFinalClassAdapter} now also remembers
 * which methods it stripped, so {@link MethodDescriptor#shouldBeMocked()} and
 * {@link FunctionalMockStatement#canBeFunctionalMockedIncludingSUT} can reject such members
 * regardless of what the instrumented {@link Class#getModifiers()}/{@link Method#getModifiers()}
 * report.
 */
public class FunctionalMockFinalMethodTest {

    private final String defaultTargetClass = Properties.TARGET_CLASS;

    @BeforeEach
    public void setUp() {
        RemoveFinalClassAdapter.reset();
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = defaultTargetClass;
        RemoveFinalClassAdapter.reset();
    }

    @Test
    public void finalMethodIsStrippedButRecordedAndNotMocked() throws Exception {
        Properties.TARGET_CLASS = ExampleWithFinalMethod.class.getName();

        InstrumentingClassLoader loader = new InstrumentingClassLoader();
        Class<?> loaded = Class.forName(Properties.TARGET_CLASS, true, loader);

        Method isEnabled = loaded.getMethod("isEnabled", String.class);
        Method isOverridable = loaded.getMethod("isOverridable", String.class);

        // Instrumentation stripped the final modifier from the loaded method...
        assertFalse(Modifier.isFinal(isEnabled.getModifiers()),
                "InstrumentingClassLoader should have stripped the final modifier");
        // ...but RemoveFinalClassAdapter still knows it was final in the original bytecode.
        assertTrue(RemoveFinalClassAdapter.isFinalMethod(isEnabled),
                "RemoveFinalClassAdapter should have recorded isEnabled as originally final");
        assertFalse(RemoveFinalClassAdapter.isFinalMethod(isOverridable),
                "isOverridable was never final and must not be reported as such");

        MethodDescriptor finalMethodDescriptor =
                new MethodDescriptor(isEnabled, GenericClassFactory.get(loaded));
        assertFalse(finalMethodDescriptor.shouldBeMocked(),
                "a method final in the original bytecode must never be stubbed");

        MethodDescriptor overridableMethodDescriptor =
                new MethodDescriptor(isOverridable, GenericClassFactory.get(loaded));
        assertTrue(overridableMethodDescriptor.shouldBeMocked(),
                "a non-final method should still be eligible for stubbing");
    }

    @Test
    public void finalClassIsStrippedButRecordedAndNotMockable() throws Exception {
        // Load ExampleFinalClass as a non-target (dependency) class, the same way a class like
        // com.fasterxml.jackson.databind.type.MapType would be loaded while generating a test
        // for some other target class.
        Properties.TARGET_CLASS = ExampleWithFinalMethod.class.getName();

        InstrumentingClassLoader loader = new InstrumentingClassLoader();
        Class<?> loadedFinalClass = Class.forName(ExampleFinalClass.class.getName(), true, loader);

        assertFalse(Modifier.isFinal(loadedFinalClass.getModifiers()),
                "InstrumentingClassLoader should have stripped the final modifier");
        assertTrue(RemoveFinalClassAdapter.finalClasses.contains(loadedFinalClass.getName()),
                "RemoveFinalClassAdapter should have recorded the class as originally final");

        assertFalse(FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(loadedFinalClass),
                "a class final in the original bytecode must never be mocked, even once instrumented");
    }
}
