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
package org.evosuite.coverage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.testcase.execution.ExecutionResult;

/**
 * Shared "how close was the failing guard to not throwing" branch-distance helper.
 *
 * <p>Given the position at which a test threw, this locates the bytecode instruction that threw,
 * finds the branch(es) it is control-dependent on, and returns the normalized branch distance of the
 * direction that would have <em>avoided</em> the throw. The optional EvoObj legitimizer uses this
 * directed gradient to distinguish candidates that fail at the same test statement.
 */
public final class GuardBranchDistance {

    /** Value returned when no guard/branch-distance information can be recovered ("assume far"). */
    public static final double NO_GUARD_DISTANCE = 0.99;

    private GuardBranchDistance() {
    }

    /**
     * Normalized branch distance, in {@code [0,1)}, of the direction that avoids the instruction that
     * threw at {@code throwingPosition} — i.e. how close the failing guard is to not throwing.
     * Returns {@link #NO_GUARD_DISTANCE} when the throw is not guarded by a resolvable branch (e.g. an
     * unguarded NPE) or the instruction/branch cannot be recovered.
     */
    public static double avoidanceDistance(ExecutionResult result, int throwingPosition) {
        if (result == null || result.getTrace() == null) {
            return NO_GUARD_DISTANCE;
        }
        Throwable t = result.getExceptionThrownAtPosition(throwingPosition);
        if (t == null || t.getStackTrace().length == 0) {
            return NO_GUARD_DISTANCE;
        }
        StackTraceElement frame = t.getStackTrace()[0];
        BytecodeInstruction ins = instructionAt(frame.getClassName(), frame.getMethodName(),
                frame.getLineNumber());
        if (ins == null) {
            return NO_GUARD_DISTANCE;
        }
        Set<ControlDependency> deps = ins.getControlDependencies();
        if (deps == null || deps.isEmpty()) {
            return NO_GUARD_DISTANCE;
        }

        Map<Integer, Double> trueDistances = result.getTrace().getTrueDistances();
        Map<Integer, Double> falseDistances = result.getTrace().getFalseDistances();
        double best = NO_GUARD_DISTANCE;
        for (ControlDependency cd : deps) {
            Branch branch = cd.getBranch();
            if (branch == null) {
                continue;
            }
            // To reach the throw the branch evaluated to getBranchExpressionValue(); the distance to
            // take the opposite (non-throwing) direction is the guard distance.
            Map<Integer, Double> opposite = cd.getBranchExpressionValue() ? falseDistances : trueDistances;
            Double d = opposite.get(branch.getActualBranchId());
            if (d != null) {
                best = Math.min(best, normalize(d));
            }
        }
        return best;
    }

    private static BytecodeInstruction instructionAt(String className, String simpleMethodName, int lineNumber) {
        try {
            List<BytecodeInstruction> instructions = BytecodeInstructionPool
                    .getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getInstructionsIn(className);
            if (instructions == null) {
                return null;
            }
            for (BytecodeInstruction ins : instructions) {
                if (ins.getLineNumber() == lineNumber
                        && ins.getMethodName() != null
                        && ins.getMethodName().startsWith(simpleMethodName + "(")
                        && !ins.getControlDependencies().isEmpty()) {
                    return ins;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static double normalize(double d) {
        double v = Math.abs(d);
        return v / (v + 1.0);
    }
}
