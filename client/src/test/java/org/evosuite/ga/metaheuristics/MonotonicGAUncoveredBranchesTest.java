package org.evosuite.ga.metaheuristics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.Test;

/**
 * EvoObj T1 regression: {@link MonotonicGA#computeUncoveredBranches} must treat a branch as
 * covered if <em>any</em> execution result reaches distance 0 for it — not just the last result.
 */
public class MonotonicGAUncoveredBranchesTest {

    private BranchCoverageTestFitness trueGoal(int branchId, BranchCoverageGoal goal) {
        Branch branch = mock(Branch.class);
        when(branch.getActualBranchId()).thenReturn(branchId);
        BranchCoverageTestFitness tf = mock(BranchCoverageTestFitness.class);
        when(tf.getBranch()).thenReturn(branch);
        when(tf.getValue()).thenReturn(true);
        when(tf.getBranchGoal()).thenReturn(goal);
        return tf;
    }

    private ExecutionResult resultWithTrueDistance(int branchId, double distance) {
        Map<Integer, Double> trueDistances = new HashMap<>();
        trueDistances.put(branchId, distance);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getTrueDistances()).thenReturn(trueDistances);
        ExecutionResult result = mock(ExecutionResult.class);
        when(result.getTrace()).thenReturn(trace);
        return result;
    }

    @Test
    public void branchCoveredByFirstResultButNotLastIsNotReportedUncovered() {
        BranchCoverageGoal goal = mock(BranchCoverageGoal.class);
        int branchId = 42;
        BranchCoverageTestFitness tf = trueGoal(branchId, goal);

        // First result covers the branch (distance 0); second does not (distance 1).
        List<ExecutionResult> results = Arrays.asList(
                resultWithTrueDistance(branchId, 0.0),
                resultWithTrueDistance(branchId, 1.0));

        Set<BranchCoverageGoal> uncovered =
                MonotonicGA.computeUncoveredBranches(results, Arrays.asList(tf));

        assertFalse(uncovered.contains(goal),
                "branch covered by an earlier result must not be reported uncovered");
    }

    @Test
    public void branchNeverCoveredIsReportedUncovered() {
        BranchCoverageGoal goal = mock(BranchCoverageGoal.class);
        int branchId = 7;
        BranchCoverageTestFitness tf = trueGoal(branchId, goal);

        List<ExecutionResult> results = Arrays.asList(
                resultWithTrueDistance(branchId, 2.0),
                resultWithTrueDistance(branchId, 1.0));

        Set<BranchCoverageGoal> uncovered =
                MonotonicGA.computeUncoveredBranches(results, Arrays.asList(tf));

        assertTrue(uncovered.contains(goal),
                "branch never reaching distance 0 must be reported uncovered");
    }
}
