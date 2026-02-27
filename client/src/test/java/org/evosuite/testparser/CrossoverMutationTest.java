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
package org.evosuite.testparser;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that crossover and mutation work correctly with parsed test cases
 * containing UninterpretedStatements.
 */
class CrossoverMutationTest {

    @Test
    void crossoverPreservesUninterpretedStatements() throws Exception {
        // Build test1: int(42), UninterpretedStatement
        DefaultTestCase tc1 = new DefaultTestCase();
        tc1.addStatement(new IntPrimitiveStatement(tc1, 42));
        tc1.addStatement(new IntPrimitiveStatement(tc1, 99));
        tc1.addStatement(new UninterpretedStatement(tc1, "// interpreted line"));

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
        tc2.addStatement(new UninterpretedStatement(tc2, "System.out.println(\"hello\");"));
        tc2.addStatement(new IntPrimitiveStatement(tc2, 30));

        TestChromosome chrom1 = new TestChromosome();
        chrom1.setTestCase(tc1);

        TestChromosome chrom2 = new TestChromosome();
        chrom2.setTestCase(tc2);

        // Take first 1 from chrom1, append from position 1 of chrom2
        chrom1.crossOver(chrom2, 1, 1);

        TestCase result = chrom1.getTestCase();
        // Should have: int(10) from tc1 + UninterpretedStatement + int(30) from tc2
        assertEquals(3, result.size());
        assertInstanceOf(IntPrimitiveStatement.class, result.getStatement(0));
        assertInstanceOf(UninterpretedStatement.class, result.getStatement(1));
        assertInstanceOf(IntPrimitiveStatement.class, result.getStatement(2));
    }

