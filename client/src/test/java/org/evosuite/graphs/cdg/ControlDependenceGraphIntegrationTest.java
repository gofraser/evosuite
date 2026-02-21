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
package org.evosuite.graphs.cdg;

import com.examples.with.different.packagename.cdg.*;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.graphs.cfg.ControlFlowEdge;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that build CDGs from real Java bytecode via
 * DependencyAnalysis and verify structural properties against
 * known source code patterns.
 */
public class ControlDependenceGraphIntegrationTest {

    @BeforeEach
    public void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.getInstance().resetToDefaults();
        Properties.TARGET_CLASS = "";
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();
        Properties.getInstance().resetToDefaults();
    }

    // ── Helper methods ──────────────────────────────────────────────

    /**
     * Instruments the given class, retrieves and returns the CDG for the
     * specified method descriptor.
     */
    private ControlDependenceGraph loadAndGetCDG(Class<?> clazz, String methodDescriptor)
            throws ClassNotFoundException {
        String className = clazz.getCanonicalName();
        Properties.TARGET_CLASS = className;

        String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();
        DependencyAnalysis.analyzeClass(className, Arrays.asList(cp.split(File.pathSeparator)));

        ClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        return GraphPool.getInstance(classLoader).getCDG(className, methodDescriptor);
    }

    /**
     * Finds a BasicBlock in the CDG whose line range includes the given line number.
     * Skips entry/exit blocks. Returns null if not found.
     */
    private BasicBlock findBlockAtLine(ControlDependenceGraph cdg, int lineNumber) {
        for (BasicBlock block : cdg.vertexSet()) {
            if (block.isEntryBlock() || block.isExitBlock()) continue;
            int first = block.getFirstLine();
            int last = block.getLastLine();
            if (first <= lineNumber && lineNumber <= last) {
                return block;
            }
        }
        return null;
    }

    /**
     * Finds all BasicBlocks whose line range includes the given line number.
     */
    private List<BasicBlock> findAllBlocksAtLine(ControlDependenceGraph cdg, int lineNumber) {
        List<BasicBlock> result = new ArrayList<>();
        for (BasicBlock block : cdg.vertexSet()) {
            if (block.isEntryBlock() || block.isExitBlock()) continue;
            int first = block.getFirstLine();
            int last = block.getLastLine();
            if (first <= lineNumber && lineNumber <= last) {
                result.add(block);
            }
        }
        return result;
    }

    /**
     * Returns the source line numbers of branch instructions controlling
     * the given block (via CDG edges).
     */
    private Set<Integer> getControllingBranchLines(ControlDependenceGraph cdg, BasicBlock block) {
        Set<Integer> lines = new LinkedHashSet<>();
        for (ControlDependency cd : cdg.getControlDependentBranches(block)) {
            BytecodeInstruction branchIns = cd.getBranch().getInstruction();
            if (branchIns.hasLineNumberSet()) {
                lines.add(branchIns.getLineNumber());
            }
        }
        return lines;
    }

    /**
     * Dumps CDG structure for debugging purposes.
     */
    private String dumpCDG(ControlDependenceGraph cdg) {
        StringBuilder sb = new StringBuilder();
        sb.append("CDG for ").append(cdg.getMethodName()).append(":\n");
        sb.append("  Vertices (").append(cdg.vertexCount()).append("):\n");
        for (BasicBlock b : cdg.vertexSet()) {
            sb.append("    ").append(b.getName())
              .append(" lines=").append(b.getFirstLine()).append("-").append(b.getLastLine())
              .append(" entry=").append(b.isEntryBlock())
              .append(" rootDep=").append(!b.isEntryBlock() && cdg.isRootDependent(b))
              .append("\n");
            if (!b.isEntryBlock() && !b.isExitBlock()) {
                Set<ControlDependency> deps = cdg.getControlDependentBranches(b);
                for (ControlDependency cd : deps) {
                    sb.append("      depends on branch at line ")
                      .append(cd.getBranch().getInstruction().getLineNumber())
                      .append(" (value=").append(cd.getBranchExpressionValue()).append(")\n");
                }
            }
        }
        sb.append("  Edges (").append(cdg.edgeCount()).append("):\n");
        for (ControlFlowEdge e : cdg.edgeSet()) {
            BasicBlock src = cdg.getEdgeSource(e);
            BasicBlock tgt = cdg.getEdgeTarget(e);
            sb.append("    ").append(src.getName()).append(" -> ").append(tgt.getName()).append("\n");
        }
        return sb.toString();
    }

    // ── Test: SimpleIfElse ──────────────────────────────────────────

    @Test
    public void testSimpleIfElse() throws ClassNotFoundException {
        // Source (SimpleIfElse.java):
        //   line 23: public int abs(int x) {
        //   line 24:     if (x >= 0) {         <-- BRANCH
        //   line 25:         return x;          <-- dependent on branch (true)
        //   line 27:         return -x;         <-- dependent on branch (false)
        ControlDependenceGraph cdg = loadAndGetCDG(SimpleIfElse.class, "abs(I)I");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        // The branch is at the if-condition line
        // Find blocks for the then and else bodies
        BasicBlock thenBlock = findBlockAtLine(cdg, 25);
        BasicBlock elseBlock = findBlockAtLine(cdg, 27);

        assertNotNull(thenBlock, "Should find block for 'return x' (line 25): " + dump);
        assertNotNull(elseBlock, "Should find block for 'return -x' (line 27): " + dump);

        // Both then and else should NOT be root dependent
        assertFalse(cdg.isRootDependent(thenBlock),
                "Then block should not be root dependent: " + dump);
        assertFalse(cdg.isRootDependent(elseBlock),
                "Else block should not be root dependent: " + dump);

        // Both should be control dependent on the same branch (the if condition)
        Set<ControlDependency> thenDeps = cdg.getControlDependentBranches(thenBlock);
        Set<ControlDependency> elseDeps = cdg.getControlDependentBranches(elseBlock);

        assertFalse(thenDeps.isEmpty(), "Then block should have control dependencies: " + dump);
        assertFalse(elseDeps.isEmpty(), "Else block should have control dependencies: " + dump);

        // The branch instruction for both should be on the same line (the if condition)
        Set<Integer> thenBranchLines = getControllingBranchLines(cdg, thenBlock);
        Set<Integer> elseBranchLines = getControllingBranchLines(cdg, elseBlock);
        assertEquals(thenBranchLines,
                elseBranchLines, "Then and else should depend on same branch lines: " + dump);

        // They should have opposite branch expression values
        boolean thenValue = thenDeps.iterator().next().getBranchExpressionValue();
        boolean elseValue = elseDeps.iterator().next().getBranchExpressionValue();
        assertNotEquals(thenValue,
                elseValue, "Then and else should have opposite branch values: " + dump);
    }

    // ── Test: NestedConditions ──────────────────────────────────────

    @Test
    public void testNestedConditions() throws ClassNotFoundException {
        // Source (NestedConditions.java):
        //   line 24:     if (x > 0) {             <-- OUTER BRANCH
        //   line 25:         if (x > 100) {        <-- INNER BRANCH
        //   line 26:             return "big";      <-- dependent on inner
        //   line 28:             return "small";    <-- dependent on inner
        //   line 31:         return "non-positive"; <-- dependent on outer only
        ControlDependenceGraph cdg = loadAndGetCDG(NestedConditions.class, "classify(I)Ljava/lang/String;");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        BasicBlock bigBlock = findBlockAtLine(cdg, 26);
        BasicBlock smallBlock = findBlockAtLine(cdg, 28);
        BasicBlock nonPosBlock = findBlockAtLine(cdg, 31);

        assertNotNull(bigBlock, "Should find block for 'big' (line 26): " + dump);
        assertNotNull(smallBlock, "Should find block for 'small' (line 28): " + dump);
        assertNotNull(nonPosBlock, "Should find block for 'non-positive' (line 31): " + dump);

        // "big" and "small" should both depend on the inner branch (line 25)
        Set<Integer> bigBranchLines = getControllingBranchLines(cdg, bigBlock);
        Set<Integer> smallBranchLines = getControllingBranchLines(cdg, smallBlock);
        assertTrue(bigBranchLines.contains(25),
                "'big' should depend on inner branch at line 25: " + dump);
        assertTrue(smallBranchLines.contains(25),
                "'small' should depend on inner branch at line 25: " + dump);

        // "non-positive" should depend on the outer branch (line 24)
        Set<Integer> nonPosBranchLines = getControllingBranchLines(cdg, nonPosBlock);
        assertTrue(nonPosBranchLines.contains(24),
                "'non-positive' should depend on outer branch at line 24: " + dump);

        // "non-positive" should NOT depend on the inner branch
        assertFalse(nonPosBranchLines.contains(25),
                "'non-positive' should not depend on inner branch: " + dump);
    }

    // ── Test: WhileLoop ─────────────────────────────────────────────

    @Test
    public void testWhileLoop() throws ClassNotFoundException {
        // Source (WhileLoop.java):
        //   line 24:     int sum = 0;
        //   line 25:     int i = 0;
        //   line 26:     while (i < n) {      <-- BRANCH (loop condition)
        //   line 27:         sum += i;         <-- dependent on while
        //   line 28:         i++;
        //   line 30:     return sum;           <-- root dependent
        ControlDependenceGraph cdg = loadAndGetCDG(WhileLoop.class, "sumUpTo(I)I");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        // The loop body (line 27-28) should be control dependent on the while condition
        BasicBlock loopBody = findBlockAtLine(cdg, 27);
        assertNotNull(loopBody, "Should find block for loop body (line 27): " + dump);
        assertFalse(cdg.isRootDependent(loopBody),
                "Loop body should not be root dependent: " + dump);

        Set<Integer> loopBodyBranches = getControllingBranchLines(cdg, loopBody);
        assertTrue(loopBodyBranches.contains(26),
                "Loop body should depend on while condition at line 26: " + dump);

        // The return statement (after the loop) is control dependent on the
        // while condition — when the condition evaluates to true (loop exits),
        // execution reaches the return. It is NOT root dependent.
        BasicBlock returnBlock = findBlockAtLine(cdg, 30);
        assertNotNull(returnBlock, "Should find block for return (line 30): " + dump);
        assertFalse(cdg.isRootDependent(returnBlock),
                "Return should not be root dependent (it depends on loop exit): " + dump);
    }

    // ── Test: SwitchMethod ──────────────────────────────────────────

    @Test
    public void testSwitchMethod() throws ClassNotFoundException {
        // Source (SwitchMethod.java):
        //   line 24:     switch (day) {              <-- SWITCH BRANCH
        //   line 25:         case 1: return "Monday";
        //   line 26:         case 2: return "Tuesday";
        //   line 27:         case 3: return "Wednesday";
        //   line 28:         default: return "other";
        ControlDependenceGraph cdg = loadAndGetCDG(SwitchMethod.class, "dayType(I)Ljava/lang/String;");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        // Each case should be control dependent (not root dependent)
        BasicBlock mondayBlock = findBlockAtLine(cdg, 25);
        BasicBlock tuesdayBlock = findBlockAtLine(cdg, 26);
        BasicBlock wednesdayBlock = findBlockAtLine(cdg, 27);
        BasicBlock defaultBlock = findBlockAtLine(cdg, 28);

        assertNotNull(mondayBlock, "Should find block for Monday (line 25): " + dump);
        assertNotNull(tuesdayBlock, "Should find block for Tuesday (line 26): " + dump);
        assertNotNull(wednesdayBlock, "Should find block for Wednesday (line 27): " + dump);
        assertNotNull(defaultBlock, "Should find block for default (line 28): " + dump);

        // All case blocks should not be root dependent
        assertFalse(cdg.isRootDependent(mondayBlock),
                "Monday should not be root dependent: " + dump);
        assertFalse(cdg.isRootDependent(tuesdayBlock),
                "Tuesday should not be root dependent: " + dump);
        assertFalse(cdg.isRootDependent(wednesdayBlock),
                "Wednesday should not be root dependent: " + dump);
        assertFalse(cdg.isRootDependent(defaultBlock),
                "Default should not be root dependent: " + dump);

        // All case blocks should have control dependencies
        assertFalse(cdg.getControlDependentBranches(mondayBlock).isEmpty(),
                "Monday should have control deps: " + dump);
        assertFalse(cdg.getControlDependentBranches(tuesdayBlock).isEmpty(),
                "Tuesday should have control deps: " + dump);
        assertFalse(cdg.getControlDependentBranches(wednesdayBlock).isEmpty(),
                "Wednesday should have control deps: " + dump);
        assertFalse(cdg.getControlDependentBranches(defaultBlock).isEmpty(),
                "Default should have control deps: " + dump);
    }

    // ── Test: SequentialBranches ─────────────────────────────────────

    @Test
    public void testSequentialBranches() throws ClassNotFoundException {
        // Source (SequentialBranches.java):
        //   line 24:     int result = 0;
        //   line 25:     if (x > 0) {            <-- BRANCH 1
        //   line 26:         result += 1;         <-- dependent on branch 1
        //   line 28:         result -= 1;         <-- dependent on branch 1
        //   line 30:     if (y > 0) {             <-- BRANCH 2
        //   line 31:         result += 10;        <-- dependent on branch 2
        //   line 33:         result -= 10;        <-- dependent on branch 2
        //   line 35:     return result;            <-- root dependent
        ControlDependenceGraph cdg = loadAndGetCDG(SequentialBranches.class, "process(II)I");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        BasicBlock addOneBlock = findBlockAtLine(cdg, 26);
        BasicBlock subOneBlock = findBlockAtLine(cdg, 28);
        BasicBlock addTenBlock = findBlockAtLine(cdg, 31);
        BasicBlock subTenBlock = findBlockAtLine(cdg, 33);
        BasicBlock returnBlock = findBlockAtLine(cdg, 35);

        assertNotNull(addOneBlock, "Should find block for result += 1 (line 26): " + dump);
        assertNotNull(subOneBlock, "Should find block for result -= 1 (line 28): " + dump);
        assertNotNull(addTenBlock, "Should find block for result += 10 (line 31): " + dump);
        assertNotNull(subTenBlock, "Should find block for result -= 10 (line 33): " + dump);
        assertNotNull(returnBlock, "Should find block for return (line 35): " + dump);

        // First if branches depend on branch 1 (line 25)
        Set<Integer> addOneBranches = getControllingBranchLines(cdg, addOneBlock);
        Set<Integer> subOneBranches = getControllingBranchLines(cdg, subOneBlock);
        assertTrue(addOneBranches.contains(25),
                "result += 1 should depend on branch 1 at line 25: " + dump);
        assertTrue(subOneBranches.contains(25),
                "result -= 1 should depend on branch 1 at line 25: " + dump);

        // Second if branches depend on branch 2 (line 30)
        Set<Integer> addTenBranches = getControllingBranchLines(cdg, addTenBlock);
        Set<Integer> subTenBranches = getControllingBranchLines(cdg, subTenBlock);
        assertTrue(addTenBranches.contains(30),
                "result += 10 should depend on branch 2 at line 30: " + dump);
        assertTrue(subTenBranches.contains(30),
                "result -= 10 should depend on branch 2 at line 30: " + dump);

        // First if branches should NOT depend on branch 2
        assertFalse(addOneBranches.contains(30),
                "result += 1 should not depend on branch 2: " + dump);
        assertFalse(subOneBranches.contains(30),
                "result -= 1 should not depend on branch 2: " + dump);

        // Second if branches should NOT depend on branch 1
        assertFalse(addTenBranches.contains(25),
                "result += 10 should not depend on branch 1: " + dump);
        assertFalse(subTenBranches.contains(25),
                "result -= 10 should not depend on branch 1: " + dump);

        // The return block is NOT root dependent — in the CDG it has no
        // incoming edges (it's at a join point after the second if-else).
        // Verify it has no control dependencies from either branch.
        assertFalse(cdg.isRootDependent(returnBlock),
                "return should not be root dependent: " + dump);
        Set<Integer> returnBranches = getControllingBranchLines(cdg, returnBlock);
        assertFalse(returnBranches.contains(25),
                "return should not depend on branch 1: " + dump);
        assertFalse(returnBranches.contains(30),
                "return should not depend on branch 2: " + dump);
    }

    // ── Test: ExceptionalControlFlow ───────────────────────────────

    @Test
    public void testExceptionalControlFlow() throws ClassNotFoundException {
        // Source (ExceptionalControlFlow.java):
        //   line 25: if (x < 0) { throw ... }   <-- try guard
        //   line 28: if (x == 0) { return "zero"; }
        //   line 33: if (x < -10) { return "very-neg"; }
        ControlDependenceGraph cdg = loadAndGetCDG(ExceptionalControlFlow.class, "classify(I)Ljava/lang/String;");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        BasicBlock zeroBlock = findBlockAtLine(cdg, 29);
        BasicBlock posBlock = findBlockAtLine(cdg, 31);
        BasicBlock veryNegBlock = findBlockAtLine(cdg, 34);
        BasicBlock negBlock = findBlockAtLine(cdg, 36);

        assertNotNull(zeroBlock, "Should find block for 'zero' (line 29): " + dump);
        assertNotNull(posBlock, "Should find block for 'pos' (line 31): " + dump);
        assertNotNull(veryNegBlock, "Should find block for 'very-neg' (line 34): " + dump);
        assertNotNull(negBlock, "Should find block for 'neg' (line 36): " + dump);

        Set<Integer> zeroDeps = getControllingBranchLines(cdg, zeroBlock);
        assertTrue(zeroDeps.contains(25), "'zero' should depend on try guard (line 25): " + dump);
        assertTrue(zeroDeps.contains(28), "'zero' should depend on zero-check (line 28): " + dump);

        Set<Integer> posDeps = getControllingBranchLines(cdg, posBlock);
        assertTrue(posDeps.contains(25), "'pos' should depend on try guard (line 25): " + dump);
        assertTrue(posDeps.contains(28), "'pos' should depend on zero-check (line 28): " + dump);

        Set<Integer> veryNegDeps = getControllingBranchLines(cdg, veryNegBlock);
        assertTrue(veryNegDeps.contains(33), "'very-neg' should depend on catch guard (line 33): " + dump);
        assertTrue(veryNegDeps.contains(25), "'very-neg' should depend on try guard (line 25): " + dump);

        Set<Integer> negDeps = getControllingBranchLines(cdg, negBlock);
        assertTrue(negDeps.contains(33), "'neg' should depend on catch guard (line 33): " + dump);
        assertTrue(negDeps.contains(25), "'neg' should depend on try guard (line 25): " + dump);
    }

    // ── Test: ImplicitExceptionControlFlow ─────────────────────────

    @Test
    public void testImplicitExceptionControlFlow() throws ClassNotFoundException {
        // Source (ImplicitExceptionControlFlow.java):
        //   line 25: if (flag) { s.length(); }  <-- implicit NPE
        //   line 31: catch returns "npe"
        ControlDependenceGraph cdg = loadAndGetCDG(ImplicitExceptionControlFlow.class, "classify(Z)Ljava/lang/String;");
        assertNotNull(cdg, "CDG should not be null");

        String dump = dumpCDG(cdg);

        BasicBlock npeBlock = findBlockAtLine(cdg, 31);
        assertNotNull(npeBlock, "Should find block for 'npe' (line 31): " + dump);

        Set<Integer> npeDeps = getControllingBranchLines(cdg, npeBlock);
        assertTrue(npeDeps.contains(25), "'npe' should depend on flag branch (line 25): " + dump);
    }
}
