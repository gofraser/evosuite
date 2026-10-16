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
package org.evosuite.instrumentation;

import com.examples.with.different.packagename.reflection.SetAccessibleUser;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.runtime.ReflectionAccess;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * End-to-end check for the Defects4J {@code com.fasterxml.jackson.databind.util.ClassUtil}
 * bug: the EvoSuite client JVM is started with {@code --add-opens java.base/java.net=
 * ALL-UNNAMED} (see {@code ExecutionModeUtils}), so an instrumented SUT calling {@code
 * Field#setAccessible(true)} on a JDK member that a stock JVM would deny used to see it
 * silently succeed during the search, and the generated test carried no try/catch for
 * the {@link InaccessibleObjectException} a Defects4J stock test JVM then throws.
 *
 * <p>This loads {@link SetAccessibleUser} through {@link InstrumentingClassLoader} --
 * the same loader used during the search -- with the method call replacements enabled,
 * and checks that the call is routed to {@code org.evosuite.runtime.ReflectionAccess}
 * and denied exactly as a stock JVM would deny it.</p>
 */
public class ReflectionAccessReplacementTest {

    private static final boolean DEFAULT_JVM = RuntimeSettings.mockJVMNonDeterminism;
    private static final boolean DEFAULT_REPLACE_CALLS = Properties.REPLACE_CALLS;

    @BeforeAll
    public static void init() {
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        // surefire starts this JVM with --add-opens java.base/java.net; play the master and
        // declare it EvoSuite's own, so the SUT must not see it (as in a client JVM).
        System.setProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY, "java.base/java.net");
        ReflectionAccess.reloadPrivateAccess();
    }

    @AfterAll
    public static void clearPrivateOpens() {
        System.clearProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY);
        ReflectionAccess.reloadPrivateAccess();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.mockJVMNonDeterminism = DEFAULT_JVM;
        Properties.REPLACE_CALLS = DEFAULT_REPLACE_CALLS;
        MockFramework.disable();
    }

    @Test
    public void testSetAccessibleOnDeniedJdkFieldThrowsInaccessibleObjectException() throws Exception {
        RuntimeSettings.mockJVMNonDeterminism = true;
        Properties.REPLACE_CALLS = true;

        InstrumentingClassLoader cl = new InstrumentingClassLoader();
        MockFramework.enable();
        Class<?> clazz = cl.loadClass(SetAccessibleUser.class.getCanonicalName());

        Object sut = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("makeUrlHostAddressAccessible");

        InvocationTargetException wrapped = Assertions.assertThrows(InvocationTargetException.class,
                () -> m.invoke(sut));

        Throwable cause = wrapped.getCause();
        Assertions.assertTrue(cause instanceof InaccessibleObjectException,
                "Expected InaccessibleObjectException, got " + cause);
        Assertions.assertEquals("java.lang.reflect.AccessibleObject", cause.getStackTrace()[0].getClassName(),
                "Top stack frame must match what verifyException(...) asserts in generated tests");
    }
}
