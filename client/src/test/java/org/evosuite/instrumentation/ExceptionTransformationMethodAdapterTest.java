/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exception transformation reads the throws clause of each callee when it reaches the call,
 * so it needs no table that is filled before instrumentation starts.
 */
public class ExceptionTransformationMethodAdapterTest {

    static class Callee {
        void throwing() throws IOException {
            throw new IOException();
        }

        void plain() {
        }
    }

    @BeforeAll
    public static void addTestClassesToClassPath() {
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(
                System.getProperty("user.dir") + "/target/test-classes");
    }

    private static MethodNode callMethod(String owner, String name) {
        MethodNode out = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        ExceptionTransformationMethodAdapter adapter = new ExceptionTransformationMethodAdapter(
                out, "Caller", "run", Opcodes.ACC_PUBLIC, "()V");
        adapter.visitCode();
        adapter.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, "()V", false);
        return out;
    }

    @Test
    public void methodWithDeclaredExceptionIsWrapped() {
        String owner = Type.getInternalName(Callee.class);
        MethodNode out = callMethod(owner, "throwing");

        assertEquals(1, out.tryCatchBlocks.size());
        boolean testsDeclaredType = false;
        for (org.objectweb.asm.tree.AbstractInsnNode insn : out.instructions) {
            if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.INSTANCEOF
                    && ((TypeInsnNode) insn).desc.equals(Type.getInternalName(IOException.class))) {
                testsDeclaredType = true;
            }
        }
        assertTrue(testsDeclaredType, "the rethrow must test for the declared exception");
    }

    @Test
    public void methodWithoutDeclaredExceptionIsLeftAlone() {
        MethodNode out = callMethod(Type.getInternalName(Callee.class), "plain");

        assertEquals(0, out.tryCatchBlocks.size());
        assertEquals(1, out.instructions.size());
    }

    @Test
    public void unknownOwnerIsLeftAlone() {
        MethodNode out = callMethod("does/not/Exist", "throwing");

        assertEquals(0, out.tryCatchBlocks.size());
        assertEquals(1, out.instructions.size());
    }
}
