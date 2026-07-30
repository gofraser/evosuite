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
package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;
import com.examples.with.different.packagename.objectconstruction.tuning.ConstructorOnlyNestedState;
import com.examples.with.different.packagename.objectconstruction.tuning.ObserverOnlyInput;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for the object-rule branch selector: a branch is only eligible for
 * template synthesis when it has a non-empty object-construction graph, and a branch that fails
 * synthesis {@code object_rule_max_failed_attempts} times is negatively cached for the rest of the
 * run.
 */
public class ObjectRuleBranchSelectorTest {

    @BeforeEach
    public void setUp() {
        ObjectRuleBranchSelector.getInstance().reset();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS = 3;
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

    /** Pick the branch with the largest OCG leaf set (the most object state to construct). */
    private static Branch stateBearingBranch() {
        Map<Branch, Set<DepVariable>> map =
                ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
        assertNotNull(map, "the target method should have an OCG entry");
        Branch best = null;
        int bestSize = 0;
        for (Map.Entry<Branch, Set<DepVariable>> e : map.entrySet()) {
            if (e.getValue() != null && e.getValue().size() > bestSize) {
                best = e.getKey();
                bestSize = e.getValue().size();
            }
        }
        assertNotNull(best, "expected at least one branch depending on object state");
        return best;
    }

    @Test
    public void stateBearingBranchIsEligibleAndSelectable() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        assertTrue(selector.isEligible(branch), "a branch with a non-empty object graph is eligible");
        // Nulls in the candidate collection are simply skipped.
        assertSame(branch, selector.selectWorkingBranch(Arrays.asList(null, branch)),
                "the selector should pick the eligible branch");
    }

    @Test
    public void constructorOnlyNestedStateIsNotEligible() {
        analyze(ConstructorOnlyNestedState.class, "target", 0);
        Branch branch = stateBearingBranch();

        assertFalse(ObjectRuleBranchSelector.getInstance().isEligible(branch),
                "runtime synthesis cannot replace an already-constructed nested receiver");
    }

    @Test
    public void observerOnlyInputIsNotEligible() {
        boolean oldMockSetup = Properties.OBJECT_RULE_MOCK_SETUP;
        try {
            Properties.OBJECT_RULE_MOCK_SETUP = false;
            analyze(ObserverOnlyInput.class, "target", 1);
            Branch branch = stateBearingBranch();

            assertFalse(ObjectRuleBranchSelector.getInstance().isEligible(branch),
                    "a query without a setter, builder, collection recipe, or mock recipe is inert");
        } finally {
            Properties.OBJECT_RULE_MOCK_SETUP = oldMockSetup;
        }
    }

