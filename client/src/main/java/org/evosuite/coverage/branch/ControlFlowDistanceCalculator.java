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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final Map<Integer, Integer> CDG_DEPTH_CACHE = new ConcurrentHashMap<>();

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

        // Minimal distance between target node and path
        for (MethodCall call : result.getTrace().getMethodCalls()) {
            if (call.className.equals(className) && call.methodName.equals(methodName)) {
                ControlFlowDistance distance;
                Map<BranchOutcome, ControlFlowDistance> memoizedDistances = new HashMap<>();
                Set<Integer> activeBranches = new HashSet<>();
                distance = getNonRootDistance(result, call, branch, value, className,
                        methodName, memoizedDistances, activeBranches);
                if (distance.compareTo(resultDistance) < 0) {
                    resultDistance = distance;
                }
            }
        }

        return resultDistance;
    }

    private static ControlFlowDistance getNonRootDistance(ExecutionResult result,
                                                          MethodCall call, Branch branch, boolean value,
                                                          String className,
                                                          String methodName,
                                                          Map<BranchOutcome, ControlFlowDistance> memoizedDistances,
                                                          Set<Integer> activeBranches) {

        if (branch == null) {
            throw new IllegalStateException(
                    "expect getNonRootDistance() to only be called if this goal's branch is not a root branch");
        }
        if (call == null) {
            throw new IllegalArgumentException("null given");
        }

        BranchOutcome branchOutcome = new BranchOutcome(branch.getActualBranchId(), value);
        ControlFlowDistance memoizedDistance = memoizedDistances.get(branchOutcome);
        if (memoizedDistance != null) {
            return copyDistance(memoizedDistance);
        }

        if (!activeBranches.add(branch.getActualBranchId())) {
            // Fast escape for cycles discovered during recursive distance expansion.
            // Computing CDG depth here can be very expensive and does not improve
            // guidance quality for cyclic dependencies.
            return worstPossibleDistanceWithoutCDGComputation();
        }

        try {
            List<Double> trueDistances = call.trueDistanceTrace;
            List<Double> falseDistances = call.falseDistanceTrace;

            Set<Integer> branchTracePositions = determineBranchTracePositions(call, branch);

            if (!branchTracePositions.isEmpty()) {

                // branch was traced in given path
                ControlFlowDistance resultDistance = new ControlFlowDistance(0, Double.MAX_VALUE);

                for (Integer branchTracePosition : branchTracePositions) {
                    if (value) {
                        resultDistance.setBranchDistance(Math.min(resultDistance.getBranchDistance(),
                                trueDistances.get(branchTracePosition)));
                    } else {
                        resultDistance.setBranchDistance(Math.min(resultDistance.getBranchDistance(),
                                falseDistances.get(branchTracePosition)));
                    }
                }

                if (resultDistance.getBranchDistance() == Double.MAX_VALUE) {
                    throw new IllegalStateException("should be impossible");
                }

                memoizedDistances.put(branchOutcome, copyDistance(resultDistance));
                return resultDistance;
            }

            ControlFlowDistance controlDependenceDistance = getControlDependenceDistancesFor(
                    result,
                    call,
                    branch.getInstruction(),
                    className,
                    methodName,
                    memoizedDistances,
                    activeBranches);

            controlDependenceDistance.increaseApproachLevel();
            memoizedDistances.put(branchOutcome, copyDistance(controlDependenceDistance));

            return controlDependenceDistance;
        } finally {
            activeBranches.remove(branch.getActualBranchId());
        }
    }

    private static ControlFlowDistance getControlDependenceDistancesFor(
            ExecutionResult result, MethodCall call, BytecodeInstruction instruction,
            String className, String methodName,
            Map<BranchOutcome, ControlFlowDistance> memoizedDistances,
            Set<Integer> activeBranches) {

        Set<ControlFlowDistance> cdDistances = getDistancesForControlDependentBranchesOf(result,
                call,
                instruction,
                className,
                methodName,
                memoizedDistances,
                activeBranches);

        if (cdDistances == null) {
            throw new IllegalStateException("expect cdDistances to never be null");
        }

        return Collections.min(cdDistances);
    }

    /**
     * Returns a set containing the ControlFlowDistances in the given result for
     * all branches the given instruction is control dependent on.
     */
    private static Set<ControlFlowDistance> getDistancesForControlDependentBranchesOf(
            ExecutionResult result, MethodCall call, BytecodeInstruction instruction,
            String className, String methodName,
            Map<BranchOutcome, ControlFlowDistance> memoizedDistances,
            Set<Integer> activeBranches) {

        if (isExceptionHandlerEntry(instruction)) {
            Set<ControlFlowDistance> resultDistance = new HashSet<>();
            resultDistance.add(new ControlFlowDistance());
            return resultDistance;
        }

        Set<ControlFlowDistance> resultDistance = new HashSet<>();
        Set<ControlDependency> nextToLookAt = instruction.getControlDependencies();

        for (ControlDependency next : nextToLookAt) {
            if (instruction.equals(next.getBranch().getInstruction())) {
                continue; // avoid loops
            }

            boolean nextValue = next.getBranchExpressionValue();
            ControlFlowDistance nextDistance = getNonRootDistance(result, call,
                    next.getBranch(),
                    nextValue, className,
                    methodName,
                    memoizedDistances,
                    activeBranches);
            assert (nextDistance != null);
            resultDistance.add(nextDistance);
        }

        if (resultDistance.isEmpty()) {
            // instruction only dependent on root branch
            resultDistance.add(new ControlFlowDistance());
        }

        return resultDistance;
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

    private static Set<Integer> determineBranchTracePositions(MethodCall call,
                                                              Branch branch) {

        Set<Integer> positions = new HashSet<>();
        List<Integer> path = call.branchTrace;
        for (int pos = 0; pos < path.size(); pos++) {
            if (path.get(pos) == branch.getActualBranchId()) {
                positions.add(pos);
            }
        }
        return positions;
    }

    private static ControlFlowDistance copyDistance(ControlFlowDistance original) {
        return new ControlFlowDistance(original.getApproachLevel(), original.getBranchDistance());
    }

    private static final class BranchOutcome {
        private final int branchId;
        private final boolean branchValue;

        private BranchOutcome(int branchId, boolean branchValue) {
            this.branchId = branchId;
            this.branchValue = branchValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BranchOutcome)) {
                return false;
            }
            BranchOutcome that = (BranchOutcome) o;
            return branchId == that.branchId && branchValue == that.branchValue;
        }

        @Override
        public int hashCode() {
            return Objects.hash(branchId, branchValue);
        }
    }

}
