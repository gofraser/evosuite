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
package org.evosuite.coverage.branch;

import org.evosuite.coverage.ControlFlowDistance;
import org.evosuite.coverage.TestCoverageGoal;
import org.evosuite.graphs.cdg.ControlDependenceGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.graphs.cfg.ControlFlowEdge;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.MethodCall;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.Statement;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class holds static methods used to calculate ControlFlowDistances or in
 * other words methods to determine, how far a given ExecutionResult was away
 * from reaching a given instruction or evaluating a certain Branch in a certain
 * way - depending on your point of view.
 *
 * <p>The distance to a certain Branch evaluating in a certain way is calculated as
 * follows:
 *
 * <p>If the given result had a Timeout, the worst possible ControlFlowDistance for
 * the method at hand is returned.
 *
 * <p>Otherwise, if the given branch was null, meaning the distance to the root
 * branch of a method should be calculated, either the 0-distance is returned,
 * should the method at hand be called in the given ExecutionResult, or
 * otherwise the 1-distance is returned.
 *
 * <p>Otherwise, the distance from the given ExecutionResult to evaluating the
 * given Branch to either jump (given value being true) or not jump (given value
 * being false) is calculated as follows:
 *
 * <p>If the given Branch was passed in the given ExecutionResult, the respective
 * true- or false-distance - depending on the given value- is taken as the
 * returned distance's branch distance with an approach level of 0. Otherwise
 * the minimum over all distances for evaluating one of the Branches that the
 * given Branch is control dependent on is returned, after adding one to that
 * distance's approach level.
 *
 * @author Andre Mis
 */
public class ControlFlowDistanceCalculator {

    private static final Logger logger = LoggerFactory.getLogger(ControlFlowDistanceCalculator.class);

    private static final int TIMEOUT_APPROACH_LEVEL = 20;
    private static final int MAX_BFS_EXPANSIONS =
            Integer.getInteger("evosuite.cfd.max_expansions", 2_000);
    private static final Map<Integer, Integer> CDG_DEPTH_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, CdgAncestorInfo> CDG_ANCESTOR_CACHE = new ConcurrentHashMap<>();

    /**
     * Calculates the ControlFlowDistance indicating how far away the given
     * ExecutionResult was from executing the given Branch in a certain way,
     * depending on the given value.
     *
     * <p>For more information look at this class's class comment
     *
     * @param result     a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     * @param branch     a {@link org.evosuite.coverage.branch.Branch} object.
     * @param value      a boolean.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @return a {@link org.evosuite.coverage.ControlFlowDistance} object.
     */
    public static ControlFlowDistance getDistance(ExecutionResult result, Branch branch,
                                                  boolean value, String className, String methodName) {
        if (result == null || className == null || methodName == null) {
            throw new IllegalArgumentException("null given");
        }
        if (branch == null && !value) {
            throw new IllegalArgumentException(
                    "expect distance for a root branch to always have value set to true");
        }
        if (branch != null) {
            if (!branch.getMethodName().equals(methodName)
                    || !branch.getClassName().equals(className)) {
                throw new IllegalArgumentException(
                        "expect explicitly given information about a branch to coincide with the "
                                + "information given by that branch");
            }
        }

        // handle timeout in ExecutionResult
        if (TestCoverageGoal.hasTimeout(result)) {
            return getTimeoutDistance(result, branch);
        }

        // if branch is null, we will just try to call the method at hand
        if (branch == null) {
            return getRootDistance(result, className, methodName);
        }

        if (value) {
            if (result.getTrace().getCoveredTrueBranches().contains(branch.getActualBranchId())) {
                return new ControlFlowDistance(0, 0.0);
            }
        } else {
            if (result.getTrace().getCoveredFalseBranches().contains(branch.getActualBranchId())) {
                return new ControlFlowDistance(0, 0.0);
            }
        }

        ControlFlowDistance nonRootDistance = getNonRootDistance(result, branch, value);

        if (nonRootDistance == null) {
            throw new IllegalStateException(
                    "expect getNonRootDistance to never return null");
        }

        return nonRootDistance;
    }

