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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.net.EvoURLStreamHandler;
import org.evosuite.runtime.mock.java.net.URLUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.net.URL;

/**
 * A constructor that is replaced by a static factory of a mock class has to construct the real
 * object while the mock framework is disabled, like all other replaced calls do.
 */
public class MethodCallReplacementDisabledMockTest {

    private boolean wasEnabled;

    @BeforeEach
    public void enableJvmMocking() {
        wasEnabled = MockFramework.isEnabled();
        RuntimeSettings.mockJVMNonDeterminism = true;
        RuntimeSettings.useVNET = true;
        MethodCallReplacementCache.resetSingleton();
    }

    @AfterEach
    public void resetState() {
        if (wasEnabled) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
        RuntimeSettings.mockJVMNonDeterminism = false;
        RuntimeSettings.useVNET = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testConstructorReplacementIsSkippedWhenMockingIsDisabled() throws Exception {
        Method create = instrumentedFactory();
        String spec = "http://www.evosuite.org";

        MockFramework.disable();
        URL real = (URL) create.invoke(null, spec);
        Assertions.assertFalse(URLUtil.getHandler(real) instanceof EvoURLStreamHandler);

        MockFramework.enable();
        URL mocked = (URL) create.invoke(null, spec);
        Assertions.assertTrue(URLUtil.getHandler(mocked) instanceof EvoURLStreamHandler);
    }

    private static Method instrumentedFactory() throws Exception {
        String name = UrlFactory.class.getName();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new MethodCallReplacementClassAdapter(writer, name);
        ClassNode cn = new ClassNode();
        new ClassReader(name).accept(cn, ClassReader.SKIP_FRAMES);
        cn.accept(cv);
        byte[] bytecode = writer.toByteArray();

        ClassLoader loader = new ClassLoader(MethodCallReplacementDisabledMockTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                if (!className.equals(name)) {
                    return super.loadClass(className, resolve);
                }
                synchronized (getClassLoadingLock(className)) {
                    Class<?> loaded = findLoadedClass(className);
                    return loaded != null ? loaded : defineClass(className, bytecode, 0, bytecode.length);
                }
            }
        };
        return loader.loadClass(name).getMethod("create", String.class);
    }

    /**
     * Contains a construction in the shape javac emits (NEW, DUP, ..., INVOKESPECIAL).
     */
    public static class UrlFactory {
        public static URL create(String spec) throws java.net.MalformedURLException {
            return new URL(spec);
        }
    }
}
