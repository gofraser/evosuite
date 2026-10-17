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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.reload.ReloadFixtures;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Assertion generation moves every test to a new class loader. A stub whose method takes a
 * class of the code under test has to match the arguments of that loader afterwards.
 */
public class FunctionalMockClassLoaderChangeTest {

    private static final String FIXTURES = ReloadFixtures.class.getName();

    /** Loads the fixtures itself, so that they are other classes than the test's. */
    private static final class ReloadingLoader extends ClassLoader {
        ReloadingLoader() {
            super(FunctionalMockClassLoaderChangeTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(FIXTURES)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String resource = name.replace('.', '/') + ".class";
                    try (InputStream in = getParent().getResourceAsStream(resource)) {
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                return loaded;
            }
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = ReloadFixtures.User.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void aStubTakingAClassOfTheCodeUnderTestMatchesAfterTheClassLoaderChanged() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "stubbed"));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, ReloadFixtures.Dao.class,
                GenericClassFactory.get(ReloadFixtures.Dao.class));
        VariableReference ref = tc.addStatement(mock);
        MethodDescriptor descriptor = new MethodDescriptor(
                ReloadFixtures.Dao.class.getMethod("find", ReloadFixtures.Item.class),
                GenericClassFactory.get(ReloadFixtures.Dao.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(value));
        VariableReference result = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(ReloadFixtures.User.class.getMethod("use", ReloadFixtures.Dao.class),
                        ReloadFixtures.User.class), null, Arrays.asList(ref)));

        ClassLoader loader = new ReloadingLoader();
        tc.changeClassLoader(loader);
        assertNotSame(ReloadFixtures.Dao.class, mock.getTargetClass());

        Scope scope = new Scope();
        for (Statement statement : tc) {
            assertNull(statement.execute(scope, System.out));
        }
        assertEquals("stubbed", result.getObject(scope));
    }
}
