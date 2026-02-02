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
package org.evosuite.ga.metaheuristics.mosa.structural;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.exception.ExceptionCoverageFactory;
import org.evosuite.coverage.exception.ExceptionCoverageHelper;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.exception.TryCatchCoverageTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.mutation.StrongMutationTestFitness;
import org.evosuite.coverage.mutation.WeakMutationTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.setup.CallContext;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

/**
 * A class for managing multiple coverage targets simultaneously.
 */
public class MultiCriteriaManager extends StructuralGoalManager implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(MultiCriteriaManager.class);

    private static final long serialVersionUID = 8161137239404885564L;

    protected Map<BranchCoverageTestFitness, Set<TestFitnessFunction>> dependencies;

    /**
     * Creates a new {@code MultiCriteriaManager} with the given list of targets. The targets are
     * encoded as fitness functions, which are expected to be minimization functions.
     *
     * @param targets The targets to cover encoded as minimization functions
     */
    public MultiCriteriaManager(List<TestFitnessFunction> targets) {
        super(targets);

        // initialize the dependency graph among branches
        this.graph = getControlDependencies4Branches(targets);

        // initialize the dependency graph between branches and other coverage targets (e.g., statements)
        for (Criterion criterion : Properties.CRITERION) {
            switch (criterion) {
                case BRANCH:
                    break; // branches have been handled by getControlDependencies4Branches
                case EXCEPTION:
                    break; // exception coverage is handled by calculateFitness
                case LINE:
                    addDependencies4Line();
                    break;
                case STATEMENT:
                    addDependencies4Statement();
                    break;
                case WEAKMUTATION:
                    addDependencies4WeakMutation();
                    break;
                case STRONGMUTATION:
                    addDependencies4StrongMutation();
                    break;
                case METHOD:
                    addDependencies4Methods();
                    break;
                case INPUT:
                    addDependencies4Input();
                    break;
                case OUTPUT:
                    addDependencies4Output();
                    break;
                case TRYCATCH:
                    addDependencies4TryCatch();
                    break;
                case METHODNOEXCEPTION:
                    addDependencies4MethodsNoException();
                    break;
                case CBRANCH:
                    addDependencies4CBranch();
                    break;
                default:
                    LoggingUtils.getEvoLogger().error("The criterion {} is not currently supported in DynaMOSA", criterion.name());
            }
        }

        // initialize current goals
        this.currentGoals.addAll(graph.getRootBranches());
    }

    @Override
    protected Set<TestFitnessFunction> getDependencies(TestFitnessFunction fitnessFunction) {
        Set<TestFitnessFunction> deps = new LinkedHashSet<>(graph.getStructuralChildren(fitnessFunction));
        if (fitnessFunction instanceof BranchCoverageTestFitness && dependencies.containsKey(fitnessFunction)) {
            deps.addAll(dependencies.get(fitnessFunction));
        }
        return deps;
    }

    @Override
    protected void postCalculateFitness(TestChromosome c) {
        // let's manage the exception coverage
        if (ArrayUtil.contains(Properties.CRITERION, Criterion.EXCEPTION)) {
            // if one of the coverage criterion is Criterion.EXCEPTION,
            // then we have to analyze the results of the execution do look
            // for generated exceptions
            Set<ExceptionCoverageTestFitness> set = deriveCoveredExceptions(c);
            for (ExceptionCoverageTestFitness exp : set) {
                // let's update the list of fitness functions
                updateCoveredGoals(exp, c);
                // new covered exceptions (goals) have to be added to the archive
                if (!ExceptionCoverageFactory.getGoals().containsKey(exp.getKey())) {
                    // let's update the newly discovered exceptions to ExceptionCoverageFactory
                    ExceptionCoverageFactory.getGoals().put(exp.getKey(), exp);
                }
            }
        }
    }

    private void addDependencies4TryCatch() {
        logger.debug("Added dependencies for Try-Catch");
        for (FitnessFunction<TestChromosome> ff : this.getUncoveredGoals()) {
            if (ff instanceof TryCatchCoverageTestFitness) {
                TryCatchCoverageTestFitness stmt = (TryCatchCoverageTestFitness) ff;
                BranchCoverageTestFitness branch = new BranchCoverageTestFitness(stmt.getBranchGoal());
                if (this.dependencies.containsKey(branch)) {
                    this.dependencies.get(branch).add(stmt);
                }
            }
        }
    }

    private void addDependencies4Output() {
        logger.debug("Added dependencies for Output");
        for (TestFitnessFunction ff : this.getUncoveredGoals()) {
            if (ff instanceof OutputCoverageTestFitness) {
                OutputCoverageTestFitness output = (OutputCoverageTestFitness) ff;
                processInstructionDependencies(output.getClassName(), output.getMethod(), ff);
            }
        }
    }

    private void addDependencies4Input() {
        logger.debug("Added dependencies for Input");
        for (TestFitnessFunction ff : this.getUncoveredGoals()) {
            if (ff instanceof InputCoverageTestFitness) {
                InputCoverageTestFitness input = (InputCoverageTestFitness) ff;
                processInstructionDependencies(input.getClassName(), input.getMethod(), ff);
            }
        }
    }

    private void processInstructionDependencies(String className, String methodName, TestFitnessFunction ff) {
        ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
        List<BytecodeInstruction> instructions = pool.getInstructionsIn(className, methodName);

        if (instructions == null) {
            this.currentGoals.add(ff);
            return;
        }

        for (BytecodeInstruction instruction : instructions) {
            if (instruction.getBasicBlock() != null) {
                Set<ControlDependency> cds = instruction.getBasicBlock().getControlDependencies();
                if (cds.isEmpty()) {
                    this.currentGoals.add(ff);
                } else {
                    for (ControlDependency cd : cds) {
                        BranchCoverageTestFitness fitness = BranchCoverageFactory.createBranchCoverageTestFitness(cd);
                        if (this.dependencies.containsKey(fitness)) {
                            this.dependencies.get(fitness).add(ff);
                        }
                    }
                }
            }
        }
    }

    private void addDependencies4Methods() {
        logger.debug("Added dependencies for Methods");
        for (BranchCoverageTestFitness branch : this.dependencies.keySet()) {
            MethodCoverageTestFitness method = new MethodCoverageTestFitness(branch.getClassName(), branch.getMethod());
            this.dependencies.get(branch).add(method);
        }
    }

    private void addDependencies4MethodsNoException() {
        logger.debug("Added dependencies for MethodsNoException");
        for (BranchCoverageTestFitness branch : this.dependencies.keySet()) {
            MethodNoExceptionCoverageTestFitness method = new MethodNoExceptionCoverageTestFitness(branch.getClassName(), branch.getMethod());
            this.dependencies.get(branch).add(method);
        }
    }

    private void addDependencies4CBranch() {
        logger.debug("Added dependencies for CBranch");
        CallGraph callGraph = DependencyAnalysis.getCallGraph();
        for (BranchCoverageTestFitness branch : this.dependencies.keySet()) {
            Set<CallContext> entryPoints = callGraph.getMethodEntryPoint(branch.getClassName(), branch.getMethod());
            for (CallContext context : entryPoints) {
                CBranchTestFitness cBranch = new CBranchTestFitness(branch.getBranchGoal(), context);
                this.dependencies.get(branch).add(cBranch);
                logger.debug("Added context branch: {}", cBranch);
            }
        }
    }

    private void addDependencies4WeakMutation() {
        logger.debug("Added dependencies for Weak-Mutation");
        for (TestFitnessFunction ff : this.getUncoveredGoals()) {
            if (ff instanceof WeakMutationTestFitness) {
                WeakMutationTestFitness mutation = (WeakMutationTestFitness) ff;
                processMutationDependencies(mutation.getMutation().getControlDependencies(), ff);
            }
        }
    }

    private void addDependencies4StrongMutation() {
        logger.debug("Added dependencies for Strong-Mutation");
        for (TestFitnessFunction ff : this.getUncoveredGoals()) {
            if (ff instanceof StrongMutationTestFitness) {
                StrongMutationTestFitness mutation = (StrongMutationTestFitness) ff;
                processMutationDependencies(mutation.getMutation().getControlDependencies(), ff);
            }
        }
    }

    private void processMutationDependencies(Set<BranchCoverageGoal> goals, TestFitnessFunction ff) {
        if (goals.isEmpty()) {
            this.currentGoals.add(ff);
        } else {
            for (BranchCoverageGoal goal : goals) {
                BranchCoverageTestFitness fitness = new BranchCoverageTestFitness(goal);
                if (this.dependencies.containsKey(fitness)) {
                    this.dependencies.get(fitness).add(ff);
                }
            }
        }
    }

    private void addDependencies4Line() {
        logger.debug("Added dependencies for Lines");
        for (TestFitnessFunction ff : this.getUncoveredGoals()) {
            if (ff instanceof LineCoverageTestFitness) {
                LineCoverageTestFitness line = (LineCoverageTestFitness) ff;
                ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
                BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
                BytecodeInstruction instruction = pool.getFirstInstructionAtLineNumber(line.getClassName(), line.getMethod(), line.getLine());
                if (instruction == null) {
                    continue;
                }
                Set<ControlDependency> cds = instruction.getControlDependencies();
                if (cds.isEmpty())
                    this.currentGoals.add(ff);
                else {
                    for (ControlDependency cd : cds) {
                        BranchCoverageTestFitness fitness = BranchCoverageFactory.createBranchCoverageTestFitness(cd);
                        if (this.dependencies.containsKey(fitness)) {
                            this.dependencies.get(fitness).add(ff);
                        }
                    }
                }
            }
        }
    }

    private void addDependencies4Statement() {
        logger.debug("Added dependencies for Statements");
        for (TestFitnessFunction ff : this.getUncoveredGoals()) {
            if (ff instanceof StatementCoverageTestFitness) {
                StatementCoverageTestFitness stmt = (StatementCoverageTestFitness) ff;
                if (stmt.getBranchFitnesses().isEmpty())
                    this.currentGoals.add(ff);
                else {
                    for (BranchCoverageTestFitness branch : stmt.getBranchFitnesses()) {
                        if (this.dependencies.containsKey(branch)) {
                            this.dependencies.get(branch).add(stmt);
                        }
                    }
                }
            }
        }
    }

    /**
     * This method analyzes the execution results of a TestChromosome looking for generated exceptions.
     * Such exceptions are converted in instances of the class {@link ExceptionCoverageTestFitness},
     * which are additional covered goals when using as criterion {@link Properties.Criterion Exception}
     *
     * @param t TestChromosome to analyze
     * @return list of exception goals being covered by t
     */
    public Set<ExceptionCoverageTestFitness> deriveCoveredExceptions(TestChromosome t) {
        Set<ExceptionCoverageTestFitness> covered_exceptions = new LinkedHashSet<>();
        ExecutionResult result = t.getLastExecutionResult();

        if (result.calledReflection())
            return covered_exceptions;

        for (Integer i : result.getPositionsWhereExceptionsWereThrown()) {
            if (ExceptionCoverageHelper.shouldSkip(result, i)) {
                continue;
            }

            Class<?> exceptionClass = ExceptionCoverageHelper.getExceptionClass(result, i);
            String methodIdentifier = ExceptionCoverageHelper.getMethodIdentifier(result, i); //eg name+descriptor
            boolean sutException = ExceptionCoverageHelper.isSutException(result, i); // was the exception originated by a direct call on the SUT?

            if (sutException) {

                ExceptionCoverageTestFitness.ExceptionType type = ExceptionCoverageHelper.getType(result, i);
                /*
                 * Add goal to list of fitness functions to solve
                 */
                ExceptionCoverageTestFitness goal = new ExceptionCoverageTestFitness(Properties.TARGET_CLASS, methodIdentifier, exceptionClass, type);
                covered_exceptions.add(goal);
            }
        }
        return covered_exceptions;
    }

    public BranchFitnessGraph getControlDependencies4Branches(List<TestFitnessFunction> fitnessFunctions) {
        Set<TestFitnessFunction> setOfBranches = new LinkedHashSet<>();
        this.dependencies = new LinkedHashMap<>();

        List<BranchCoverageTestFitness> branches = new BranchCoverageFactory().getCoverageGoals();
        for (BranchCoverageTestFitness branch : branches) {
            setOfBranches.add(branch);
            this.dependencies.put(branch, new LinkedHashSet<>());
        }

        // initialize the maps
        this.initializeMaps(setOfBranches);

        return new BranchFitnessGraph(setOfBranches);
    }
}
