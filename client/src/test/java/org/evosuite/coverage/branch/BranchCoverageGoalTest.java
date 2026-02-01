package org.evosuite.coverage.branch;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;

public class BranchCoverageGoalTest {

    @Test
    public void testEqualsAndHashCode() {
        BytecodeInstruction instr1 = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instr1.isBranch()).thenReturn(true);
        Mockito.when(instr1.getLineNumber()).thenReturn(10);
        Mockito.when(instr1.getMethodName()).thenReturn("Method");
        Mockito.when(instr1.getClassName()).thenReturn("Class");

        BytecodeInstruction instr2 = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instr2.isBranch()).thenReturn(true);
        Mockito.when(instr2.getLineNumber()).thenReturn(20);
        Mockito.when(instr2.getMethodName()).thenReturn("Method");
        Mockito.when(instr2.getClassName()).thenReturn("Class");

        Branch b1 = new Branch(instr1, 1);
        Branch b2 = new Branch(instr1, 1); // Same ID, same instruction
        Branch b3 = new Branch(instr2, 2); // Diff ID, diff instruction

        BranchCoverageGoal g1 = new BranchCoverageGoal(b1, true, "Class", "Method");
        BranchCoverageGoal g2 = new BranchCoverageGoal(b2, true, "Class", "Method");
        BranchCoverageGoal g3 = new BranchCoverageGoal(b3, true, "Class", "Method");
        BranchCoverageGoal g4 = new BranchCoverageGoal(b1, false, "Class", "Method"); // Diff value

        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());

        assertNotEquals(g1, g3);
        assertNotEquals(g1, g4);
        assertNotEquals(g1, null);
    }

    @Test
    public void testCompareTo() {
        BytecodeInstruction instr1 = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instr1.isBranch()).thenReturn(true);
        Mockito.when(instr1.getLineNumber()).thenReturn(10);
        Mockito.when(instr1.getMethodName()).thenReturn("Method");
        Mockito.when(instr1.getClassName()).thenReturn("Class");

        BytecodeInstruction instr2 = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instr2.isBranch()).thenReturn(true);
        Mockito.when(instr2.getLineNumber()).thenReturn(20);
        Mockito.when(instr2.getMethodName()).thenReturn("Method");
        Mockito.when(instr2.getClassName()).thenReturn("Class");

        Branch b1 = new Branch(instr1, 1);
        Branch b2 = new Branch(instr2, 2);

        BranchCoverageGoal g1 = new BranchCoverageGoal(b1, true, "Class", "Method");
        BranchCoverageGoal g2 = new BranchCoverageGoal(b2, true, "Class", "Method");

        // g1 line 10, g2 line 20
        assertTrue("Expected g1 < g2 because line 10 < 20", g1.compareTo(g2) < 0);
        assertTrue("Expected g2 > g1 because line 20 > 10", g2.compareTo(g1) > 0);

        // Same line, same branch, diff value
        BranchCoverageGoal g1False = new BranchCoverageGoal(b1, false, "Class", "Method");

        // g1 is true, g1False is false.
        // value comparison: true(1) > false(0)
        assertTrue("Expected true > false", g1.compareTo(g1False) > 0);
        assertTrue("Expected false < true", g1False.compareTo(g1) < 0);

        assertEquals(0, g1.compareTo(g1));
    }

    @Test
    public void testCompareToSameLineDifferentBranch() {
        // Two branches on the same line
        BytecodeInstruction instr1 = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instr1.isBranch()).thenReturn(true);
        Mockito.when(instr1.getLineNumber()).thenReturn(10);
        Mockito.when(instr1.getMethodName()).thenReturn("Method");
        Mockito.when(instr1.getClassName()).thenReturn("Class");

        BytecodeInstruction instr2 = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instr2.isBranch()).thenReturn(true);
        Mockito.when(instr2.getLineNumber()).thenReturn(10); // Same line
        Mockito.when(instr2.getMethodName()).thenReturn("Method");
        Mockito.when(instr2.getClassName()).thenReturn("Class");

        Branch b1 = new Branch(instr1, 1);
        Branch b2 = new Branch(instr2, 2); // Different ID

        BranchCoverageGoal g1 = new BranchCoverageGoal(b1, true, "Class", "Method");
        BranchCoverageGoal g2 = new BranchCoverageGoal(b2, true, "Class", "Method");

        // g1 id 1, g2 id 2. 1 < 2.
        assertTrue("Expected g1 < g2 because id 1 < 2", g1.compareTo(g2) < 0);
        assertTrue("Expected g2 > g1 because id 2 > 1", g2.compareTo(g1) > 0);
    }

    @Test
    public void testRootBranch() {
        BranchCoverageGoal g1 = new BranchCoverageGoal(null, true, "Class", "Method", 10);
        BranchCoverageGoal g2 = new BranchCoverageGoal(null, true, "Class", "Method", 10);

        assertEquals(g1, g2);
        assertEquals(0, g1.compareTo(g2));

        BranchCoverageGoal g3 = new BranchCoverageGoal(null, true, "Class", "Method2", 10);
        assertTrue(g1.compareTo(g3) < 0); // "Method" < "Method2"
    }
}