    private static ControlFlowDistance getTimeoutDistance(ExecutionResult result,
                                                          Branch branch) {

        if (!TestCoverageGoal.hasTimeout(result)) {
            throw new IllegalArgumentException("expect given result to have a timeout");
        }
        logger.debug("Has timeout!");
        return worstPossibleDistanceForMethod(branch);
    }

    private static ControlFlowDistance worstPossibleDistanceForMethod(Branch branch) {
        ControlFlowDistance distance = new ControlFlowDistance();
        if (branch == null) {
            distance.setApproachLevel(TIMEOUT_APPROACH_LEVEL);
        } else {
            int cdgDepth = getCDGDepth(branch);
            if (cdgDepth == Integer.MAX_VALUE) {
                distance.setApproachLevel(TIMEOUT_APPROACH_LEVEL);
            } else {
                distance.setApproachLevel(cdgDepth + 2);
            }
        }
        return distance;
    }

    private static ControlFlowDistance worstPossibleDistanceWithoutCDGComputation() {
        ControlFlowDistance distance = new ControlFlowDistance();
        distance.setApproachLevel(TIMEOUT_APPROACH_LEVEL);
        return distance;
    }

    /**
     * If there is an exception in a superconstructor, then the corresponding
     * constructor might not be included in the execution trace.
     *
     * @param result a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     * @param className a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     */
    private static boolean hasConstructorException(ExecutionResult result,
                                                   String className, String methodName) {

        if (result.hasTimeout() || result.hasTestException()
                || result.noThrownExceptions()) {
            return false;
        }

        Integer exceptionPosition = result.getFirstPositionOfThrownException();
        if (!result.test.hasStatement(exceptionPosition)) {
            return false;
        }
        Statement statement = result.test.getStatement(exceptionPosition);
        if (statement instanceof ConstructorStatement) {
            ConstructorStatement c = (ConstructorStatement) statement;
            String constructorClassName = c.getConstructor().getName();
            String constructorMethodName = "<init>"
                    + Type.getConstructorDescriptor(c.getConstructor().getConstructor());
            return constructorClassName.equals(className) && constructorMethodName.equals(methodName);

        }
        return false;
    }

    private static ControlFlowDistance getRootDistance(ExecutionResult result,
                                                       String className, String methodName) {

        ControlFlowDistance distance = new ControlFlowDistance();

        if (result.getTrace().getCoveredMethods().contains(className + "." + methodName)) {
            return distance;
        }
        if (hasConstructorException(result, className, methodName)) {
            return distance;
        }

        distance.increaseApproachLevel();
        return distance;
    }


    private static ControlFlowDistance getNonRootDistance(ExecutionResult result,
                                                          Branch branch, boolean value) {

        if (branch == null) {
            throw new IllegalStateException(
                    "expect this method only to be called if this goal does not try to cover the root branch");
        }

        String className = branch.getClassName();
        String methodName = branch.getMethodName();

        ControlFlowDistance resultDistance = new ControlFlowDistance();
        int cdgDepth = getCDGDepth(branch);
        if (cdgDepth == Integer.MAX_VALUE) {
            resultDistance.setApproachLevel(TIMEOUT_APPROACH_LEVEL);
        } else {
            resultDistance.setApproachLevel(cdgDepth + 1);
        }

        // The CDG ancestor info is purely structural and independent of any
        // execution trace.  Cache it so the BFS runs at most once per branch.
        CdgAncestorInfo ancestorInfo = getAncestorInfo(branch);

        // Trace data may still be extended while fitness is computed in parallel.
        // Iterate on a stable snapshot to avoid fail-fast iterator exceptions.
        List<MethodCall> methodCalls = result.getTrace().getMethodCalls();
        List<MethodCall> callsSnapshot;
        synchronized (methodCalls) {
            callsSnapshot = new ArrayList<>(methodCalls);
        }
        for (MethodCall call : callsSnapshot) {
            if (call.className.equals(className) && call.methodName.equals(methodName)) {
                Map<Integer, MinBranchDistances> branchTraceMinDistances =
                        buildBranchTraceMinDistances(call);
                ControlFlowDistance distance = lookupDistance(branch, value,
                        ancestorInfo, branchTraceMinDistances);
                if (distance != null && distance.compareTo(resultDistance) < 0) {
                    resultDistance = distance;
                }
            }
        }

        return resultDistance;
    }

