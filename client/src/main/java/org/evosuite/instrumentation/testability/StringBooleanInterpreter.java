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
package org.evosuite.instrumentation.testability;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.List;

/**
 * <p>StringBooleanInterpreter class.</p>
 *
 * @author Gordon Fraser
 */
public class StringBooleanInterpreter extends BasicInterpreter {

    /**
     * Constant <code>STRING_BOOLEAN</code>.
     */
    public static final BasicValue STRING_BOOLEAN = new BasicValue(null);

    public StringBooleanInterpreter() {
        super(Opcodes.ASM9);
    }


    /* (non-Javadoc)
     * @see org.objectweb.asm.tree.analysis.BasicInterpreter#naryOperation(
     * org.objectweb.asm.tree.AbstractInsnNode, java.util.List)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public BasicValue naryOperation(AbstractInsnNode insn,
                                    @SuppressWarnings("rawtypes") List values)
            throws AnalyzerException {
        if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
            MethodInsnNode mn = (MethodInsnNode) insn;
            String helperName = Type.getInternalName(StringHelper.class);
            if (mn.owner.equals(helperName)
                    && mn.name.startsWith("String")) {
                return STRING_BOOLEAN;
            }
        }
        return super.naryOperation(insn, values);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link BasicInterpreter#merge(BasicValue, BasicValue)} falls back to
     * {@link BasicValue#UNINITIALIZED_VALUE} whenever the two merged values are not equal. Left
     * unoverridden, that silently discards the {@link #STRING_BOOLEAN} tag at every control-flow
     * join where a tagged value meets an untagged one (e.g. a boolean local initialised to a plain
     * {@code false} and later reassigned from a {@code String.equals} call). Once the tag is lost,
     * the consuming jump is never rewritten to be sign-aware, and the signed branch distance
     * produced by {@link StringHelper} is read with plain boolean semantics instead, where any
     * nonzero value - including a large negative "false" distance - counts as true.
     *
     * <p>Widening instead of losing the tag is safe: a merged plain boolean is still exactly 0 or 1,
     * and the sign-aware test ({@code <= 0} is false, {@code > 0} is true) agrees with plain boolean
     * semantics for those values too. So treating the merge result as {@link #STRING_BOOLEAN} never
     * changes behaviour for genuine booleans, while treating a {@link #STRING_BOOLEAN} as a plain int
     * is exactly what causes the bug.
     */
    @Override
    public BasicValue merge(final BasicValue value1, final BasicValue value2) {
        if (value1 == STRING_BOOLEAN || value2 == STRING_BOOLEAN) {
            return STRING_BOOLEAN;
        }
        return super.merge(value1, value2);
    }

}
