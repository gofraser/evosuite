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

import org.evosuite.Properties;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two properties of the array-size guard.
 *
 * <p>First, its scope: {@code array_limit} bounds what a <em>test</em> may ask for, so it applies
 * to the classes under analysis and not to third-party libraries sizing their own buffers.
 *
 * <p>Second, how a refusal is reported: it is EvoSuite deciding not to spend the memory, not the
 * execution watchdog firing, so it has to surface as {@link TooManyResourcesException} rather than
 * {@code TestCaseExecutor.TimeoutExceeded}. Reporting it as a timeout also means
 * {@link TimeoutExceededRethrowAdapter} tears it out of any catch block in the class under test,
 * which no ordinary exception would do.
 */
public class ArrayAllocationLimitMethodAdapterTest {

    private static final String VICTIM = "com.examples.with.different.packagename.ArrayAllocationVictim";

    /**
     * Deliberately above 256: below that, {@code visitMultiANewArrayInsn} emits an extra
     * dimension-count check whose branch leaves the stack in an inconsistent state, which is a
     * separate, pre-existing defect of that adapter and not what is under test here.
     */
    private static final int LIMIT = 1000;

    private int originalArrayLimit;

    private String originalTargetClass;

    private Class<?> instrumented;

    @BeforeEach
    public void setUp() throws Exception {
        Properties.getInstance().resetToDefaults();
        originalArrayLimit = Properties.ARRAY_LIMIT;
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.ARRAY_LIMIT = LIMIT;
        // The guard only applies to classes under analysis, so the fixture has to be one.
        Properties.TARGET_CLASS = VICTIM;
        instrumented = instrument(VICTIM);
    }

    @AfterEach
    public void tearDown() {
        Properties.ARRAY_LIMIT = originalArrayLimit;
        Properties.TARGET_CLASS = originalTargetClass;
    }

    @Test
    public void allocationWithinTheLimitIsUntouched() throws Exception {
        assertEquals(10, invoke("newArray", 10));
        assertEquals(10, invoke("aNewArray", 10));
        assertEquals(5, invoke("multiNewArray", 5, 5));
        assertEquals(10, invoke("stringBuilderCapacity", 10));
    }

    @Test
    public void oversizedNewArrayIsRejected() {
        assertRejected("newArray", LIMIT * 100);
    }

    @Test
    public void oversizedANewArrayIsRejected() {
        assertRejected("aNewArray", LIMIT * 100);
    }

    @Test
    public void oversizedMultiNewArrayIsRejected() {
        assertRejected("multiNewArray", LIMIT * 100, LIMIT * 100);
    }

    @Test
    public void oversizedStringBuilderIsRejected() {
        assertRejected("stringBuilderCapacity", LIMIT * 100);
    }

    /**
     * A refusal must behave like an ordinary exception: code that catches {@code Throwable} around
     * its own buffer allocation keeps working. Signalled as a timeout it would not, because
     * {@link TimeoutExceededRethrowAdapter} forces {@code TimeoutExceeded} back out of every catch
     * block it instruments.
     */
    @Test
    public void rejectionCanStillBeHandledByTheClassUnderTest() throws Exception {
        assertEquals(-1, invoke("newArrayWithOwnCatch", LIMIT * 100));
    }

    /**
     * The other half of the regression: {@code array_limit} bounds what a <em>test</em> may ask
     * for. A third-party library sizing its own buffer from its own constants (jxl asks for
     * 5 MB) must not be refused, or every call into that library dies.
     */
    @Test
    public void libraryClassesAreNotGuarded() throws Exception {
        Properties.TARGET_CLASS = "some.other.Class";
        instrumented = instrument(VICTIM);

        assertEquals(LIMIT * 100, invoke("newArray", LIMIT * 100));
    }

    // ---------------------------------------------------------------- helpers

    private void assertRejected(String method, Object... args) {
        try {
            invoke(method, args);
            fail("Expected the oversized allocation in " + method + " to be rejected");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertNotNull(cause);
            assertTrue(cause instanceof TooManyResourcesException,
                    "Expected TooManyResourcesException but got " + cause);
            assertFalse(cause instanceof TestCaseExecutor.TimeoutExceeded,
                    "Allocation rejection must not be signalled as a timeout");
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
    }

    private int invoke(String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        java.util.Arrays.fill(types, int.class);
        Method method = instrumented.getMethod(name, types);
        return (Integer) method.invoke(null, args);
    }

    /**
     * Applies the same two adapters that {@link NonTargetClassAdapter} puts around every method of
     * a non-target class, in the same order, and loads the result in an isolated class loader.
     */
    private static Class<?> instrument(String className) throws Exception {
        String internalName = className.replace('.', '/');
        byte[] transformed;
        try (InputStream is = ArrayAllocationLimitMethodAdapterTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(is, "Could not find " + className + " on the test classpath");
            ClassReader reader = new ClassReader(is);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    mv = new TimeoutExceededRethrowAdapter(mv);
                    return new ArrayAllocationLimitMethodAdapter(mv, internalName, name, access, descriptor);
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            transformed = writer.toByteArray();
        }

        CheckClassAdapter.verify(new ClassReader(transformed), false, new PrintWriter(System.err));

        ByteClassLoader loader = new ByteClassLoader(
                ArrayAllocationLimitMethodAdapterTest.class.getClassLoader());
        return loader.define(className, transformed);
    }

    private static final class ByteClassLoader extends ClassLoader {
        private ByteClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
