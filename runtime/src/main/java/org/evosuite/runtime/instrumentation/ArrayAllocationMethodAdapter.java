/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.ArrayAllocationBudget;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * Emits cumulative array-allocation accounting around JVM array opcodes.
 *
 * <p>This adapter deliberately contains no client-only dependencies or
 * per-allocation size checks. The client adapter subclasses it and adds those
 * checks while reusing this emission logic.</p>
 */
public class ArrayAllocationMethodAdapter extends GeneratorAdapter {

    private static final int REFERENCE_ELEMENT_WIDTH_BYTES = 8;

    public ArrayAllocationMethodAdapter(MethodVisitor mv, String className, int access,
                                        String methodName, String desc) {
        super(Opcodes.ASM9, mv, access, methodName, desc);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (opcode == Opcodes.NEWARRAY) {
            emitCumulativeBudgetCheck(primitiveElementWidthBytes(operand));
        }
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (opcode == Opcodes.ANEWARRAY) {
            emitCumulativeBudgetCheck(REFERENCE_ELEMENT_WIDTH_BYTES);
        }
        super.visitTypeInsn(opcode, type);
    }

    /**
     * Charge every level MULTIANEWARRAY allocates -- not just the product of all
     * dimensions times the leaf width, which ignores every reference array but the
     * leaf and charges zero for a populated outer dimension whose leaves are
     * themselves zero-length (e.g. {@code new byte[1000][0]}: see
     * {@link ArrayAllocationBudget#checkAndRecord(int[], int)} for the accounting).
     * The JVM leaves dimensions on the operand stack in declaration order, so spill
     * and restore them around building the dimensions array and the runtime call.
     */
    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        Map<Integer, Integer> locals = new HashMap<>();
        for (int i = dims - 1; i >= 0; i--) {
            int local = newLocal(Type.INT_TYPE);
            storeLocal(local);
            locals.put(i, local);
        }

        push(dims);
        newArray(Type.INT_TYPE);
        for (int i = 0; i < dims; i++) {
            dup();
            push(i);
            loadLocal(locals.get(i));
            arrayStore(Type.INT_TYPE);
        }
        push(multiArrayElementWidthBytes(desc, dims));
        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(ArrayAllocationBudget.class),
                "checkAndRecord", "([II)V", false);

        for (int i = 0; i < dims; i++) {
            loadLocal(locals.get(i));
        }
        super.visitMultiANewArrayInsn(desc, dims);
    }

    /**
     * Leaves the element count on the stack for the array instruction that
     * follows it, while recording the corresponding byte charge.
     */
    protected final void emitCumulativeBudgetCheck(int elementWidthBytes) {
        visitInsn(Opcodes.DUP);
        visitInsn(Opcodes.I2L);
        push(elementWidthBytes);
        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(ArrayAllocationBudget.class),
                "checkAndRecord", "(JI)V", false);
    }

    /**
     * Maps a NEWARRAY primitive-type operand to the width of one element.
     */
    protected static int primitiveElementWidthBytes(int operand) {
        switch (operand) {
            case Opcodes.T_BOOLEAN:
            case Opcodes.T_BYTE:
                return 1;
            case Opcodes.T_CHAR:
            case Opcodes.T_SHORT:
                return 2;
            case Opcodes.T_FLOAT:
            case Opcodes.T_INT:
                return 4;
            case Opcodes.T_DOUBLE:
            case Opcodes.T_LONG:
                return 8;
            default:
                return REFERENCE_ELEMENT_WIDTH_BYTES;
        }
    }

    /**
     * Returns the width of the leaf component allocated by a
     * MULTIANEWARRAY instruction.
     */
    protected static int multiArrayElementWidthBytes(String desc, int dims) {
        String remainder = dims < desc.length() ? desc.substring(dims) : "";
        if (remainder.isEmpty()) {
            return REFERENCE_ELEMENT_WIDTH_BYTES;
        }
        switch (remainder.charAt(0)) {
            case 'Z':
            case 'B':
                return 1;
            case 'C':
            case 'S':
                return 2;
            case 'F':
            case 'I':
                return 4;
            case 'D':
            case 'J':
                return 8;
            default:
                return REFERENCE_ELEMENT_WIDTH_BYTES;
        }
    }
}
