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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for the object-rule branch selector (plan E3): a branch is only eligible for
 * template synthesis when it has a non-empty object-construction graph, and a branch that fails
 * synthesis {@value ObjectRuleBranchSelector#MAX_FAILED_ATTEMPTS} times is negatively cached for
 * the rest of the run.
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
    public void repeatedFailuresNegativelyCacheBranch() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        for (int i = 0; i < ObjectRuleBranchSelector.MAX_FAILED_ATTEMPTS; i++) {
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
    public void reachingTargetResetsTheFailureCounter() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Branch branch = stateBearingBranch();
        ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();

        // Two failures, then a success clears the streak.
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, false);
        selector.recordSynthesisAttempt(branch, true);
        assertTrue(selector.isEligible(branch), "a success resets the failure count");

        // It now takes a fresh full streak of failures to drop the branch.
        for (int i = 0; i < ObjectRuleBranchSelector.MAX_FAILED_ATTEMPTS - 1; i++) {
            selector.recordSynthesisAttempt(branch, false);
        }
        assertTrue(selector.isEligible(branch), "counter restarted after the success");
        selector.recordSynthesisAttempt(branch, false);
        assertFalse(selector.isEligible(branch), "dropped once the fresh streak reaches the threshold");
    }
}