    /**
     * Returns the cached CDG ancestor info for the given branch, computing it
     * via BFS on the first call.  The result is purely structural (depends only
     * on the control-dependency graph, not on any execution trace) and is
     * therefore safe to cache globally.
     */
    private static CdgAncestorInfo getAncestorInfo(Branch branch) {
        return CDG_ANCESTOR_CACHE.computeIfAbsent(branch.getActualBranchId(),
                ignored -> computeAncestorInfo(branch));
    }

    /**
     * BFS over the control dependency graph rooted at {@code branch} to
     * discover all ancestor (branchId, value) pairs and their minimum approach
     * levels, plus the minimum terminal approach level for root-dependent or
     * exception-handler paths.
     *
     * <p>The approach level for an ancestor at BFS depth D is D (for traced
     * branches) or D+1 (for terminal nodes), matching the semantics of the
     * original recursive algorithm.
     */
    private static CdgAncestorInfo computeAncestorInfo(Branch branch) {
        Map<Long, Integer> levels = new HashMap<>();
        int terminalLevel = Integer.MAX_VALUE;

        BytecodeInstruction startInstruction = branch.getInstruction();

        // Check if the target branch's instruction is itself terminal
        if (isExceptionHandlerEntry(startInstruction)) {
            terminalLevel = 1;
        }

        Set<ControlDependency> startDeps = startInstruction.getControlDependencies();
        if (startDeps.isEmpty() && terminalLevel > 1) {
            terminalLevel = 1;
        }

        // BFS from control dependencies of the target branch (level 1+)
        Queue<BfsEntry> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        // Mark the target branch as visited (both true/false) to avoid cycles
        visited.add(toBranchOutcomeKey(branch.getActualBranchId(), true));
        visited.add(toBranchOutcomeKey(branch.getActualBranchId(), false));

        boolean hasNonSelfLoopDep = false;
        for (ControlDependency dep : startDeps) {
            if (startInstruction.equals(dep.getBranch().getInstruction())) {
                continue;
            }
            hasNonSelfLoopDep = true;
            boolean depValue = dep.getBranchExpressionValue();
            long depKey = toBranchOutcomeKey(
                    dep.getBranch().getActualBranchId(), depValue);
            if (visited.add(depKey)) {
                levels.put(depKey, 1);
                queue.add(new BfsEntry(dep.getBranch(), depValue, 1));
            }
        }
        if (!hasNonSelfLoopDep && !startDeps.isEmpty()) {
            terminalLevel = Math.min(terminalLevel, 1);
        }

        int expansions = 0;
        while (!queue.isEmpty()) {
            BfsEntry entry = queue.poll();

            if (++expansions > MAX_BFS_EXPANSIONS) {
                break;
            }

            BytecodeInstruction instruction = entry.branch.getInstruction();

            if (isExceptionHandlerEntry(instruction)) {
                terminalLevel = Math.min(terminalLevel, entry.approachLevel + 1);
                continue;
            }

            Set<ControlDependency> deps = instruction.getControlDependencies();
            if (deps.isEmpty()) {
                terminalLevel = Math.min(terminalLevel, entry.approachLevel + 1);
                continue;
            }

            boolean hasNonSelf = false;
            for (ControlDependency dep : deps) {
                if (instruction.equals(dep.getBranch().getInstruction())) {
                    continue;
                }
                hasNonSelf = true;
                boolean depValue = dep.getBranchExpressionValue();
                long depKey = toBranchOutcomeKey(
                        dep.getBranch().getActualBranchId(), depValue);
                if (visited.add(depKey)) {
                    levels.put(depKey, entry.approachLevel + 1);
                    queue.add(new BfsEntry(dep.getBranch(), depValue,
                            entry.approachLevel + 1));
                }
            }
            if (!hasNonSelf) {
                terminalLevel = Math.min(terminalLevel, entry.approachLevel + 1);
            }
        }

        return new CdgAncestorInfo(levels, terminalLevel);
    }

