/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase.factories;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TimeController;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.ControlFlowDistance;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Regression tests for the production EvoObj counters and diagnostics. */
public class ObjectRuleStatisticsTest {

    private Logger templateSynthesizerLogger;
    private ListAppender<ILoggingEvent> templateSynthesizerLogAppender;
    private boolean templateSynthesizerLoggerAdditive;
    private Level templateSynthesizerLoggerLevel;

    @BeforeEach
    public void captureTemplateSynthesizerLogs() {
        templateSynthesizerLogger =
                (Logger) LoggerFactory.getLogger(ObjectRuleTemplateSynthesizer.class);
        templateSynthesizerLogAppender = new ListAppender<>();
        templateSynthesizerLogAppender.start();
        templateSynthesizerLogger.addAppender(templateSynthesizerLogAppender);
        templateSynthesizerLoggerAdditive = templateSynthesizerLogger.isAdditive();
        templateSynthesizerLoggerLevel = templateSynthesizerLogger.getLevel();
        templateSynthesizerLogger.setAdditive(false);
        templateSynthesizerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    public void tearDown() {
        try {
            Properties.TARGET_METHOD = "";
            Properties.TARGET_CLASS = "";
            Properties.APPLY_OBJECT_RULE = false;
            Properties.P_OBJECT_RULE_APPLICATION = 0.6;
            org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime().getTemplateCache().reset();
            session().clearWorkingTarget();
            TimeController.resetSingleton();
            org.evosuite.TestGenerationContext.getInstance().resetContext();
        } finally {
            templateSynthesizerLogger.detachAppender(templateSynthesizerLogAppender);
            templateSynthesizerLogger.setAdditive(templateSynthesizerLoggerAdditive);
            templateSynthesizerLogger.setLevel(templateSynthesizerLoggerLevel);
            templateSynthesizerLogAppender.stop();
        }
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
    public void firstShotScoringFailureRecordsFailureWithoutMutatingTest() {
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
        assertInitialScoringFailureWasLogged();
    }

    @Test
    public void firstShotScoringFailurePreservesReturnedTemplate() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Properties.P_OBJECT_RULE_APPLICATION = 1.0;
        Properties.CHROMOSOME_LENGTH = 40;

        Branch stateBranch = stateBearingBranch();
        BranchCoverageTestFitness failingFitness = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal failingGoal = mock(BranchCoverageGoal.class);
        when(failingFitness.getBranchGoal()).thenReturn(failingGoal);
        when(failingGoal.getDistance(any(ExecutionResult.class)))
                .thenThrow(new IllegalStateException("forced scoring failure"));

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        TestCase template = null;
        for (int attempt = 0; attempt < 60 && template == null; attempt++) {
            int injectionsBeforeAttempt = session().templateInjections;
            session().setWorkingTarget(0, stateBranch,
                    Collections.singletonList(failingFitness));
            TestChromosome chromosome = factory.getChromosome();
            TestCase candidate = chromosome.getTestCase();
            if (session().templateInjections > injectionsBeforeAttempt
                    && candidate.findTargetMethodCallStatement() != null) {
                template = candidate;
            }
        }

        assertNotNull(template,
                "a scoring failure must not discard a structurally usable first-shot template");
        assertNotNull(template.findTargetMethodCallStatement(),
                "the returned template must still contain the target invocation");
        assertNotNull(TestCaseExecutor.getInstance().execute(template, Integer.MAX_VALUE),
                "the returned template must remain executable after scoring fails");
        assertEquals(1, session().firstShotSuccesses);
        assertEquals(0, session().immediateCoverage);
        assertEquals(0, session().reachedNotCovered);
        assertEquals(1, session().workingGoalUnavailable);
        assertEquals(1, session().workingGoalEvaluationFailures);
        assertEquals(1, session().templateInjections);
        assertEquals(0, session().inconsistentTemplatesDiscarded);
        assertInitialScoringFailureWasLogged();
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

    private void assertInitialScoringFailureWasLogged() {
        ILoggingEvent matchingEvent = null;
        for (ILoggingEvent event : templateSynthesizerLogAppender.list) {
            if (event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains(
                            "Object-rule initial scoring failed")) {
                assertTrue(matchingEvent == null,
                        "initial scoring failure should be logged only once");
                matchingEvent = event;
            }
        }
        assertNotNull(matchingEvent, "the scoring failure should be recorded in the log");
        assertNotNull(matchingEvent.getThrowableProxy(),
                "the failure log should retain the scoring exception");
    }

    private static Branch stateBearingBranch() {
        Map<Branch, Set<DepVariable>> map = org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime().getBranchInterestedVarsMap().get(Properties.TARGET_METHOD);
        assertNotNull(map, "the target method should have an object-rule graph");
        Branch best = null;
        int bestSize = 0;
        for (Map.Entry<Branch, Set<DepVariable>> entry : map.entrySet()) {
            if (entry.getValue() != null && entry.getValue().size() > bestSize) {
                best = entry.getKey();
                bestSize = entry.getValue().size();
            }
        }
        assertNotNull(best, "expected a branch depending on object state");
        return best;
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
