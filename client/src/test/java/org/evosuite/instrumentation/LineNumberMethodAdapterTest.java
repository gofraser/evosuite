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
import org.evosuite.testcase.execution.ExecutionTracer;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A constructor whose {@code super(...)} arguments throw never reaches the code after the super
 * call, so lines that precede it have to be traced where they stand. Deferring them lost every
 * line of such a class: a test that built it with a null argument reported 0% line coverage
 * although the constructor had run.
 */
public class LineNumberMethodAdapterTest {

    private static final String VICTIM =
            "com.examples.with.different.packagename.SuperArgumentThrowsVictim";

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = VICTIM;
        ExecutionTracer.setThread(Thread.currentThread());
        ExecutionTracer.getExecutionTracer().clear();
        ExecutionTracer.enable();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        ExecutionTracer.getExecutionTracer().clear();
        ExecutionTracer.disable();
    }

    @Test
    public void lineBeforeSuperCallIsTracedWhenTheSuperArgumentThrows() throws Exception {
        Constructor<?> ctor = instrument().getConstructor(String.class);

        try {
            ctor.newInstance((Object) null);
            fail("Expected the null argument to throw");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof NullPointerException);
        }

        assertFalse(ExecutionTracer.getExecutionTracer().getTrace().getCoveredLines().isEmpty(),
                "the constructor line before super() must be covered");
    }

    @Test
    public void linesBeforeAndAfterSuperCallAreBothTraced() throws Exception {
        assertNotNull(instrument().getConstructor(String.class).newInstance("abc"));

        // The super() line and the closing return line.
        assertEquals(2, ExecutionTracer.getExecutionTracer().getTrace().getCoveredLines().size());
    }

    private static Class<?> instrument() throws Exception {
        String internalName = VICTIM.replace('.', '/');
        byte[] transformed;
        try (InputStream is = LineNumberMethodAdapterTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(is);
            ClassReader reader = new ClassReader(is);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new LineNumberMethodAdapter(mv, access, VICTIM, name, descriptor);
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            transformed = writer.toByteArray();
        }
        CheckClassAdapter.verify(new ClassReader(transformed), false, new PrintWriter(System.err));
        return new ByteClassLoader(transformed).define();
    }

    private static final class ByteClassLoader extends ClassLoader {
        private final byte[] bytes;

        private ByteClassLoader(byte[] bytes) {
            super(LineNumberMethodAdapterTest.class.getClassLoader());
            this.bytes = bytes;
        }

        private Class<?> define() {
            return defineClass(VICTIM, bytes, 0, bytes.length);
        }
    }
}
