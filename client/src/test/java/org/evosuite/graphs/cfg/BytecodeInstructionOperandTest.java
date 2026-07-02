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
package org.evosuite.graphs.cfg;

import org.evosuite.graphs.GraphPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the EvoObj infrastructure added to {@link BytecodeInstruction} in milestone
 * M0: operand-count, parameter-position, operand-source and INSTANCEOF queries evaluated
 * against the known bytecode of a fixture method.
 */
public class BytecodeInstructionOperandTest {

    /**
     * Fixture whose {@code probe} method exercises: parameters of mixed widths (long/double
     * occupy two local-variable slots each), a field read, an array load, an INSTANCEOF and a
     * method call.
     */
    public static class Fixture {
        int field;

        // slots: this=0, a=1(+2), b=3, c=4(+5), d=6, e=7
        public int probe(long a, int b, double c, Object d, int[] e) {
            int r = b;
            if (d instanceof String) {
                r += 1;
            }
            r += this.field;
            r += e[0];
            r += helper(b);
            r += (int) a;
            r += (int) c;
            return r;
        }

        int helper(int x) {
            return x;
        }
    }

    private static final String CLASS_NAME = Fixture.class.getName();
    private static final String PROBE = "probe(JIDLjava/lang/Object;[I)I";

    private List<BytecodeInstruction> instructions;

    @BeforeEach
    public void setUp() throws Exception {
        GraphPool.clearAll();
        ClassReader cr = new ClassReader(CLASS_NAME);
        CFGClassAdapter adapter = new CFGClassAdapter(getClass().getClassLoader(),
                new ClassWriter(ClassWriter.COMPUTE_MAXS), CLASS_NAME);
        cr.accept(adapter, ClassReader.SKIP_FRAMES);

        ActualControlFlowGraph cfg =
                GraphPool.getInstance(getClass().getClassLoader()).getActualCFG(CLASS_NAME, PROBE);
        assertNotNull(cfg, "CFG should not be null for probe");

        instructions = BytecodeInstructionPool.getInstance(getClass().getClassLoader())
                .getInstructionsIn(CLASS_NAME, PROBE);
        assertNotNull(instructions, "instructions of probe should be registered");
        assertFalse(instructions.isEmpty());
    }

    private BytecodeInstruction findFirst(Predicate<BytecodeInstruction> p) {
        BytecodeInstruction best = null;
        for (BytecodeInstruction bi : instructions) {
            if (p.test(bi)) {
                if (best == null || bi.getInstructionId() < best.getInstructionId()) {
                    best = bi;
                }
            }
        }
        return best;
    }

    private BytecodeInstruction findByOpcode(int opcode) {
        return findFirst(bi -> bi.getASMNode().getOpcode() == opcode);
    }

    @Test
    public void testGetOperandNum() {
        assertEquals(1, findByOpcode(Opcodes.GETFIELD).getOperandNum(), "GETFIELD pops the receiver");
        assertEquals(2, findByOpcode(Opcodes.IALOAD).getOperandNum(), "array load pops arrayref + index");
        assertEquals(1, findFirst(BytecodeInstruction::checkInstanceOf).getOperandNum(), "INSTANCEOF pops one");

        BytecodeInstruction call = findFirst(bi -> bi.getASMNode().getOpcode() == Opcodes.INVOKEVIRTUAL);
        assertNotNull(call, "expected an INVOKEVIRTUAL for helper(b)");
        assertEquals(2, call.getOperandNum(), "instance call pops receiver + one argument");
    }

    @Test
    public void testGetParameterPositionAndWideTypes() {
        // a (long) is parameter 0 although it occupies slots 1 and 2
        BytecodeInstruction lloadA = findByOpcode(Opcodes.LLOAD);
        assertNotNull(lloadA);
        assertTrue(lloadA.isParameter());
        assertEquals(0, lloadA.getParameterPosition(), "long a is parameter 0");

        // c (double) is parameter 2, occupying slots 4 and 5 (b sits between at slot 3)
        BytecodeInstruction dloadC = findByOpcode(Opcodes.DLOAD);
        assertNotNull(dloadC);
        assertTrue(dloadC.isParameter());
        assertEquals(2, dloadC.getParameterPosition(), "double c is parameter 2");

        // b (int) is parameter 1 at slot 3
        BytecodeInstruction iloadB = findFirst(bi -> bi.getASMNode().getOpcode() == Opcodes.ILOAD
                && bi.getLocalVariableSlot() == 3);
        assertNotNull(iloadB, "expected an ILOAD of slot 3 (parameter b)");
        assertTrue(iloadB.isParameter());
        assertEquals(1, iloadB.getParameterPosition(), "int b is parameter 1");
    }

    @Test
    public void testThisIsNotAParameter() {
        // The receiver of the field read (this.field) is an ALOAD of slot 0 -> not a parameter.
        BytecodeInstruction getfield = findByOpcode(Opcodes.GETFIELD);
        List<BytecodeInstruction> sources = getfield.getSourceOfStackInstructionList(0);
        assertNotNull(sources);
        assertFalse(sources.isEmpty(), "GETFIELD receiver must have a producing instruction");
        BytecodeInstruction receiver = sources.get(0);
        assertEquals(0, receiver.getLocalVariableSlot(), "receiver is slot 0 (this)");
        assertFalse(receiver.isParameter(), "this must not be classified as a parameter");
        assertEquals(-1, receiver.getParameterPosition(), "this has no parameter position");
    }

    @Test
    public void testGetSourceOfStackInstructionList() {
        BytecodeInstruction iaload = findByOpcode(Opcodes.IALOAD);
        assertNotNull(iaload);

        // Operand 1 from top = the array reference -> ALOAD of parameter e (index 4).
        List<BytecodeInstruction> arrayRefSources = iaload.getSourceOfStackInstructionList(1);
        assertNotNull(arrayRefSources);
        assertTrue(arrayRefSources.stream().anyMatch(bi -> bi.isParameter() && bi.getParameterPosition() == 4),
                "array reference of e[0] must come from parameter e (index 4)");

        // Operand 0 from top = the index -> a constant, not a parameter.
        List<BytecodeInstruction> indexSources = iaload.getSourceOfStackInstructionList(0);
        assertNotNull(indexSources);
        assertFalse(indexSources.isEmpty());
        assertTrue(indexSources.stream().anyMatch(BytecodeInstruction::isConstant),
                "the array index 0 is a constant");

        // The alias must behave identically.
        assertEquals(iaload.getSourceOfStackInstructionList(1).size(),
                iaload.getSourceListOfStackInstruction(1).size(),
                "getSourceListOfStackInstruction must alias getSourceOfStackInstructionList");
    }

    @Test
    public void testCheckInstanceOf() {
        BytecodeInstruction instanceOf = findFirst(BytecodeInstruction::checkInstanceOf);
        assertNotNull(instanceOf, "expected an INSTANCEOF instruction");
        assertTrue(instanceOf.checkInstanceOf());
        assertEquals("java.lang.String", instanceOf.getInstanceOfCheckingType());

        // Its operand comes from ALOAD of parameter d (index 3).
        List<BytecodeInstruction> operandSources = instanceOf.getSourceOfStackInstructionList(0);
        assertNotNull(operandSources);
        assertTrue(operandSources.stream().anyMatch(bi -> bi.isParameter() && bi.getParameterPosition() == 3),
                "INSTANCEOF operand must be parameter d (index 3)");

        // A non-INSTANCEOF instruction reports false / null.
        BytecodeInstruction getfield = findByOpcode(Opcodes.GETFIELD);
        assertFalse(getfield.checkInstanceOf());
    }
}
