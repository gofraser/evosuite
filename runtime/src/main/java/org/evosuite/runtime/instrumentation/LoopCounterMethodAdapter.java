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

import org.evosuite.runtime.LoopCounter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;

import java.util.List;

/**
 * Add loop check before each jump instruction.
 *
 * <p>Note: not all jumps represent a loop (eg, for/while).
 * Non-loops are for examples if/switch.
 * However, such extra instrumentation should not really be a problem.
 *
 * <p>Created by Andrea Arcuri on 29/03/15.
 */
public class LoopCounterMethodAdapter extends MethodVisitor {

    private static final String LOOP_COUNTER = Type.getInternalName(LoopCounter.class);

    private final AnalyzerAdapter analyzer;

    /**
     * <p>Constructor for LoopCounterMethodAdapter.</p>
     *
     * @param mv         a {@link org.objectweb.asm.MethodVisitor} object.
     * @param className  a {@link java.lang.String} object.
     * @param access     method modifiers.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     */
    public LoopCounterMethodAdapter(MethodVisitor mv, String className, int access, String methodName, String desc) {
        this(new AnalyzerAdapter(className, access, methodName, desc, mv));
    }

    private LoopCounterMethodAdapter(AnalyzerAdapter analyzer) {
        super(Opcodes.ASM9, analyzer);
        this.analyzer = analyzer;
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack + 2, maxLocals);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (!hasUninitializedValue()) {
            addInstrumentation();
        }
        super.visitJumpInsn(opcode, label);
    }

    /**
     * Checks whether injecting a {@code checkLoop()} call at the current
     * position could leave a {@code Label} (uninitialized reference) or
     * {@code UNINITIALIZED_THIS} value on the stack/locals at a frame
     * boundary, which the JVM verifier rejects.
     *
     * <p>The injected sequence is {@code INVOKESTATIC + LDC + INVOKEVIRTUAL}
     * — it never reads any local or any pre-existing stack value, and its
     * net stack effect is zero. The only verifier concern is the presence
     * of uninitialized markers in the surrounding frame, which would
     * already fail verification at the next merge point regardless of our
     * injection. We therefore treat null frames (unreachable / unknown
     * frame state) as safe for every jump opcode: skipping conditional
     * back-edges here is what previously left tight loops uninstrumented
     * (e.g. {@code IFLE} as a loop back-edge would not be guarded), so
     * a runaway SUT loop could not be terminated by {@code LoopCounter}.
     */
    private boolean hasUninitializedValue() {
        return containsUninitialized(analyzer.locals)
                || containsUninitialized(analyzer.stack);
    }

    private boolean containsUninitialized(List<Object> values) {
        if (values == null) {
            return false;
        }
        for (Object value : values) {
            if (value instanceof Label || value == Opcodes.UNINITIALIZED_THIS) {
                return true;
            }
        }
        return false;
    }

    private void addInstrumentation() {

        int index = LoopCounter.getInstance().getNewIndex();

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LOOP_COUNTER,
                "getInstance", "()L" + LOOP_COUNTER + ";", false);

        mv.visitLdcInsn(index);

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOOP_COUNTER,
                "checkLoop", "(I)V", false);
    }
}
