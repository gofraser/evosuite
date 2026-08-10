/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase.factories;

import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TimeController;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.ControlFlowDistance;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Regression tests for the production EvoObj counters and diagnostics. */
public class ObjectRuleStatisticsTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime().getTemplateCache().reset();
        session().clearWorkingTarget();
        TimeController.resetSingleton();
        org.evosuite.TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void unchangedSeedIsNotAnObjectSynthesisAction() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test), 0);
        assertFalse(ObjectRuleTemplateSynthesizer.hasGraphDerivedActions(1, test));
        test.addStatement(new IntPrimitiveStatement(test), test.size());
        assertTrue(ObjectRuleTemplateSynthesizer.hasGraphDerivedActions(1, test));
    }

    @Test
    public void resetStatisticsClearsProductionCounters() {
        session().templateInjections = 11;
        session().templateSynthesisTimeMs = 1234;
        session().currentIteration = 9;
        session().templateCacheHits = 5;
        session().freshSyntheses = 4;
        session().firstShotSuccesses = 2;
        session().immediateCoverage = 1;
        session().reachedNotCovered = 1;
        session().workingGoalEvaluationFailures = 2;

        session().reset();

        assertEquals(0, session().templateInjections);
        assertEquals(0L, session().templateSynthesisTimeMs);
        assertEquals(0, session().currentIteration);
        assertEquals(0, session().templateCacheHits);
        assertEquals(0, session().freshSyntheses);
        assertEquals(0, session().firstShotSuccesses);
        assertEquals(0, session().immediateCoverage);
        assertEquals(0, session().reachedNotCovered);
        assertEquals(0, session().workingGoalEvaluationFailures);
        assertTrue(session().getWorkingGoals().isEmpty());
    }

    @Test
    public void firstShotScoringFailurePreservesInvariantAndTemplate() {
        session().reset();
        session().setWorkingTarget(0, null,
                Collections.singletonList(mock(BranchCoverageTestFitness.class)));
        DefaultTestCase original = new DefaultTestCase();
        IntPrimitiveStatement value = new IntPrimitiveStatement(original, 17);
        value.setObjectRuleSlot(true);
        original.addStatement(value);

        ObjectRuleTemplateSynthesizer.FirstShotClassification classification =
                ObjectRuleTemplateSynthesizer.classifyFirstShot(
                        original, new ExecutionResult(original, null),
                        (test, result) -> {
                            throw new IllegalStateException("forced scoring failure");
                        });

        assertFalse(classification.immediateCoverage);
        assertEquals(1, session().firstShotSuccesses);
        assertEquals(0, session().reachedNotCovered);
        assertEquals(1, session().workingGoalUnavailable);
        assertEquals(1, session().workingGoalEvaluationFailures);
        assertEquals(Integer.valueOf(17), value.getValue());
    }

    @Test
    public void workingGoalScoringUsesExistingExecutionTrace() {
        DefaultTestCase original = new DefaultTestCase();
        original.addStatement(new IntPrimitiveStatement(original, 17));
        ExecutionResult result = new ExecutionResult(original, null);
        BranchCoverageTestFitness fitness = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal goal = mock(BranchCoverageGoal.class);
        ControlFlowDistance distance = mock(ControlFlowDistance.class);
        when(fitness.getBranchGoal()).thenReturn(goal);
        when(goal.getDistance(result)).thenReturn(distance);
        when(distance.getResultingBranchFitness()).thenReturn(0.25);
        session().setWorkingTarget(0, null, Collections.singletonList(fitness));

        assertEquals(0.25, ObjectRuleTemplateSynthesizer.bestWorkingGoalFitness(original, result), 0.0);
        verify(fitness).getBranchGoal();
        verify(goal).getDistance(result);
        verify(distance).getResultingBranchFitness();
        verifyNoMoreInteractions(fitness);
    }

    @Test
    public void objectRuleCountersResetThroughConstructionContext() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        ObjectRuleBranchSelector selector = org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime().getBranchSelector();
        org.evosuite.coverage.branch.Branch branch = org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime().getBranchInterestedVarsMap().get(Properties.TARGET_METHOD)
                .entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        assertTrue(branch != null);
        selector.isEligible(branch);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);
        TargetContext.forBranch(null);
        assertTrue(selector.getBlacklistedCount() > 0);
        org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime().reset();
        assertEquals(0, selector.getBlacklistedCount());
    }

    @Test
    public void objectGraphStatsReflectAnalysis() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        ObjectRuleRuntime runtime = org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime();
        assertTrue(ObjectRuleStatisticsReporter.countObjectGraphBranches(runtime) > 0);
        assertTrue(ObjectRuleStatisticsReporter.maxObjectGraphSize(runtime) >= 1);
        ObjectRuleStatisticsReporter.publish(
                org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime());
    }

    @Test
    public void statsSafeWhenContextEmpty() {
        org.evosuite.TestGenerationContext.getInstance().resetContext();
        ObjectRuleRuntime runtime = org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime();
        assertEquals(0, ObjectRuleStatisticsReporter.countObjectGraphBranches(runtime));
        assertEquals(0, ObjectRuleStatisticsReporter.maxObjectGraphSize(runtime));
        ObjectRuleStatisticsReporter.publish(
                org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime());
    }

    private static ObjectRuleSession session() {
        return org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime().getSession();
    }

    private static void analyze(Class<?> clazz, String methodName, int parameterCount) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, parameterCount);
        Properties.APPLY_OBJECT_RULE = true;
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
        } catch (ClassNotFoundException | RuntimeException failure) {
            throw new AssertionError("analyzeClass failed for " + clazz, failure);
        }
        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);
        TestCaseExecutor.initExecutor();
    }

    private static String signatureOf(Class<?> clazz, String methodName, int parameterCount) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == parameterCount) {
                return method.getName() + MethodUtil.getSignature(method);
            }
        }
        throw new IllegalStateException("No matching method");
    }
}
