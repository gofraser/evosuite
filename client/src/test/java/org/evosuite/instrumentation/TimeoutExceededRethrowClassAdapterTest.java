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
package org.evosuite.instrumentation;

import com.examples.with.different.packagename.timeout.FinallyUnlock;
import com.examples.with.different.packagename.timeout.SwallowingLoop;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A class under test that catches Throwable must still let TimeoutExceeded out, otherwise a
 * SUT thread looping inside the class under test outlives the kill switch.
 */
public class TimeoutExceededRethrowClassAdapterTest {

    @Test
    public void ordinaryExceptionsAreStillSwallowed() throws Exception {
        Class<?> loop = instrument(SwallowingLoop.class.getName());
        Callable<Void> failing = () -> {
            throw new IllegalStateException("ordinary");
        };
        assertEquals(3, run(loop, failing, 3));
    }

    @Test
    public void timeoutExceededEscapesTheCatchBlock() throws Exception {
        Class<?> loop = instrument(SwallowingLoop.class.getName());
        Callable<Void> timingOut = () -> {
            throw new TestCaseExecutor.TimeoutExceeded();
        };
        try {
            run(loop, timingOut, 3);
            fail("TimeoutExceeded should have escaped the catch (Throwable) block");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof TestCaseExecutor.TimeoutExceeded,
                    "expected TimeoutExceeded, got " + e.getCause());
        }
        assertEquals(0, loop.getField("rounds").getInt(null));
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    public void timeoutExceededPassesThroughAFinallyHandlerWithoutLooping() throws Exception {
        Class<?> cls = instrument(FinallyUnlock.class.getName());
        Object sut = cls.getDeclaredConstructor().newInstance();
        Method run = cls.getMethod("run", Callable.class);
        Callable<Void> timingOut = () -> {
            throw new TestCaseExecutor.TimeoutExceeded();
        };
        try {
            run.invoke(sut, timingOut);
            fail("TimeoutExceeded should have propagated");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof TestCaseExecutor.TimeoutExceeded,
                    "expected TimeoutExceeded, got " + e.getCause());
        }
    }

    /**
     * javac up to Java 8 emitted {@code [handler, handler+1) -> handler} for finally blocks
     * (most of SF110 is compiled that way); javac 17 no longer does, so the shape is built
     * with ASM: {@code static void run(Callable) { try { body.call(); } finally { } }}.
     */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    public void timeoutExceededDoesNotLoopInASelfCoveringFinallyHandler() throws Exception {
        String name = "com/examples/generated/Java8Finally";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new TimeoutExceededRethrowClassAdapter(cw);
        cv.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Ljava/util/concurrent/Callable;)V", null, new String[] {"java/lang/Exception"});
        Label start = new Label(), end = new Label(), handler = new Label(), afterStore = new Label(), done = new Label();
        mv.visitTryCatchBlock(start, end, handler, null);
        mv.visitTryCatchBlock(handler, afterStore, handler, null); // the Java 8 self-covering entry
        mv.visitCode();
        mv.visitLabel(start);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/Callable", "call", "()Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitLabel(afterStore);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cv.visitEnd();
        byte[] bytes = cw.toByteArray();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(System.err));
        Class<?> cls = new ByteClassLoader(getClass().getClassLoader()).define(name.replace('/', '.'), bytes);

        Method run = cls.getMethod("run", Callable.class);
        Callable<Void> timingOut = () -> {
            throw new TestCaseExecutor.TimeoutExceeded();
        };
        try {
            run.invoke(null, timingOut);
            fail("TimeoutExceeded should have propagated");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof TestCaseExecutor.TimeoutExceeded,
                    "expected TimeoutExceeded, got " + e.getCause());
        }
        run.invoke(null, (Callable<Void>) () -> null); // the finally path still returns normally
    }

    private static int run(Class<?> loop, Callable<Void> body, int maxRounds) throws Exception {
        Method method = loop.getMethod("run", Callable.class, int.class);
        return (Integer) method.invoke(null, body, maxRounds);
    }

    private static Class<?> instrument(String className) throws Exception {
        String internalName = className.replace('.', '/');
        byte[] transformed;
        try (InputStream is = TimeoutExceededRethrowClassAdapterTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(is, "Could not find " + className + " on the test classpath");
            ClassReader reader = new ClassReader(is);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            reader.accept(new TimeoutExceededRethrowClassAdapter(writer), ClassReader.EXPAND_FRAMES);
            transformed = writer.toByteArray();
        }
        CheckClassAdapter.verify(new ClassReader(transformed), false, new PrintWriter(System.err));
        ByteClassLoader loader = new ByteClassLoader(TimeoutExceededRethrowClassAdapterTest.class.getClassLoader());
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
