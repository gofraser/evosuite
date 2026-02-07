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
package org.evosuite.coverage.branch;

import com.examples.with.different.packagename.cdg.NestedConditions;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.ControlFlowDistance;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.execution.MethodCall;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link ControlFlowDistanceCalculator} using real
 * instrumented classes via DependencyAnalysis.
 */
public class ControlFlowDistanceCalculatorTest {

    private static final String CLASS_NAME = NestedConditions.class.getCanonicalName();
    private static final String METHOD_NAME = "classify(I)Ljava/lang/String;";
    private static final String FULL_METHOD = CLASS_NAME + "." + METHOD_NAME;

    // Bytecode line numbers (from NestedConditions source):
    //   Line 24: if (x > 0)   -> outer branch, IFLE, CDG depth 0 (root-dependent)
    //   Line 25: if (x > 100) -> inner branch, IF_ICMPLE, CDG depth 1 (depends on outer)
    private static final int OUTER_LINE = 24;
    private static final int INNER_LINE = 25;

    @Before
    public void setUp() throws ClassNotFoundException {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.getInstance().resetToDefaults();
        Properties.TARGET_CLASS = "";
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();

        analyzeClass(NestedConditions.class);
    }

    @After
    public void tearDown() {
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();
        Properties.getInstance().resetToDefaults();
    }

    // ── Helper methods ──────────────────────────────────────────────

    private void analyzeClass(Class<?> clazz) throws ClassNotFoundException {
        String className = clazz.getCanonicalName();
        Properties.TARGET_CLASS = className;
        String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();
        DependencyAnalysis.analyzeClass(className, Arrays.asList(cp.split(File.pathSeparator)));
    }

    private Branch findBranchAtLine(int line) {
        ClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();
        BranchPool pool = BranchPool.getInstance(cl);
        for (Branch b : pool.getAllBranches()) {
            if (b.getClassName().equals(CLASS_NAME)
                    && b.getInstruction().getLineNumber() == line) {
                return b;
            }
        }
        fail("No branch found at line " + line);
        return null;
    }

    private ExecutionResult createResult(List<MethodCall> calls,
                                         Set<Integer> coveredTrue,
                                         Set<Integer> coveredFalse,
                                         Set<String> coveredMethods) {
        ExecutionResult result = new ExecutionResult(mock(TestCase.class));
        ExecutionTraceImpl trace = new ExecutionTraceImpl();

        for (int branchId : coveredTrue) {
            trace.getTrueDistances().put(branchId, 0.0);
        }
        for (int branchId : coveredFalse) {
            trace.getFalseDistances().put(branchId, 0.0);
        }

        for (String method : coveredMethods) {
            trace.getMethodExecutionCount().put(method, 1);
        }

        for (MethodCall call : calls) {
            trace.getMethodCalls().add(call);
        }

        result.setTrace(trace);
        return result;
    }

    private void addBranchHit(MethodCall call, int branchId,
                               double trueDist, double falseDist) {
        call.branchTrace.add(branchId);
        call.trueDistanceTrace.add(trueDist);
        call.falseDistanceTrace.add(falseDist);
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    public void testBranchCoveredExact() {
        // Outer branch reached and covered in the desired direction (false = fall-through = x > 0)
        Branch outer = findBranchAtLine(OUTER_LINE);
        int branchId = outer.getActualBranchId();

        MethodCall call = new MethodCall(CLASS_NAME, METHOD_NAME, 0, 0, 0);
        addBranchHit(call, branchId, 5.0, 0.0);

        ExecutionResult result = createResult(
                Collections.singletonList(call),
                Collections.emptySet(),
                Collections.singleton(branchId),
                Collections.singleton(FULL_METHOD));

        ControlFlowDistance d = ControlFlowDistanceCalculator.getDistance(
                result, outer, false, CLASS_NAME, METHOD_NAME);

        assertEquals(0, d.getApproachLevel());
        assertEquals(0.0, d.getBranchDistance(), 0.001);
    }

    @Test
    public void testBranchReachedWrongDirection() {
        // Outer branch reached but wrong direction: true taken (x <= 0), want false (x > 0)
        Branch outer = findBranchAtLine(OUTER_LINE);
        int branchId = outer.getActualBranchId();

        MethodCall call = new MethodCall(CLASS_NAME, METHOD_NAME, 0, 0, 0);
        addBranchHit(call, branchId, 0.0, 7.0);

        ExecutionResult result = createResult(
                Collections.singletonList(call),
                Collections.singleton(branchId),
                Collections.emptySet(),
                Collections.singleton(FULL_METHOD));

        ControlFlowDistance d = ControlFlowDistanceCalculator.getDistance(
                result, outer, false, CLASS_NAME, METHOD_NAME);

        assertEquals(0, d.getApproachLevel());
        assertTrue("Branch distance should be > 0", d.getBranchDistance() > 0);
    }

    @Test
    public void testImmediateControlDependencyReached() {
        // Inner branch NOT reached; outer branch reached in wrong direction.
        // Inner is control-dependent on outer with branchExpressionValue=false.
        Branch inner = findBranchAtLine(INNER_LINE);
        Branch outer = findBranchAtLine(OUTER_LINE);
        int outerBranchId = outer.getActualBranchId();

        MethodCall call = new MethodCall(CLASS_NAME, METHOD_NAME, 0, 0, 0);
        addBranchHit(call, outerBranchId, 0.0, 3.0);

        ExecutionResult result = createResult(
                Collections.singletonList(call),
                Collections.singleton(outerBranchId),
                Collections.emptySet(),
                Collections.singleton(FULL_METHOD));

        ControlFlowDistance d = ControlFlowDistanceCalculator.getDistance(
                result, inner, true, CLASS_NAME, METHOD_NAME);

        assertEquals(1, d.getApproachLevel());
    }

    @Test
    public void testMethodNotCalled_RootDependentBranch() {
        // Outer branch (CDG depth 0), method never called -> approach = 0 + 1 = 1
        Branch outer = findBranchAtLine(OUTER_LINE);

        ExecutionResult result = createResult(
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet());

        ControlFlowDistance d = ControlFlowDistanceCalculator.getDistance(
                result, outer, true, CLASS_NAME, METHOD_NAME);

        assertEquals(1, d.getApproachLevel());
    }

    @Test
    public void testMethodNotCalled_NestedBranch() {
        // Inner branch (CDG depth 1), method never called -> approach = 1 + 1 = 2
        Branch inner = findBranchAtLine(INNER_LINE);

        ExecutionResult result = createResult(
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet());

        ControlFlowDistance d = ControlFlowDistanceCalculator.getDistance(
                result, inner, true, CLASS_NAME, METHOD_NAME);

        assertEquals(2, d.getApproachLevel());
    }

    @Test
    public void testCDGDepthComputation() {
        Branch outer = findBranchAtLine(OUTER_LINE);
        Branch inner = findBranchAtLine(INNER_LINE);

        assertEquals(0, ControlFlowDistanceCalculator.getCDGDepth(outer));
        assertEquals(1, ControlFlowDistanceCalculator.getCDGDepth(inner));
    }
}