    @Test
    public void nullAndEmptyCandidatesYieldNoBranch() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        assertFalse(selector.isEligible(null), "null is never eligible");
        assertNull(selector.selectWorkingBranch(null));
        assertNull(selector.selectWorkingBranch(new ArrayList<>()));
        // A single null candidate is not eligible, so nothing is selected.
        assertNull(selector.selectWorkingBranch(Arrays.asList((Branch) null)));
    }

    @Test
    public void unsuccessfulEligibilityDiscoveryDoesNotPerturbSearchRandomness() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();
        selector.reset();

        // Make this otherwise valid branch unknown and graph-less. Supplying it twice ensures the
        // discovery list has a size for which a shuffle would consume randomness.
        ObjectConstructionContext.branchInterestedVarsMap.clear();
        Randomness.setSeed(12345L);
        double expectedNext = Randomness.nextDouble();
        Randomness.setSeed(12345L);

        assertTrue(selector.hasPotentialCandidate(Arrays.asList(branch, branch)),
                "an unknown branch must be considered before lazy discovery");
        assertNull(selector.selectWorkingBranch(Arrays.asList(branch, branch)));
        assertFalse(selector.hasPotentialCandidate(Arrays.asList(branch, branch)),
                "a completely classified graph-less set enables the inert fast path");
        assertEquals(expectedNext, Randomness.nextDouble(), 0.0,
                "no-op eligibility discovery must not shift the GA's random stream");
    }

    @Test
    public void preparationClassifiesGraphlessBranchesBeforeSelection() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();
        selector.reset();

        ObjectConstructionContext.branchInterestedVarsMap.clear();
        assertTrue(selector.hasPotentialCandidate(Arrays.asList(branch)),
                "the branch starts unclassified");

        selector.prepareEligibility(Arrays.asList(branch));

        assertFalse(selector.hasPotentialCandidate(Arrays.asList(branch)),
                "preparation must fully classify a graphless search space");
        assertNull(selector.selectWorkingBranch(Arrays.asList(branch)),
                "selection after preparation must use the cached negative verdict");
    }

    @Test
    public void repeatedDiscardsNegativelyCacheBranch() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        for (int i = 0; i < Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS; i++) {
            assertTrue(selector.isEligible(branch), "still eligible before the failure threshold");
            selector.recordSynthesisAttempt(branch, false);
        }

        assertFalse(selector.isEligible(branch), "the branch is dropped after too many failures");
        assertNull(selector.selectWorkingBranch(Arrays.asList(branch)),
                "a negatively cached branch is never selected");

        selector.reset();
        assertTrue(selector.isEligible(branch), "reset re-enables the branch");
    }

    @Test
    public void entryConstructionFailureImmediatelyRejectsBranch() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        assertTrue(selector.isEligible(branch));
        selector.recordEntryConstructionFailure(branch);

        assertFalse(selector.isEligible(branch),
                "an entry that cannot be constructed must not be retried");
        assertNull(selector.selectWorkingBranch(Arrays.asList(branch)));
        assertEquals(1, selector.getBlacklistedCount());
        assertEquals(0, selector.getTargetedBranchCount(),
                "entry rejection happens before synthesis starts");

        selector.reset();
        assertTrue(selector.isEligible(branch), "per-run reset restores eligibility");
    }

    @Test
    public void retryableEntryFailuresUseBoundWithoutCountingAsSynthesis() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        for (int i = 0; i < Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS - 1; i++) {
            selector.recordRetryableEntryConstructionFailure(branch);
            assertTrue(selector.isEligible(branch));
        }
        assertEquals(0, selector.getTargetedBranchCount());
        selector.recordRetryableEntryConstructionFailure(branch);
        assertFalse(selector.isEligible(branch));
        assertEquals(1, selector.getBlacklistedCount());
        assertEquals(0, selector.getTargetedBranchCount());
    }

    @Test
    public void reachingTemplateResetsTheFailureCounter() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        // Two pre-target failures, then a target-reaching template clears the streak.
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, true);
        assertTrue(selector.isEligible(branch), "a reaching template resets the failure count");

        // It now takes a fresh full streak of discarded syntheses to drop the branch.
        for (int i = 0; i < Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS - 1; i++) {
            selector.recordSynthesisAttempt(branch, false);
        }
        assertTrue(selector.isEligible(branch), "counter restarted after the usable template");
        selector.recordSynthesisAttempt(branch, false);
        assertFalse(selector.isEligible(branch), "dropped once the fresh streak reaches the threshold");
    }

    @Test
    public void reachingWithoutWorkingGoalBenefitDoesNotResetFailureCounter() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        for (int i = 0; i < Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS; i++) {
            selector.recordSynthesisAttempt(branch, true, false);
        }

        assertFalse(selector.isEligible(branch),
                "reaching an entry without improving its selected branch is bounded");
        assertEquals(Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS,
                selector.getMaxInjectionsPerBranch());
    }

    @Test
    public void repeatedWorkingGoalImprovementWithoutCoverageIsBounded() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        selector.recordSynthesisAttempt(branch, true, false);
        selector.recordSynthesisAttempt(branch, true, false);
        selector.recordSynthesisAttempt(branch, true, false);
        assertFalse(selector.isEligible(branch),
                "fitness improvement alone must not permit unbounded repeated synthesis");
    }

    @Test
    public void failureThresholdPropertyIsHonored() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS = 2;
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        selector.recordSynthesisAttempt(branch, false);
        assertTrue(selector.isEligible(branch), "one discard is below the configured threshold");
        selector.recordSynthesisAttempt(branch, false);
        assertFalse(selector.isEligible(branch), "second discard reaches the configured threshold");
    }

    @Test
    public void recordsConcentrationAndRepeatedFailureGateTelemetry() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, true);
        selector.recordSynthesisAttempt(branch, false);

        assertEquals(1, selector.getTargetedBranchCount());
        assertEquals(4, selector.getMaxInjectionsPerBranch());
        assertEquals(1, selector.getRepeatedFailureBranchCount());
        assertEquals(2, selector.getMaxConsecutiveFailures(),
                "a successful synthesis resets the current streak, not its observed maximum");

        selector.reset();
        assertEquals(0, selector.getTargetedBranchCount());
        assertEquals(0, selector.getMaxInjectionsPerBranch());
        assertEquals(0, selector.getRepeatedFailureBranchCount());
        assertEquals(0, selector.getMaxConsecutiveFailures());
    }
}