    /**
     * Looks up the minimum distance for the target branch using the cached
     * ancestor info and the per-call traced branch distances.  Runs in
     * O(traced_branches) time.
     *
     * @return the minimum distance, or {@code null} if no path was found
     */
    private static ControlFlowDistance lookupDistance(
            Branch branch, boolean value,
            CdgAncestorInfo ancestorInfo,
            Map<Integer, MinBranchDistances> branchTraceMinDistances) {

        ControlFlowDistance best = null;

        // Level 0: check the target branch itself (only the desired value)
        MinBranchDistances targetTraced =
                branchTraceMinDistances.get(branch.getActualBranchId());
        if (targetTraced != null) {
            double branchDist = value
                    ? targetTraced.trueMinDistance : targetTraced.falseMinDistance;
            if (branchDist < Double.MAX_VALUE) {
                best = new ControlFlowDistance(0, branchDist);
            }
        }

        // Levels 1+: check ancestor branches from CDG
        for (Map.Entry<Integer, MinBranchDistances> entry :
                branchTraceMinDistances.entrySet()) {
            int branchId = entry.getKey();
            MinBranchDistances distances = entry.getValue();

            if (distances.trueMinDistance < Double.MAX_VALUE) {
                Integer level = ancestorInfo.ancestorApproachLevels.get(
                        toBranchOutcomeKey(branchId, true));
                if (level != null) {
                    ControlFlowDistance candidate =
                            new ControlFlowDistance(level, distances.trueMinDistance);
                    if (best == null || candidate.compareTo(best) < 0) {
                        best = candidate;
                    }
                }
            }

            if (distances.falseMinDistance < Double.MAX_VALUE) {
                Integer level = ancestorInfo.ancestorApproachLevels.get(
                        toBranchOutcomeKey(branchId, false));
                if (level != null) {
                    ControlFlowDistance candidate =
                            new ControlFlowDistance(level, distances.falseMinDistance);
                    if (best == null || candidate.compareTo(best) < 0) {
                        best = candidate;
                    }
                }
            }
        }

        // Terminal path: method was called but the chain leads to root/handler
        if (ancestorInfo.terminalApproachLevel < Integer.MAX_VALUE) {
            ControlFlowDistance terminal =
                    new ControlFlowDistance(ancestorInfo.terminalApproachLevel, 0.0);
            if (best == null || terminal.compareTo(best) < 0) {
                best = terminal;
            }
        }

        return best;
    }

    /**
     * Returns the Control Dependency Graph (CDG) depth of the given branch.
     * The depth is the minimum number of control dependencies to traverse to reach a root-dependent branch.
     *
     * @param branch the branch to calculate the CDG depth for
     * @return the CDG depth, or {@link Integer#MAX_VALUE} if a cycle is detected or depth cannot be determined
     */
    static int getCDGDepth(Branch branch) {
        return CDG_DEPTH_CACHE.computeIfAbsent(branch.getActualBranchId(),
                ignored -> computeCDGDepth(branch.getInstruction()));
    }

