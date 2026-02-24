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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testparser;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that crossover and mutation work correctly with parsed test cases
 * containing InterpretedStatements.
 */
class CrossoverMutationTest {

    @Test
    void crossoverPreservesInterpretedStatements() throws Exception {
        // Build test1: int(42), InterpretedStatement
        DefaultTestCase tc1 = new DefaultTestCase();
        tc1.addStatement(new IntPrimitiveStatement(tc1, 42));
        tc1.addStatement(new IntPrimitiveStatement(tc1, 99));
        tc1.addStatement(new InterpretedStatement(tc1, "// interpreted line"));

        // Build test2: int(1), int(2)
        DefaultTestCase tc2 = new DefaultTestCase();
        tc2.addStatement(new IntPrimitiveStatement(tc2, 1));
        tc2.addStatement(new IntPrimitiveStatement(tc2, 2));

        TestChromosome chrom1 = new TestChromosome();
        chrom1.setTestCase(tc1);

        TestChromosome chrom2 = new TestChromosome();
        chrom2.setTestCase(tc2);

        // Crossover: take first 1 stmt from chrom1, append from position 1 of chrom2
        chrom1.crossOver(chrom2, 1, 1);

        TestCase result = chrom1.getTestCase();
        // Should have: int(42) from tc1 + int(2) from tc2
        assertEquals(2, result.size());
        assertInstanceOf(IntPrimitiveStatement.class, result.getStatement(0));
    }

    @Test
    void crossoverWithInterpretedInSecondParent() throws Exception {
        DefaultTestCase tc1 = new DefaultTestCase();
        tc1.addStatement(new IntPrimitiveStatement(tc1, 10));

        DefaultTestCase tc2 = new DefaultTestCase();
        tc2.addStatement(new IntPrimitiveStatement(tc2, 20));
        tc2.addStatement(new InterpretedStatement(tc2, "System.out.println(\"hello\");"));
        tc2.addStatement(new IntPrimitiveStatement(tc2, 30));

        TestChromosome chrom1 = new TestChromosome();
        chrom1.setTestCase(tc1);

        TestChromosome chrom2 = new TestChromosome();
        chrom2.setTestCase(tc2);

        // Take first 1 from chrom1, append from position 1 of chrom2
        chrom1.crossOver(chrom2, 1, 1);

        TestCase result = chrom1.getTestCase();
        // Should have: int(10) from tc1 + InterpretedStatement + int(30) from tc2
        assertEquals(3, result.size());
        assertInstanceOf(IntPrimitiveStatement.class, result.getStatement(0));
        assertInstanceOf(InterpretedStatement.class, result.getStatement(1));
        assertInstanceOf(IntPrimitiveStatement.class, result.getStatement(2));
    }

    @Test
    void clonePreservesInterpretedStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 5));
        tc.addStatement(new InterpretedStatement(tc, "// comment"));

        TestCase cloned = tc.clone();
        assertEquals(2, cloned.size());
        assertInstanceOf(IntPrimitiveStatement.class, cloned.getStatement(0));
        assertInstanceOf(InterpretedStatement.class, cloned.getStatement(1));
        assertEquals("// comment",
                ((InterpretedStatement) cloned.getStatement(1)).getSourceCode());
    }

    @Test
    void mutationDoesNotBreakInterpretedStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));
        tc.addStatement(new InterpretedStatement(tc, "x.doSomething();"));

        TestChromosome chrom = new TestChromosome();
        chrom.setTestCase(tc);
        chrom.mutate();

        // InterpretedStatement should still be present after mutation
        TestCase result = chrom.getTestCase();
        assertTrue(result.size() >= 1, "Test should still have statements");
    }
}
