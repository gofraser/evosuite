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
import java.util.List;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.wholeclass.WholeClassExample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for EvoObj whole-class mode: the object rule can run without
 * {@code -Dtarget_method}, deriving the entry method per branch from the branch's own
 * public/protected containing method ({@link TargetContext}), analyzing its object graph on demand,
 * and synthesizing a template that inserts the entry call and builds its state. The end-to-end
 * DynaMOSA coverage comparison is a master-module {@code SystemTestBase} test; here the pieces are
 * unit-tested against the analysis/synthesis APIs.
 */
public class WholeClassModeTest {

	@AfterEach
	public void tearDown() {
		Properties.TARGET_METHOD = "";
		Properties.TARGET_CLASS = "";
		Properties.APPLY_OBJECT_RULE = false;
		Properties.OBJECT_RULE_WHOLE_CLASS = false;
		RandomLengthTestFactory.workingBranch4ObjectGraph = null;
		TargetContext.clearActive();
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

	/** Analyze the whole class (no target method), as in a real {@code object_rule_whole_class} run. */
	private static void analyzeWholeClass(Class<?> clazz) {
		Properties.CLIENT_ON_THREAD = true;
		Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
		Properties.ASSERTIONS = false;
		Properties.RANDOM_SEED = 42L;
		Randomness.setSeed(42L);
		Properties.TARGET_CLASS = clazz.getName();
		Properties.TARGET_METHOD = "";
		Properties.APPLY_OBJECT_RULE = true;
		Properties.OBJECT_RULE_WHOLE_CLASS = true;

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

	private static Branch firstBranchOf(Class<?> clazz, String methodName, int paramNum) {
		String sig = signatureOf(clazz, methodName, paramNum);
		List<Branch> branches = BranchPool.getInstance(
				org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT())
				.retrieveBranchesInMethod(clazz.getName(), sig);
		assertTrue(branches != null && !branches.isEmpty(), "expected a branch in " + methodName);
		return branches.get(0);
	}

	@Test
	public void targetContextResolvesOnlyAccessibleEntryMethods() {
		analyzeWholeClass(WholeClassExample.class);

		Branch publicBranch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		TargetContext ctx = TargetContext.forBranch(publicBranch);
		assertNotNull(ctx, "a branch in a public method is a valid entry");
		assertEquals(WholeClassExample.class.getName(), ctx.getClassName());
		assertEquals(signatureOf(WholeClassExample.class, "publicEntry", 1), ctx.getMethodSignature());

		Branch privateBranch = firstBranchOf(WholeClassExample.class, "hiddenEntry", 1);
		assertNull(TargetContext.forBranch(privateBranch),
				"a branch in a private method is not a valid entry (no call-graph search)");
	}

	@Test
	public void activeContextDrivesTargetMethodResolution() {
		analyzeWholeClass(WholeClassExample.class);
		Branch publicBranch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		String sig = signatureOf(WholeClassExample.class, "publicEntry", 1);

		// No property, no active context -> empty.
		assertEquals("", TargetContext.activeMethodSignature());

		// Active context supplies the method when TARGET_METHOD is unset.
		TargetContext.setActive(TargetContext.forBranch(publicBranch));
		assertEquals(sig, TargetContext.activeMethodSignature());
		assertEquals(WholeClassExample.class.getName(), TargetContext.activeClassName());
		TargetContext.clearActive();

		// An explicit TARGET_METHOD always wins (method-level parity).
		Properties.TARGET_METHOD = "someOther()V";
		assertEquals("someOther()V", TargetContext.activeMethodSignature());
	}

	@Test
	public void eligibilityLazilyAnalysesTheEntryMethod() {
		analyzeWholeClass(WholeClassExample.class);
		Branch publicBranch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		Branch privateBranch = firstBranchOf(WholeClassExample.class, "hiddenEntry", 1);

		// Nothing analyzed eagerly in whole-class mode.
		assertTrue(ObjectConstructionContext.branchInterestedVarsMap.isEmpty(),
				"whole-class mode does not eagerly analyze any method");

		ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();
		selector.reset();
		assertTrue(selector.isEligible(publicBranch),
				"a public-method branch with object state is eligible (analyzed on demand)");
		assertTrue(ObjectConstructionContext.branchInterestedVarsMap
						.containsKey(signatureOf(WholeClassExample.class, "publicEntry", 1)),
				"eligibility should have analyzed the entry method on demand");
		assertTrue(!selector.isEligible(privateBranch), "a private-method branch is ineligible");
	}

	@Test
	public void factorySynthesizesWholeClassTemplate() {
		analyzeWholeClass(WholeClassExample.class);
		Branch publicBranch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		Properties.P_OBJECT_RULE_APPLICATION = 1.0;
		String entrySig = signatureOf(WholeClassExample.class, "publicEntry", 1);

		RandomLengthTestFactory factory = new RandomLengthTestFactory();
		boolean built = false;
		for (int attempt = 0; attempt < 60 && !built; attempt++) {
			RandomLengthTestFactory.workingBranch4ObjectGraph = publicBranch;
			TestChromosome chromosome = factory.getChromosome();
			TestCase test = chromosome.getTestCase();
			if (test.findMethodCallStatement(entrySig) != null) {
				built = true;
				ExecutionResult result = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);
				assertNotNull(result, "the whole-class template should execute");
			}
		}
		assertTrue(built, "the factory should build a template that calls the derived entry method");
	}
}
