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
import java.util.stream.Collectors;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.wholeclass.WholeClassExample;
import com.examples.with.different.packagename.objectconstruction.wholeclass.FactoryOnlyWholeClassExample;
import com.examples.with.different.packagename.objectconstruction.wholeclass.LocalDerivedArrayWholeClassExample;
import com.examples.with.different.packagename.objectconstruction.wholeclass.UnconstructableWholeClassExample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for EvoObj whole-class mode: the object rule can run without
 * {@code -Dtarget_method}, deriving the entry method per branch from the branch's own
 * normally callable containing method ({@link TargetContext}), analyzing its object graph on demand,
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
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = false;
		Properties.P_OBJECT_RULE_APPLICATION = 0.6;
		org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().clearWorkingTarget();
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

	private static String constructorSignatureOf(Class<?> clazz, int paramNum) {
		for (java.lang.reflect.Constructor<?> c : clazz.getDeclaredConstructors()) {
			if (c.getParameterCount() == paramNum) {
				return "<init>" + MethodUtil.getSignature(c);
			}
		}
		throw new IllegalStateException("no constructor/" + paramNum + " in " + clazz);
	}

	private static Branch firstConstructorBranchOf(Class<?> clazz, int paramNum) {
		String sig = constructorSignatureOf(clazz, paramNum);
		List<Branch> branches = BranchPool.getInstance(
				org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT())
				.retrieveBranchesInMethod(clazz.getName(), sig);
		assertTrue(branches != null && !branches.isEmpty(), "expected a branch in " + sig);
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

		Branch packageBranch = firstBranchOf(WholeClassExample.class, "packageEntry", 1);
		assertNull(TargetContext.forBranch(packageBranch),
				"the core synthesis surface remains public/protected");
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = true;
		TargetContext packageContext = TargetContext.forBranch(packageBranch);
		assertNotNull(packageContext,
				"expanded entries use EvoSuite's package-aware accessibility policy");
		assertEquals(signatureOf(WholeClassExample.class, "packageEntry", 1),
				packageContext.getMethodSignature());

		Branch privateBranch = firstBranchOf(WholeClassExample.class, "hiddenEntry", 1);
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = false;
		assertNull(TargetContext.forBranch(privateBranch),
				"a branch in a private method is not a valid entry (no call-graph search)");
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = true;
		TargetContext privateContext = TargetContext.forBranch(privateBranch);
		assertNotNull(privateContext, "expanded entries allow private branches through a public caller");
		assertEquals(signatureOf(WholeClassExample.class, "publicCallsHidden", 1),
				privateContext.getMethodSignature());

		Branch constructorBranch = firstConstructorBranchOf(WholeClassExample.class, 1);
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = false;
		assertNull(TargetContext.forBranch(constructorBranch),
				"a constructor branch is rejected unless expanded entries are enabled");
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = true;
		TargetContext constructorContext = TargetContext.forBranch(constructorBranch);
		assertNotNull(constructorContext, "expanded entries allow constructor branches");
		assertTrue(constructorContext.isConstructorEntry());
		assertEquals(constructorSignatureOf(WholeClassExample.class, 1),
				constructorContext.getMethodSignature());
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
		selector.reset();
		ObjectConstructionContext.reset();
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = true;
		assertTrue(selector.isEligible(privateBranch),
				"a private-method branch with a public caller and object state is eligible");
		assertTrue(ObjectConstructionContext.branchInterestedVarsMap
						.containsKey(signatureOf(WholeClassExample.class, "publicCallsHidden", 1)),
				"private branch analysis should be cached under the public entry method");
	}

	@Test
	public void eligibilityDiscoveryDoesNotMutateTheLiveTestCluster() {
		analyzeWholeClass(WholeClassExample.class);
		Branch publicBranch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		TestCluster cluster = TestCluster.getInstance();
		List<String> callsBefore = cluster.getTestCalls().stream()
				.map(Object::toString).sorted().collect(Collectors.toList());
		List<String> generatorsBefore = cluster.getGeneratorsByType().entrySet().stream()
				.flatMap(entry -> entry.getValue().stream()
						.map(value -> entry.getKey().toString() + "=" + value))
				.sorted().collect(Collectors.toList());

		ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();
		selector.reset();
		selector.prepareEligibility(java.util.Collections.singleton(publicBranch));

		List<String> callsAfter = cluster.getTestCalls().stream()
				.map(Object::toString).sorted().collect(Collectors.toList());
		List<String> generatorsAfter = cluster.getGeneratorsByType().entrySet().stream()
				.flatMap(entry -> entry.getValue().stream()
						.map(value -> entry.getKey().toString() + "=" + value))
				.sorted().collect(Collectors.toList());
		assertEquals(callsBefore, callsAfter,
				"eligibility analysis must not change ordinary test-call policy");
		assertEquals(generatorsBefore, generatorsAfter,
				"eligibility analysis must not add or replace live generators");
	}

	@Test
	public void localArrayDerivedFromStringIsNotConstructibleObjectState() {
		analyzeWholeClass(LocalDerivedArrayWholeClassExample.class);
		ObjectRuleBranchSelector selector = ObjectRuleBranchSelector.getInstance();
		selector.reset();

		String signature = signatureOf(
				LocalDerivedArrayWholeClassExample.class, "parse", 1);
		List<Branch> branches = BranchPool.getInstance(
				org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT())
				.retrieveBranchesInMethod(
						LocalDerivedArrayWholeClassExample.class.getName(), signature);
		assertTrue(branches != null && !branches.isEmpty());
		for (Branch branch : branches) {
			assertTrue(!selector.isEligible(branch),
					"a local split array cannot be configured before the target call");
		}
		assertEquals(0, selector.getEverEligibleCount());
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
			org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, publicBranch, java.util.Collections.emptyList());
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

	@Test
	public void rejectedObjectRuleApplicationDoesNotConsumeSearchRandomness() {
		analyzeWholeClass(WholeClassExample.class);
		Branch publicBranch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, publicBranch, java.util.Collections.emptyList());
		Properties.P_OBJECT_RULE_APPLICATION = 0.0;

		Randomness.setSeed(47L);
		Properties.APPLY_OBJECT_RULE = false;
		String baseline = new RandomLengthTestFactory()
				.getChromosome().getTestCase().toCode();

		Randomness.setSeed(47L);
		Properties.APPLY_OBJECT_RULE = true;
		String rejectedEvoObj = new RandomLengthTestFactory()
				.getChromosome().getTestCase().toCode();

		assertEquals(baseline, rejectedEvoObj,
				"declining an eligible EvoObj branch must be RNG-transparent");
	}

	@Test
	public void noEligibleBranchIsExactlyBaselineTransparent() {
		analyzeWholeClass(LocalDerivedArrayWholeClassExample.class);
		assertTransparentFallback(new RandomLengthTestFactory(), null, 1.0,
				"a class with no eligible graph");
	}

	@Test
	public void rejectedEntryContextIsExactlyBaselineTransparent() {
		analyzeWholeClass(WholeClassExample.class);
		Branch privateBranch = firstBranchOf(WholeClassExample.class, "hiddenEntry", 1);
		assertNull(TargetContext.forBranch(privateBranch));
		assertTransparentFallback(new RandomLengthTestFactory(), privateBranch, 1.0,
				"a branch without a callable entry");
	}

	@Test
	public void failedEntryConstructionIsExactlyBaselineTransparent() {
		analyzeWholeClass(UnconstructableWholeClassExample.class);
		Branch branch = firstBranchOf(UnconstructableWholeClassExample.class, "entry", 0);
		assertTransparentFallback(new RandomLengthTestFactory(), branch, 1.0,
				"failed receiver construction");
	}

	@Test
	public void synthesisWithNoActionsIsExactlyBaselineTransparent() {
		analyzeWholeClass(WholeClassExample.class);
		Branch branch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		assertTransparentFallback(new StubSynthesizerFactory(StubOutcome.NO_ACTIONS),
				branch, 1.0, "synthesis with no graph-derived actions");
	}

	@Test
	public void synthesisWithUnresolvedRootIsExactlyBaselineTransparent() {
		analyzeWholeClass(WholeClassExample.class);
		Branch branch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		assertTransparentFallback(new StubSynthesizerFactory(StubOutcome.UNRESOLVED_ROOT),
				branch, 1.0, "synthesis with an unresolved required root");
	}

	@Test
	public void inconsistentSynthesisIsExactlyBaselineTransparent() {
		analyzeWholeClass(WholeClassExample.class);
		Branch branch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		assertTransparentFallback(new StubSynthesizerFactory(StubOutcome.INCONSISTENT),
				branch, 1.0, "an inconsistent synthesized test");
	}

	@Test
	public void throwingSynthesisIsExactlyBaselineTransparent() {
		analyzeWholeClass(WholeClassExample.class);
		Branch branch = firstBranchOf(WholeClassExample.class, "publicEntry", 1);
		assertTransparentFallback(new StubSynthesizerFactory(StubOutcome.THROWS),
				branch, 1.0, "a synthesis exception");
	}

	private static void assertTransparentFallback(
			RandomLengthTestFactory factory, Branch branch, double probability, String scenario) {
		Properties.P_OBJECT_RULE_APPLICATION = probability;
		org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, branch, java.util.Collections.emptyList());

		Randomness.setSeed(47L);
		Properties.APPLY_OBJECT_RULE = false;
		List<String> baseline = generatedTestSequence(factory, 3);
		long baselineNextRandom = Randomness.nextLong();

		Randomness.setSeed(47L);
		Properties.APPLY_OBJECT_RULE = true;
		List<String> evoObj = generatedTestSequence(factory, 3);
		long evoObjNextRandom = Randomness.nextLong();

		assertEquals(baseline, evoObj, scenario + " must produce the exact baseline chromosome");
		assertEquals(baselineNextRandom, evoObjNextRandom,
				scenario + " must restore the exact RNG state before fallback");
		if (branch != null) {
			assertTrue(!TemplateCache.getInstance().hasTemplate(branch),
					scenario + " must not publish a rejected template");
		}
	}

	private static List<String> generatedTestSequence(
			RandomLengthTestFactory factory, int count) {
		List<String> tests = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			tests.add(factory.getChromosome().getTestCase().toCode());
		}
		return tests;
	}

	private enum StubOutcome {
		NO_ACTIONS,
		UNRESOLVED_ROOT,
		INCONSISTENT,
		THROWS
	}

	private static final class StubSynthesizerFactory extends RandomLengthTestFactory {
		private final StubOutcome outcome;

		private StubSynthesizerFactory(StubOutcome outcome) {
			this.outcome = outcome;
		}

		@Override
		protected ConstructionPathSynthesizer newConstructionPathSynthesizer() {
			return new ConstructionPathSynthesizer() {
				@Override
				public void buildNodeStatementCorrespondence(
						TestCase test, Branch branch, boolean allowNullValue) {
					if (outcome == StubOutcome.THROWS) {
						throw new AssertionError("synthetic synthesis failure");
					}
					if (outcome == StubOutcome.INCONSISTENT) {
						test.addStatement(new IntPrimitiveStatement(test, 1));
					}
					// NO_ACTIONS and UNRESOLVED_ROOT deliberately emit nothing.
				}

				@Override
				public boolean hasUnresolvedRequiredRoot() {
					return outcome == StubOutcome.UNRESOLVED_ROOT;
				}
			};
		}

		@Override
		protected boolean isSynthesizedTestConsistent(TestCase test) {
			return outcome != StubOutcome.INCONSISTENT
					&& super.isSynthesizedTestConsistent(test);
		}
	}

	@Test
	public void factoryUsesRegisteredStaticFactoryForEntryReceiver() {
		analyzeWholeClass(FactoryOnlyWholeClassExample.class);
		Branch branch = firstBranchOf(FactoryOnlyWholeClassExample.class, "entry", 1);
		Properties.P_OBJECT_RULE_APPLICATION = 1.0;
		String entrySig = signatureOf(FactoryOnlyWholeClassExample.class, "entry", 1);
		String factorySig = signatureOf(FactoryOnlyWholeClassExample.class, "create", 1);

		org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, branch, java.util.Collections.emptyList());
		TestCase test = new RandomLengthTestFactory().getChromosome().getTestCase();

		assertNotNull(test.findMethodCallStatement(factorySig),
				"the registered static factory should construct the private-constructor receiver");
		assertNotNull(test.findMethodCallStatement(entrySig),
				"the selected entry should be invoked on the factory result");
		assertNotNull(TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE));
	}

	@Test
	public void factoryInsertsConstructorEntryWhenExpandedEntriesAreEnabled() {
		analyzeWholeClass(WholeClassExample.class);
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = true;
		Properties.P_OBJECT_RULE_APPLICATION = 1.0;
		Branch constructorBranch = firstConstructorBranchOf(WholeClassExample.class, 1);
		String constructorSig = constructorSignatureOf(WholeClassExample.class, 1);

		RandomLengthTestFactory factory = new RandomLengthTestFactory();
		boolean built = false;
		for (int attempt = 0; attempt < 60 && !built; attempt++) {
			org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, constructorBranch, java.util.Collections.emptyList());
			TestChromosome chromosome = factory.getChromosome();
			TestCase test = chromosome.getTestCase();
			for (int i = 0; i < test.size(); i++) {
				if (test.getStatement(i) instanceof ConstructorStatement) {
					ConstructorStatement cStat = (ConstructorStatement) test.getStatement(i);
					if (cStat.getConstructor().getNameWithDescriptor().equals(constructorSig)) {
						built = true;
						ExecutionResult result = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);
						assertNotNull(result, "the constructor-entry template should execute");
						break;
					}
				}
			}
		}
		assertTrue(built, "the factory should build a template that calls the constructor entry");
	}

	@Test
	public void factoryUsesPublicCallerForPrivateBranchWhenExpandedEntriesAreEnabled() {
		analyzeWholeClass(WholeClassExample.class);
		Properties.OBJECT_RULE_EXPANDED_ENTRIES = true;
		Properties.P_OBJECT_RULE_APPLICATION = 1.0;
		Branch privateBranch = firstBranchOf(WholeClassExample.class, "hiddenEntry", 1);
		String entrySig = signatureOf(WholeClassExample.class, "publicCallsHidden", 1);

		RandomLengthTestFactory factory = new RandomLengthTestFactory();
		boolean built = false;
		for (int attempt = 0; attempt < 60 && !built; attempt++) {
			org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, privateBranch, java.util.Collections.emptyList());
			TestChromosome chromosome = factory.getChromosome();
			TestCase test = chromosome.getTestCase();
			if (test.findMethodCallStatement(entrySig) != null) {
				built = true;
				ExecutionResult result = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);
				assertNotNull(result, "the private-branch template should execute through its public caller");
			}
		}
		assertTrue(built, "the factory should call the public entry that reaches the private branch");
	}
}
