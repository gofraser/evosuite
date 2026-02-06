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
package org.evosuite.coverage.line;

import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.*;
import java.util.Map.Entry;

/**
 * Fitness function for a whole test suite for all lines.
 *
 * @author Gordon Fraser, Jose Miguel Rojas
 */
public class LineCoverageSuiteFitness extends AbstractLineCoverageSuiteFitness {

    private static final long serialVersionUID = -6369027784777941998L;

    private final Set<Integer> branchesToCoverTrue = new LinkedHashSet<>();
    private final Set<Integer> branchesToCoverFalse = new LinkedHashSet<>();
    private final Set<Integer> branchesToCoverBoth = new LinkedHashSet<>();

    public LineCoverageSuiteFitness() {
        super();
        updateControlDependencies();
    }

    @Override
    public boolean updateCoveredGoals() {
        boolean changed = super.updateCoveredGoals();
        if (changed) {
            updateControlDependencies();
        }
        return changed;
    }

    /**
     * Add guidance to the fitness function by including branch distances on
     * all control dependencies
     */
    private void updateControlDependencies() {
        branchesToCoverTrue.clear();
        branchesToCoverFalse.clear();
        branchesToCoverBoth.clear();

        Set<Integer> activeLines = new HashSet<>();
        for (TestFitnessFunction ff : lineGoals.values()) {
            if (ff instanceof LineCoverageTestFitness) {
                activeLines.add(((LineCoverageTestFitness) ff).getLine());
            }
        }

        // In case we target more than one class (context, or inner classes)
        Set<String> targetClasses = new LinkedHashSet<>();
        for (TestFitnessFunction ff : lineGoals.values()) {
            targetClasses.add(ff.getTargetClass());
        }
        for (String className : targetClasses) {
            List<BytecodeInstruction> instructions = BytecodeInstructionPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getInstructionsIn(className);

            if (instructions == null) {
                logger.info("No instructions known for class {} (is it an enum?)", className);
                continue;
            }
            for (BytecodeInstruction bi : instructions) {
                if (!activeLines.contains(bi.getLineNumber())) {
                    continue;
                }

                if (bi.getBasicBlock() == null) {
                    // Labels get no basic block. TODO - why?
                    continue;
                }

                // The order of CDs may be nondeterminstic
                // TODO: A better solution would be to make the CD order deterministic rather than sorting here
                List<ControlDependency> cds = new ArrayList<>(bi.getControlDependencies());
                Collections.sort(cds);
                for (ControlDependency cd : cds) {
                    if (cd.getBranchExpressionValue()) {
                        branchesToCoverTrue.add(cd.getBranch().getActualBranchId());
                    } else {
                        branchesToCoverFalse.add(cd.getBranch().getActualBranchId());
                    }
                }
            }
        }
        branchesToCoverBoth.addAll(branchesToCoverTrue);
        branchesToCoverBoth.retainAll(branchesToCoverFalse);
        branchesToCoverTrue.removeAll(branchesToCoverBoth);
        branchesToCoverFalse.removeAll(branchesToCoverBoth);

        logger.info("Covering branches true: " + branchesToCoverTrue);
        logger.info("Covering branches false: " + branchesToCoverFalse);
        logger.info("Covering branches both: " + branchesToCoverBoth);
    }

    @Override
    protected double getAdditionalFitness(List<ExecutionResult> results) {
        return getControlDependencyGuidance(results);
    }

    private double getControlDependencyGuidance(List<ExecutionResult> results) {
        Map<Integer, Integer> predicateCount = new LinkedHashMap<>();
        Map<Integer, Double> trueDistance = new LinkedHashMap<>();
        Map<Integer, Double> falseDistance = new LinkedHashMap<>();

        for (ExecutionResult result : results) {
            if (result.hasTimeout() || result.hasTestException()) {
                continue;
            }
            for (Entry<Integer, Integer> entry : result.getTrace().getPredicateExecutionCount().entrySet()) {
                predicateCount.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            for (Entry<Integer, Double> entry : result.getTrace().getTrueDistances().entrySet()) {
                trueDistance.merge(entry.getKey(), entry.getValue(), Math::min);
            }
            for (Entry<Integer, Double> entry : result.getTrace().getFalseDistances().entrySet()) {
                falseDistance.merge(entry.getKey(), entry.getValue(), Math::min);
            }
        }

        double distance = 0.0;

        for (Integer branchId : branchesToCoverBoth) {
            if (!predicateCount.containsKey(branchId)) {
                distance += 2.0;
            } else if (predicateCount.get(branchId) == 1) {
                distance += 1.0;
            } else {
                distance += normalize(trueDistance.get(branchId));
                distance += normalize(falseDistance.get(branchId));
            }
        }

        for (Integer branchId : branchesToCoverTrue) {
            if (!trueDistance.containsKey(branchId)) {
                distance += 1;
            } else {
                distance += normalize(trueDistance.get(branchId));
            }
        }

        for (Integer branchId : branchesToCoverFalse) {
            if (!falseDistance.containsKey(branchId)) {
                distance += 1;
            } else {
                distance += normalize(falseDistance.get(branchId));
            }
        }

        return distance;
    }
}
