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
package org.evosuite;

import org.evosuite.assertion.InspectorManager;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.contracts.ContractChecker;
import org.evosuite.contracts.FailingTestSet;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.coverage.mutation.MutationTimeouts;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.stoppingconditions.GlobalTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.instrumentation.LinePool;
import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.classhandling.ModifiedTargetStaticFields;
import org.evosuite.runtime.instrumentation.MethodCallReplacementCache;
import org.evosuite.runtime.instrumentation.RemoveFinalClassAdapter;
import org.evosuite.runtime.util.JOptionPaneInputs;
import org.evosuite.runtime.util.SystemInUtil;
import org.evosuite.seeding.CastClassManager;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.setup.ConcreteClassAnalyzer;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.testcarver.extraction.CarvingManager;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Resets the state of a test generation run.
 *
 * <p>The reset spans the pools, caches and singletons of most packages, so it lives here and not
 * in {@link TestGenerationContext}, which only holds the class loader, the object-rule runtime and
 * the SUT-execution flag.
 */
public final class ContextResetter {

    private static final Logger logger = LoggerFactory.getLogger(ContextResetter.class);

    private ContextResetter() {
    }

    /**
     * Resets the test generation context.
     *
     * <p>This involves clearing pools (branch, mutation, graph, etc.), resetting static state,
     * re-initializing the instrumenting class loader, and resetting the archive and stopping
     * conditions. This is typically done when starting a new search or when re-instrumentation is
     * needed.
     */
    public static void reset() {
        final TestGenerationContext context = TestGenerationContext.getInstance();
        logger.info("*** Resetting context");

        // A fresh context needs a fresh class loader to make sure we can
        // re-instrument classes
        context.renewClassLoader();
        final InstrumentingClassLoader classLoader = context.getClassLoaderForSUT();

        TestCaseExecutor.pullDown();
        TestCaseExecutor.resetAdaptiveTimeoutStateIfPresent();

        ExecutionTracer.getExecutionTracer().clear();

        BranchPool.clearAll();
        RemoveFinalClassAdapter.reset();
        org.evosuite.testcase.statements.FunctionalMockStatement.resetUsefulMockSurfaceCache();
        LinePool.reset();
        MutationPool.getInstance(classLoader).clear();

        // TODO: Clear only pool of current classloader?
        GraphPool.clearAll();
        DefUsePool.clear();

        // Reset all run-scoped object-construction analysis and synthesis state.
        context.getObjectRuleRuntime().reset();

        // TODO: Clear only pool of current classloader?
        BytecodeInstructionPool.clearAll();

        // TODO: After this, the test cluster is empty until
        // DependencyAnalysis.analyse is called

        // Keep inheritance metadata when classpath is unchanged; it is safe to rebuild other
        // caches.
        DependencyAnalysis.clear(true);
        TestCluster.reset();
        CastClassManager.getInstance().clear();
        org.evosuite.utils.generic.GenericClassImpl.clearSatisfiesBoundariesCache();
        ConcreteClassAnalyzer.getInstance().clear();
        // This counts the current level of recursion during test generation
        org.evosuite.testcase.TestFactory.getInstance().reset();

        MaxStatementsStoppingCondition.setNumExecutedStatements(0);
        GlobalTimeStoppingCondition.forceReset();
        MutationTimeouts.resetStatic();

        // Forget the old SUT
        Properties.resetTargetClass();

        TestCaseExecutor.initExecutor();

        Archive.getArchiveInstance().reset();

        // Constant pool
        ConstantPoolManager.getInstance().reset();
        ObjectPoolManager.getInstance().reset();
        CarvingManager.getInstance().clear();

        // TODO: Why are we doing this?
        if (Properties.INSTRUMENT_CONTEXT || !Properties.TARGET_CLASS.isEmpty()) {
            try {
                // 1. Initialize the callGraph before using
                String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();
                DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Arrays.asList(cp.split(File.pathSeparator)));
                TestClusterGenerator testClusterGenerator = new TestClusterGenerator(
                        DependencyAnalysis.getInheritanceTree());
                context.setTestClusterGenerator(testClusterGenerator);
                // 2. Use the callGraph
                testClusterGenerator.generateCluster(DependencyAnalysis.getCallGraph());
            } catch (RuntimeException e) {
                logger.error(e.getMessage(), e);
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
        }

        if (Properties.CHECK_CONTRACTS) {
            FailingTestSet.changeClassLoader(classLoader);
        }
        ContractChecker.setActive(true);

        SystemInUtil.resetSingleton();
        JOptionPaneInputs.resetSingleton();
        Runtime.resetSingleton();
        MethodCallReplacementCache.resetSingleton();

        DSEStatistics.clear();

        // keep the list of initialized classes (clear them when needed in
        // the system test cases)
        final List<String> initializedClasses = ClassReInitializer.getInstance().getInitializedClasses();
        ClassReInitializer.resetSingleton();
        ClassReInitializer.getInstance().addInitializedClasses(initializedClasses);

        InspectorManager.resetSingleton();
        ModifiedTargetStaticFields.resetSingleton();
    }
}
