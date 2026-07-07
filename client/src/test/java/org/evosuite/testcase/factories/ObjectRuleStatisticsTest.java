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
package org.evosuite.testcase.factories;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.ObjectRuleTelemetry;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for the EvoObj budget/statistics enhancement. The graph-size and
 * template counters are unit-tested here; the two system-level guarantees of E8 — that the
 * {@link org.evosuite.statistics.RuntimeVariable}s appear in the statistics CSV and that a run
 * with a tight {@code search_budget} terminates on time even with legitimization enabled — are
 * covered by a master-module {@code SystemTestBase} test (the {@code SearchStatistics} backend and
 * a full search are not on the client-only classpath).
 */
public class ObjectRuleStatisticsTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        Properties.OBJECT_RULE_SLOT_TUNING = false;
        Properties.OBJECT_RULE_SLOT_TUNING_ATTEMPTS = 3;
        Properties.OBJECT_RULE_VARIANT_INJECTIONS = false;
        RandomLengthTestFactory.workingBranch4ObjectGraph = null;
        org.evosuite.TestGenerationContext.getInstance().resetContext();
    }

    private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m.getName() + MethodUtil.getSignature(m);
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
    }

    private static void analyze(Class<?> clazz, String methodName, int paramNum) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, paramNum);
        Properties.APPLY_OBJECT_RULE = true;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed for " + clazz, e);
        }

        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);
        TestCaseExecutor.initExecutor();
    }

    /** {@code resetStatistics} zeroes every object-rule counter (determinism). */
    @Test
    public void resetStatisticsClearsCounters() {
        RandomLengthTestFactory.legitimizationTrials = 7;
        RandomLengthTestFactory.legitimizationSuccess = 3;
        RandomLengthTestFactory.templateInjections = 11;
        RandomLengthTestFactory.templateSynthesisTimeMs = 1234;
        RandomLengthTestFactory.currentIteration = 9;
        RandomLengthTestFactory.lastFreshSynthesisIteration = 8;
        RandomLengthTestFactory.templateCacheHits = 5;
        RandomLengthTestFactory.freshSyntheses = 4;
        RandomLengthTestFactory.firstShotSuccesses = 2;
        RandomLengthTestFactory.immediateCoverage = 1;
        RandomLengthTestFactory.reachedNotCovered = 1;
        RandomLengthTestFactory.tuningExecutions = 3;
        RandomLengthTestFactory.tuningWins = 2;
        RandomLengthTestFactory.tuningCoverage = 1;
        RandomLengthTestFactory.variantInjections = 4;

        RandomLengthTestFactory.resetStatistics();

        assertEquals(0, RandomLengthTestFactory.legitimizationTrials);
        assertEquals(0, RandomLengthTestFactory.legitimizationSuccess);
        assertEquals(0, RandomLengthTestFactory.templateInjections);
        assertEquals(0L, RandomLengthTestFactory.templateSynthesisTimeMs);
        assertEquals(0, RandomLengthTestFactory.currentIteration);
        assertEquals(0, RandomLengthTestFactory.lastFreshSynthesisIteration);
        assertEquals(0, RandomLengthTestFactory.templateCacheHits);
        assertEquals(0, RandomLengthTestFactory.freshSyntheses);
        assertEquals(0, RandomLengthTestFactory.firstShotSuccesses);
        assertEquals(0, RandomLengthTestFactory.immediateCoverage);
        assertEquals(0, RandomLengthTestFactory.reachedNotCovered);
        assertEquals(0, RandomLengthTestFactory.tuningExecutions);
        assertEquals(0, RandomLengthTestFactory.tuningWins);
        assertEquals(0, RandomLengthTestFactory.tuningCoverage);
        assertEquals(0, RandomLengthTestFactory.variantInjections);
        assertTrue(RandomLengthTestFactory.workingGoals4ObjectGraph.isEmpty());
    }

    @Test
    public void slotTuningMutatesOnlyObjectRulePrimitiveSlots() {
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement slot = new IntPrimitiveStatement(test, 1);
        slot.setObjectRuleSlot(true);
        test.addStatement(slot);
        IntPrimitiveStatement ordinary = new IntPrimitiveStatement(test, 2);
        test.addStatement(ordinary);

        boolean mutated = false;
        for (int seed = 1; seed <= 20 && !mutated; seed++) {
            slot.setValue(1);
            ordinary.setValue(2);
            Randomness.setSeed(seed);
            mutated = RandomLengthTestFactory.mutateObjectRuleSlots(test);
            assertEquals(2, ordinary.getValue().intValue());
        }

        assertTrue(mutated, "seeded attempts should mutate at least one object-rule slot");
        assertTrue(slot.isObjectRuleSlot());
    }

    @Test
    public void variantInjectionUsesSameGenerationTemplateClone() {
        Properties.OBJECT_RULE_VARIANT_INJECTIONS = true;

        DefaultTestCase source = new DefaultTestCase();
        IntPrimitiveStatement slot = new IntPrimitiveStatement(source, 1);
        slot.setObjectRuleSlot(true);
        source.addStatement(slot);
        IntPrimitiveStatement ordinary = new IntPrimitiveStatement(source, 2);
        source.addStatement(ordinary);

        TestCase variant = null;
        for (int seed = 1; seed <= 20 && RandomLengthTestFactory.variantInjections == 0; seed++) {
            RandomLengthTestFactory.resetStatistics();
            RandomLengthTestFactory.setCurrentIteration(7);
            RandomLengthTestFactory.rememberLastInjectedTemplate(source);
            Randomness.setSeed(seed);
            variant = RandomLengthTestFactory.produceVariantTestCase();
        }

        assertEquals(1, RandomLengthTestFactory.variantInjections);
        assertEquals(1, RandomLengthTestFactory.templateInjections);
        assertEquals(1, slot.getValue().intValue(), "source slot must not be mutated");
        assertEquals(2, ordinary.getValue().intValue(), "source non-slot must not be mutated");
        assertTrue(variant != source);
        assertTrue(((IntPrimitiveStatement) variant.getStatement(0)).isObjectRuleSlot());
        assertEquals(2, ((IntPrimitiveStatement) variant.getStatement(1)).getValue().intValue());
    }

    @Test
    public void variantSourceIsFlaggedAndGenerationScoped() {
        DefaultTestCase source = new DefaultTestCase();
        IntPrimitiveStatement slot = new IntPrimitiveStatement(source, 1);
        slot.setObjectRuleSlot(true);
        source.addStatement(slot);

        RandomLengthTestFactory.setCurrentIteration(3);
        RandomLengthTestFactory.rememberLastInjectedTemplate(source);

        Properties.OBJECT_RULE_VARIANT_INJECTIONS = false;
        assertTrue(!RandomLengthTestFactory.shouldProduceVariant());

        Properties.OBJECT_RULE_VARIANT_INJECTIONS = true;
        RandomLengthTestFactory.setCurrentIteration(4);
        assertTrue(!RandomLengthTestFactory.shouldProduceVariant());
    }

    @Test
    public void objectRuleTelemetryTracksSurvivalAndReset() {
        ObjectRuleTelemetry telemetry = ObjectRuleTelemetry.getInstance();
        telemetry.recordInjection(1, 3);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setObjectRuleTemplateId(1);
        telemetry.recordPopulation(java.util.Collections.singletonList(chromosome), 8);

        assertEquals(5.0, telemetry.meanSurvival(), 0.0);
        assertEquals(1.0, telemetry.survivalAtLeastFive(), 0.0);

        ObjectConstructionContext.reset();

        assertEquals(0.0, telemetry.meanSurvival(), 0.0);
        assertEquals(0.0, telemetry.survivalAtLeastFive(), 0.0);
    }

    @Test
    public void immediateCoverageTemplatesAreExcludedFromSurvival5Denominator() {
        ObjectRuleTelemetry telemetry = ObjectRuleTelemetry.getInstance();
        telemetry.recordInjection(1, 0);
        telemetry.recordImmediateCoverage(1);
        telemetry.recordInjection(2, 0);

        TestChromosome immediate = new TestChromosome();
        immediate.setObjectRuleTemplateId(1);
        TestChromosome missed = new TestChromosome();
        missed.setObjectRuleTemplateId(2);
        telemetry.recordPopulation(java.util.Arrays.asList(immediate, missed), 4);

        assertEquals(4.0, telemetry.meanSurvival(), 0.0);
        assertEquals(0.0, telemetry.survivalAtLeastFive(), 0.0);

        telemetry.recordPopulation(java.util.Collections.singletonList(missed), 5);

        assertEquals(1.0, telemetry.survivalAtLeastFive(), 0.0);
    }

    @Test
    public void objectRuleCountersResetThroughConstructionContext() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();
        org.evosuite.coverage.branch.Branch branch = null;
        for (java.util.Map.Entry<org.evosuite.coverage.branch.Branch,
                java.util.Set<org.evosuite.graphs.interprocedural.var.DepVariable>> entry
                : ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD).entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                branch = entry.getKey();
                break;
            }
        }
        assertTrue(branch != null);
        selector.isEligible(branch);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);

        TargetContext.forBranch(null);
        assertTrue(selector.getEverEligibleCount() > 0);
        assertTrue(selector.getBlacklistedCount() > 0);
        assertTrue(TargetContext.getRejectedOtherEntries() > 0);

        ObjectConstructionContext.reset();

        assertEquals(0, selector.getEverEligibleCount());
        assertEquals(0, selector.getBlacklistedCount());
        assertEquals(0, TargetContext.getRejectedOtherEntries());
    }

    /** Graph-size statistics reflect the object-construction analysis and tracking never throws. */
    @Test
    public void objectGraphStatsReflectAnalysis() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);

        int branches = RandomLengthTestFactory.countObjectGraphBranches();
        int maxSize = RandomLengthTestFactory.maxObjectGraphSize();
        assertTrue(branches > 0,
                "at least one branch of the analyzed method should have a non-empty object graph");
        assertTrue(maxSize >= 1, "the largest object graph should have at least one variable");

        // Emitting statistics is safe on the client-only classpath (fallback is a no-op there).
        RandomLengthTestFactory.trackObjectRuleStatistics();
    }

    /** With no analysis performed, the graph statistics are zero and tracking is a safe no-op. */
    @Test
    public void statsSafeWhenContextEmpty() {
        org.evosuite.TestGenerationContext.getInstance().resetContext();
        assertEquals(0, RandomLengthTestFactory.countObjectGraphBranches());
        assertEquals(0, RandomLengthTestFactory.maxObjectGraphSize());
        RandomLengthTestFactory.trackObjectRuleStatistics();
    }
}
