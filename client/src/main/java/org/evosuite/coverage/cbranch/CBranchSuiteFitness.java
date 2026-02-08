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
package org.evosuite.coverage.cbranch;

import org.evosuite.Properties;
import org.evosuite.ga.archive.Archive;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

import java.util.*;
import java.util.Map.Entry;

/**
 * Context Branch criterion, force the generation of test cases that directly
 * invoke the method where the branch is, i.e., do not consider covered a branch
 * if it is covered by invoking other methods.
 *
 * @author Gordon Fraser, mattia
 */

public class CBranchSuiteFitness extends TestSuiteFitnessFunction {

    private static final long serialVersionUID = -4745892521350308986L;

    private final List<CBranchTestFitness> branchGoals;

    private final Map<Integer, Map<CallContext, Set<CBranchTestFitness>>> contextGoalsMap = new LinkedHashMap<>();

    private final Map<Integer, Set<CBranchTestFitness>> privateMethodsGoalsMap = new LinkedHashMap<>();

    private final Map<String, Map<CallContext, CBranchTestFitness>> methodsMap = new LinkedHashMap<>();

    private final Map<String, CBranchTestFitness> privateMethodsMethodsMap = new LinkedHashMap<>();

    private final Set<CBranchTestFitness> toRemoveGoals = new LinkedHashSet<>();
    private final Set<CBranchTestFitness> removedGoals = new LinkedHashSet<>();

    public CBranchSuiteFitness() {
        CBranchFitnessFactory factory = new CBranchFitnessFactory();
        branchGoals = factory.getCoverageGoals();
        initMaps();
    }

    private void initMaps() {
        for (CBranchTestFitness goal : branchGoals) {
            if (Properties.TEST_ARCHIVE) {
                Archive.getArchiveInstance().addTarget(goal);

            }
            if (goal.getBranchGoal() != null && goal.getBranchGoal().getBranch() != null) {
                int branchId = goal.getBranchGoal().getBranch().getActualBranchId();

                // if private method do not consider context
                if (goal.getContext().isEmpty()) {
                    privateMethodsGoalsMap.computeIfAbsent(branchId, k -> new LinkedHashSet<>()).add(goal);
                } else {
                    // if public method consider context
                    contextGoalsMap.computeIfAbsent(branchId, k -> new LinkedHashMap<>())
                            .computeIfAbsent(goal.getContext(), k -> new LinkedHashSet<>()).add(goal);
                }
            } else {
                String methodName = goal.getTargetClass() + "." + goal.getTargetMethod();
                // if private method do not consider context
                if (goal.getContext().isEmpty()) {
                    privateMethodsMethodsMap.put(methodName, goal);
                } else {
                    // if public method consider context
                    methodsMap.computeIfAbsent(methodName, k -> new LinkedHashMap<>())
                            .put(goal.getContext(), goal);
                }
            }
            logger.info("Context goal: " + goal);
        }
    }

    private CBranchTestFitness getContextGoal(String classAndMethodName, CallContext context) {
        if (privateMethodsMethodsMap.containsKey(classAndMethodName)) {
            return privateMethodsMethodsMap.get(classAndMethodName);
        } else if (methodsMap.get(classAndMethodName) == null
                || methodsMap.get(classAndMethodName).get(context) == null)
            return null;
        else {
            return methodsMap.get(classAndMethodName).get(context);
        }
    }