    private static int computeCDGDepth(BytecodeInstruction startInstruction) {
        Queue<Map.Entry<BytecodeInstruction, Integer>> queue = new ArrayDeque<>();
        Set<BytecodeInstruction> visited = new HashSet<>();

        queue.add(new AbstractMap.SimpleEntry<>(startInstruction, 0));
        visited.add(startInstruction);

        while (!queue.isEmpty()) {
            Map.Entry<BytecodeInstruction, Integer> entry = queue.poll();
            BytecodeInstruction current = entry.getKey();
            int depth = entry.getValue();

            if (isExceptionHandlerEntry(current)) {
                return depth;
            }

            Set<ControlDependency> deps = current.getControlDependencies();
            if (deps.isEmpty()) {
                return depth;
            }

            for (ControlDependency cd : deps) {
                BytecodeInstruction parent = cd.getBranch().getInstruction();
                if (visited.add(parent)) {
                    queue.add(new AbstractMap.SimpleEntry<>(parent, depth + 1));
                }
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * Checks if the given instruction is an entry point for an exception handler.
     *
     * @param instruction the instruction to check
     * @return true if the instruction is an entry to an exception handler, false otherwise
     */
    private static boolean isExceptionHandlerEntry(BytecodeInstruction instruction) {
        if (instruction == null || !instruction.hasBasicBlockSet()) {
            return false;
        }
        try {
            ControlDependenceGraph cdg = instruction.getCDG();
            if (cdg == null) {
                return false;
            }
            if (!cdg.containsVertex(instruction.getBasicBlock())) {
                return false;
            }
            return cdg.incomingEdgesOf(instruction.getBasicBlock())
                    .stream().anyMatch(ControlFlowEdge::isExceptionEdge);
        } catch (RuntimeException e) {
            // If the graph does not contain the vertex or other graph issues
            return false;
        }
    }

    private static Map<Integer, MinBranchDistances> buildBranchTraceMinDistances(MethodCall call) {
        Map<Integer, MinBranchDistances> minDistances = new HashMap<>();
        List<Integer> path = call.branchTrace;
        List<Double> trueDistances = call.trueDistanceTrace;
        List<Double> falseDistances = call.falseDistanceTrace;
        int maxPos = Math.min(path.size(), Math.min(trueDistances.size(), falseDistances.size()));
        for (int pos = 0; pos < maxPos; pos++) {
            int branchId = path.get(pos);
            MinBranchDistances distances = minDistances.computeIfAbsent(branchId,
                    ignored -> new MinBranchDistances());
            distances.trueMinDistance = Math.min(distances.trueMinDistance, trueDistances.get(pos));
            distances.falseMinDistance = Math.min(distances.falseMinDistance, falseDistances.get(pos));
        }
        return minDistances;
    }
    private static long toBranchOutcomeKey(int branchId, boolean branchValue) {
        return (((long) branchId) << 1) | (branchValue ? 1L : 0L);
    }

    private static final class MinBranchDistances {
        private double trueMinDistance = Double.MAX_VALUE;
        private double falseMinDistance = Double.MAX_VALUE;
    }

    private static final class BfsEntry {
        final Branch branch;
        final boolean value;
        final int approachLevel;

        BfsEntry(Branch branch, boolean value, int approachLevel) {
            this.branch = branch;
            this.value = value;
            this.approachLevel = approachLevel;
        }
    }

    /**
     * Cached CDG ancestor information for a branch.  Contains the approach
     * level for every (branchId, value) pair reachable from the target branch
     * via control dependencies, plus the terminal approach level for paths
     * that reach a root-dependent or exception-handler node.
     */
    private static final class CdgAncestorInfo {
        final Map<Long, Integer> ancestorApproachLevels;
        final int terminalApproachLevel;

        CdgAncestorInfo(Map<Long, Integer> ancestorApproachLevels,
                         int terminalApproachLevel) {
            this.ancestorApproachLevels = ancestorApproachLevels;
            this.terminalApproachLevel = terminalApproachLevel;
        }
    }

}