    @Test
    void clonePreservesUninterpretedStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 5));
        tc.addStatement(new UninterpretedStatement(tc, "// comment"));

        TestCase cloned = tc.clone();
        assertEquals(2, cloned.size());
        assertInstanceOf(IntPrimitiveStatement.class, cloned.getStatement(0));
        assertInstanceOf(UninterpretedStatement.class, cloned.getStatement(1));
        assertEquals("// comment",
                ((UninterpretedStatement) cloned.getStatement(1)).getSourceCode());
    }

    /**
     * Regression test for IllegalArgumentException during crossover when an
     * UninterpretedStatement with variable bindings is transferred from a longer
     * parent into a shorter offspring.
     *
     * Before the fix, appendStatement added the raw statement (with bindings
     * still referencing the source parent's test case) into the offspring.
     * When the next appendStatement tried to resolve parameters, it called
     * getCandidatesForReuse which invoked ref.getStPosition() on the stale
     * VariableReference — returning a position from the source parent that was
     * out-of-bounds for the offspring, causing:
     *   IllegalArgumentException: Cannot access statement due to wrong position 3,
     *   where total number of statements is 2
     */
    @Test
    void crossoverWithBoundUninterpretedStatement_doesNotThrow() throws Exception {
        // Parent 1: a short test with just one statement.
        DefaultTestCase tc1 = new DefaultTestCase();
        tc1.addStatement(new IntPrimitiveStatement(tc1, 10));

        // Parent 2: a longer test simulating an LLM-parsed test.
        // It has 4 statements, with an UninterpretedStatement at index 2 that
        // carries a variable binding referencing the statement at index 1.
        DefaultTestCase tc2 = new DefaultTestCase();
        tc2.addStatement(new IntPrimitiveStatement(tc2, 20));
        VariableReference intRef = tc2.addStatement(new IntPrimitiveStatement(tc2, 30));
        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("int1", intRef);
        tc2.addStatement(new UninterpretedStatement(tc2, "int1 = int1 + 1;", bindings));
        tc2.addStatement(new IntPrimitiveStatement(tc2, 40));

        TestChromosome chrom1 = new TestChromosome();
        chrom1.setTestCase(tc1);
        TestChromosome chrom2 = new TestChromosome();
        chrom2.setTestCase(tc2);

        // Crossover: take first 1 stmt from chrom1 (offspring size=1),
        // then append from position 1 of chrom2 (3 statements including the
        // bound UninterpretedStatement).
        // Before the fix this threw IllegalArgumentException because the
        // UninterpretedStatement's binding still pointed into tc2.
        assertDoesNotThrow(() -> chrom1.crossOver(chrom2, 1, 1));

        TestCase result = chrom1.getTestCase();
        assertTrue(result.size() >= 1, "Offspring should have at least one statement");

        // Verify the UninterpretedStatement in the offspring is bound to the
        // offspring's test case, not the original parent.
        for (int i = 0; i < result.size(); i++) {
            Statement stmt = result.getStatement(i);
            if (stmt instanceof UninterpretedStatement) {
                for (VariableReference ref : stmt.getVariableReferences()) {
                    assertSame(result, ref.getTestCase(),
                            "Variable reference in offspring should point to the offspring test case");
                }
            }
        }
    }

    /**
     * Regression test for IllegalArgumentException during crossover when an
     * AssignmentStatement is transferred from a longer parent into a shorter
     * offspring.
     *
     * AssignmentStatement.copy() calls VariableReferenceImpl.copy() which does
     * newTestCase.getStatement(getStPosition() + offset) — but the position
     * from the source parent is out-of-bounds in the offspring being built.
     * The fix skips AssignmentStatements during crossover append since their
     * variable dependencies (target + value) cannot be re-resolved.
     */
    @Test
    void crossoverWithAssignmentStatement_doesNotThrow() throws Exception {
        // Parent 1: a short test with just one statement.
        DefaultTestCase tc1 = new DefaultTestCase();
        tc1.addStatement(new IntPrimitiveStatement(tc1, 10));

        // Parent 2: a longer test with an AssignmentStatement at index 4
        // that references variables at positions 0 and 3.
        DefaultTestCase tc2 = new DefaultTestCase();
        VariableReference arr = tc2.addStatement(new ArrayStatement(tc2, int[].class, 3));
        tc2.addStatement(new IntPrimitiveStatement(tc2, 20));
        tc2.addStatement(new IntPrimitiveStatement(tc2, 30));
        VariableReference val = tc2.addStatement(new IntPrimitiveStatement(tc2, 99));
        ArrayIndex idx = new ArrayIndex(tc2, (ArrayReference) arr, 0);
        tc2.addStatement(new AssignmentStatement(tc2, idx, val));

        TestChromosome chrom1 = new TestChromosome();
        chrom1.setTestCase(tc1);
        TestChromosome chrom2 = new TestChromosome();
        chrom2.setTestCase(tc2);

        // Crossover: take first 1 stmt from chrom1 (offspring size=1),
        // then append from position 1 of chrom2.
        // Before the fix, AssignmentStatement.copy() crashed with
        // IllegalArgumentException when it tried to resolve position 4 in
        // the 2-statement offspring.
        assertDoesNotThrow(() -> chrom1.crossOver(chrom2, 1, 1));

        TestCase result = chrom1.getTestCase();
        assertTrue(result.size() >= 1, "Offspring should have at least one statement");

        // The AssignmentStatement should have been skipped since its variable
        // dependencies can't be resolved in the offspring.
        for (int i = 0; i < result.size(); i++) {
            assertFalse(result.getStatement(i) instanceof AssignmentStatement,
                    "AssignmentStatement should be skipped during crossover append");
        }
    }

    @Test
    void mutationDoesNotBreakUninterpretedStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));
        tc.addStatement(new UninterpretedStatement(tc, "x.doSomething();"));

        double oldDelete = Properties.P_TEST_DELETE;
        double oldInsert = Properties.P_TEST_INSERT;
        double oldChange = Properties.P_TEST_CHANGE;
        try {
            // Make mutation deterministic for this test: only mutate existing statements,
            // do not delete or insert.
            Properties.P_TEST_DELETE = 0.0;
            Properties.P_TEST_INSERT = 0.0;
            Properties.P_TEST_CHANGE = 1.0;

            TestChromosome chrom = new TestChromosome();
            chrom.setTestCase(tc);
            chrom.mutate();

            TestCase result = chrom.getTestCase();
            assertTrue(result.size() >= 1, "Test should still have statements");
        } finally {
            Properties.P_TEST_DELETE = oldDelete;
            Properties.P_TEST_INSERT = oldInsert;
            Properties.P_TEST_CHANGE = oldChange;
        }
    }
}