    private CBranchTestFitness getContextGoal(Integer branchId, CallContext context, boolean value) {
        if (privateMethodsGoalsMap.containsKey(branchId)) {
            for (CBranchTestFitness cBranchTestFitness : privateMethodsGoalsMap.get(branchId)) {
                if (cBranchTestFitness.getValue() == value) {
                    return cBranchTestFitness;
                }
            }
        } else if (contextGoalsMap.get(branchId) == null
                || contextGoalsMap.get(branchId).get(context) == null)
            return null;
        else {
            for (CBranchTestFitness cBranchTestFitness : contextGoalsMap.get(branchId).get(context)) {
                if (cBranchTestFitness.getValue() == value) {
                    return cBranchTestFitness;
                }
            }
        }
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.evosuite.ga.FitnessFunction#getFitness(org.evosuite.ga.Chromosome)
     */
    @Override
    public double getFitness(TestSuiteChromosome suite) {
        double fitness = 0.0;

        List<ExecutionResult> results = runTestSuite(suite);
        Map<CBranchTestFitness, Double> distanceMap = new LinkedHashMap<>();

        Map<Integer, Integer> callCounter = new LinkedHashMap<>();
        Map<Integer, Integer> branchCounter = new LinkedHashMap<>();

        for (ExecutionResult result : results) {
            if (result.hasTimeout() || result.hasTestException()) {
                continue;
            }

            // Determine minimum branch distance for each branch in each context
            assert (result.getTrace().getTrueDistancesContext().keySet().size() == result
                    .getTrace().getFalseDistancesContext().keySet().size());

            TestChromosome test = new TestChromosome();
            test.setTestCase(result.test);
            test.setLastExecutionResult(result);
            test.setChanged(false);

            for (Integer branchId : result.getTrace().getTrueDistancesContext().keySet()) {
                Map<CallContext, Double> trueMap = result.getTrace().getTrueDistancesContext()
                        .get(branchId);
                Map<CallContext, Double> falseMap = result.getTrace().getFalseDistancesContext()
                        .get(branchId);

                for (CallContext context : trueMap.keySet()) {
                    CBranchTestFitness goalT = getContextGoal(branchId, context, true);
                    if (goalT == null) {
                        continue;

                    }
                    double distanceT = normalize(trueMap.get(context));
                    if (distanceMap.get(goalT) == null || distanceMap.get(goalT) > distanceT) {
                        distanceMap.put(goalT, distanceT);
                    }
                    if (Double.compare(distanceT, 0.0) == 0) {
                        if (removedGoals.contains(goalT)) {
                            continue;
                        }
                        test.getTestCase().addCoveredGoal(goalT);
                        toRemoveGoals.add(goalT);
                    }
                    if (Properties.TEST_ARCHIVE) {
                        Archive.getArchiveInstance().updateArchive(goalT, test, distanceT);
                    }
                }

                for (CallContext context : falseMap.keySet()) {
                    CBranchTestFitness goalF = getContextGoal(branchId, context, false);
                    if (goalF == null) {
                        continue;

                    }
                    double distanceF = normalize(falseMap.get(context));
                    if (distanceMap.get(goalF) == null || distanceMap.get(goalF) > distanceF) {
                        distanceMap.put(goalF, distanceF);
                    }
                    if (Double.compare(distanceF, 0.0) == 0) {
                        if (removedGoals.contains(goalF)) {
                            continue;
                        }
                        test.getTestCase().addCoveredGoal(goalF);
                        toRemoveGoals.add(goalF);
                    }
                    if (Properties.TEST_ARCHIVE) {
                        Archive.getArchiveInstance().updateArchive(goalF, test, distanceF);
                    }
                }
            }

            for (Entry<Integer, Map<CallContext, Integer>> entry : result.getTrace()
                    .getPredicateContextExecutionCount().entrySet()) {
                for (Entry<CallContext, Integer> value : entry.getValue().entrySet()) {
                    int count = value.getValue();

                    CBranchTestFitness goalT = getContextGoal(entry.getKey(), value.getKey(), true);
                    if (goalT != null) {
                        if (branchCounter.get(goalT.getGenericContextBranchIdentifier()) == null
                                || branchCounter.get(goalT.getGenericContextBranchIdentifier()) < count) {
                            branchCounter.put(goalT.getGenericContextBranchIdentifier(), count);
                        }
                    } else {
                        CBranchTestFitness goalF = getContextGoal(entry.getKey(), value.getKey(),
                                false);
                        if (goalF != null) {
                            if (branchCounter.get(goalF.getGenericContextBranchIdentifier()) == null
                                    || branchCounter.get(goalF.getGenericContextBranchIdentifier()) < count) {
                                branchCounter.put(goalF.getGenericContextBranchIdentifier(), count);
                            }
                        }
                    }
                }
            }

            for (Entry<String, Map<CallContext, Integer>> entry : result.getTrace()
                    .getMethodContextCount().entrySet()) {
                for (Entry<CallContext, Integer> value : entry.getValue().entrySet()) {
                    CBranchTestFitness goal = getContextGoal(entry.getKey(), value.getKey());
                    if (goal == null) {
                        continue;
                    }
                    int count = value.getValue();
                    if (callCounter.get(goal.hashCode()) == null
                            || callCounter.get(goal.hashCode()) < count) {
                        callCounter.put(goal.hashCode(), count);
                    }
                    if (count > 0) {
                        if (removedGoals.contains(goal)) {
                            continue;
                        }
                        test.getTestCase().addCoveredGoal(goal);
                        toRemoveGoals.add(goal);
                    }
                    if (Properties.TEST_ARCHIVE) {
                        Archive.getArchiveInstance().updateArchive(goal, test, count == 0 ? 1.0 : 0.0);
                    }
                }
            }
        }

        int numCoveredGoals = removedGoals.size();
        for (CBranchTestFitness goal : branchGoals) {
            if (removedGoals.contains(goal)) {
                continue;
            }
            Double distance = distanceMap.get(goal);
            if (distance == null) {
                distance = 1.0;

            }
            if (goal.getBranch() == null) {
                Integer count = callCounter.get(goal.hashCode());
                if (count == null || count == 0) {
                    fitness += 1;
                } else {
                    numCoveredGoals++;
                }
            } else {
                Integer count = branchCounter.get(goal.getGenericContextBranchIdentifier());
                if (count == null || count == 0) {
                    fitness += 1;
                    // If the branch was executed but not covered, we add 0.5 to the fitness
                    // to distinguish it from a branch that was not executed at all.
                } else if (count == 1) {
                    fitness += 0.5;
                } else {
                    if (Double.compare(distance, 0.0) == 0) {
                        numCoveredGoals++;
                    }
                    fitness += distance;
                }
            }
        }

        if (!branchGoals.isEmpty()) {
            suite.setCoverage(this, (double) numCoveredGoals / (double) branchGoals.size());
        } else {
            suite.setCoverage(this, 1);
        }
        suite.setNumOfCoveredGoals(this, numCoveredGoals);
        suite.setNumOfNotCoveredGoals(this, branchGoals.size() - numCoveredGoals);
        updateIndividual(suite, fitness);

        return fitness;
    }

    @Override
    public boolean updateCoveredGoals() {
        if (!Properties.TEST_ARCHIVE) {
            return false;
        }

        this.removedGoals.addAll(this.toRemoveGoals);

        this.toRemoveGoals.clear();
        logger.info("Current state of archive: " + Archive.getArchiveInstance().toString());

        return true;
    }

}
