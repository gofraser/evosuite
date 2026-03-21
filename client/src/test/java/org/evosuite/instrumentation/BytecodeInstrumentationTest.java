/**
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
package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BytecodeInstrumentationTest {

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
    }

    @AfterEach
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void testShouldTransform_TT_False() {
        Properties.TT = false;
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        assertFalse(instrumentation.shouldTransform("com.example.MyClass"));
    }

    @Test
    public void testShouldTransform_TT_True_Scope_All() {
        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.ALL;
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        assertTrue(instrumentation.shouldTransform("com.example.MyClass"));
    }

    @Test
    public void testShouldTransform_TT_True_Scope_Target() {
        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.TARGET;
        Properties.TARGET_CLASS = "com.example.TargetClass";
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();

        assertTrue(instrumentation.shouldTransform("com.example.TargetClass"));
        assertTrue(instrumentation.shouldTransform("com.example.TargetClass$Inner"));
        assertFalse(instrumentation.shouldTransform("com.example.OtherClass"));
    }

    @Test
    public void testShouldTransform_TT_True_Scope_Prefix() {
        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.PREFIX;
        Properties.PROJECT_PREFIX = "com.example";
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();

        assertTrue(instrumentation.shouldTransform("com.example.MyClass"));
        assertTrue(instrumentation.shouldTransform("com.example.sub.MyClass"));
        assertFalse(instrumentation.shouldTransform("com.other.MyClass"));
    }

    @Test
    public void testCheckIfEvoSuitePackage() {
        assertTrue(BytecodeInstrumentation.checkIfEvoSuitePackage("org.evosuite.runtime.Runtime"));
        assertTrue(BytecodeInstrumentation.checkIfEvoSuitePackage("de.unisb.cs.st.evosuite.SomeClass"));
        assertTrue(BytecodeInstrumentation.checkIfEvoSuitePackage("org.exsyst.SomeClass"));

        assertFalse(BytecodeInstrumentation.checkIfEvoSuitePackage("com.example.MyClass"));
        assertFalse(BytecodeInstrumentation.checkIfEvoSuitePackage("java.lang.String"));
    }

    @Test
    public void testRetriesWithoutLoopCounterOnAsmFrameFailure() {
        Properties.MAX_LOOP_ITERATIONS = 1;
        RetryProbeInstrumentation instrumentation = new RetryProbeInstrumentation(1);
        ClassReader reader = new ClassReader(createSimpleClassBytes("sample/Bug9"));

        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(), "sample/Bug9", reader);

        Assertions.assertArrayEquals(RetryProbeInstrumentation.SUCCESS_BYTES, transformed);
        Assertions.assertEquals(2, instrumentation.invocations);
        Assertions.assertTrue(instrumentation.firstInvocationEnabledLoopCounter);
        Assertions.assertTrue(instrumentation.secondInvocationEnabledLoopCounter);
        // First attempt COMPUTE_FRAMES, then retry with COMPUTE_MAXS keeping loop counter.
        Assertions.assertEquals(ClassWriter.COMPUTE_FRAMES, instrumentation.writerFlagsPerInvocation[0]);
        Assertions.assertEquals(ClassWriter.COMPUTE_MAXS, instrumentation.writerFlagsPerInvocation[1]);
    }

    @Test
    public void testFallsBackToComputeMaxsWhenFramesFails() {
        Properties.MAX_LOOP_ITERATIONS = 1;
        // Fail initial COMPUTE_FRAMES and retry COMPUTE_MAXS (both with loop counter)
        RetryProbeInstrumentation instrumentation = new RetryProbeInstrumentation(2);
        ClassReader reader = new ClassReader(createSimpleClassBytes("sample/Bug11"));

        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(), "sample/Bug11", reader);

        Assertions.assertArrayEquals(RetryProbeInstrumentation.SUCCESS_BYTES, transformed);
        Assertions.assertEquals(3, instrumentation.invocations);
        Assertions.assertEquals(ClassWriter.COMPUTE_FRAMES, instrumentation.writerFlagsPerInvocation[0]);
        Assertions.assertEquals(ClassWriter.COMPUTE_MAXS, instrumentation.writerFlagsPerInvocation[1]);
        Assertions.assertEquals(ClassWriter.COMPUTE_FRAMES, instrumentation.writerFlagsPerInvocation[2]);
    }

    @Test
    public void testFallsBackToComputeMaxsWhenLoopCounterDisabled() {
        Properties.MAX_LOOP_ITERATIONS = -1;
        // Loop counter disabled: skips retry 1, goes straight to COMPUTE_MAXS fallback
        RetryProbeInstrumentation instrumentation = new RetryProbeInstrumentation(1);
        ClassReader reader = new ClassReader(createSimpleClassBytes("sample/Bug10"));

        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(), "sample/Bug10", reader);

        Assertions.assertArrayEquals(RetryProbeInstrumentation.SUCCESS_BYTES, transformed);
        Assertions.assertEquals(2, instrumentation.invocations);
        Assertions.assertEquals(ClassWriter.COMPUTE_FRAMES, instrumentation.writerFlagsPerInvocation[0]);
        Assertions.assertEquals(ClassWriter.COMPUTE_MAXS, instrumentation.writerFlagsPerInvocation[1]);
    }

    private static byte[] createSimpleClassBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class RetryProbeInstrumentation extends BytecodeInstrumentation {
        private static final byte[] SUCCESS_BYTES = new byte[]{7, 1, 9};
        private final int failCount;
        private int invocations = 0;
        private boolean firstInvocationEnabledLoopCounter = false;
        private boolean secondInvocationEnabledLoopCounter = true;
        private final int[] writerFlagsPerInvocation = new int[4];

        RetryProbeInstrumentation(int failCount) {
            this.failCount = failCount;
        }

        @Override
        protected byte[] transformBytesInternal(ClassLoader classLoader, String className, String classNameWithDots,
                                                ClassReader reader, int readFlags, boolean enableLoopCounter,
                                                int writerFlags) {
            int idx = invocations;
            invocations++;
            if (idx < writerFlagsPerInvocation.length) {
                writerFlagsPerInvocation[idx] = writerFlags;
            }
            if (idx == 0) {
                firstInvocationEnabledLoopCounter = enableLoopCounter;
            } else if (idx == 1) {
                secondInvocationEnabledLoopCounter = enableLoopCounter;
            }
            if (idx < failCount) {
                throw createAsmLikeFrameMergeFailure();
            }
            return SUCCESS_BYTES;
        }

        private ArrayIndexOutOfBoundsException createAsmLikeFrameMergeFailure() {
            ArrayIndexOutOfBoundsException ex = new ArrayIndexOutOfBoundsException("Index -1 out of bounds for length 0");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("org.evosuite.shaded.org.objectweb.asm.Frame", "merge", "Frame.java", 1280),
                    new StackTraceElement("org.evosuite.shaded.org.objectweb.asm.MethodWriter", "computeAllFrames", "MethodWriter.java", 1612)
            });
            return ex;
        }
    }
}
