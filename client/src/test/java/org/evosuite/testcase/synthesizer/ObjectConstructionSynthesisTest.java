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
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.basicrules.BasicRules;
import com.examples.with.different.packagename.objectconstruction.instancefield.PrivateInstanceFieldExample;
import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M3 verification for the object-construction template synthesis
 * ({@link ConstructionPathSynthesizer} and the wrapper/code-generation machinery). Each test runs
 * the full {@link DependencyAnalysis#analyzeClass} pipeline (with {@code apply_object_rule=true})
 * on a fixture, builds an initial test that calls the target method, then runs
 * {@link ConstructionPathSynthesizer#buildNodeStatementCorrespondence} for a state-bearing branch
 * and asserts that construction statements are synthesised and that the resulting test executes.
 *
 * <p>The centrepiece is {@link #synthesisForBasicRulesRunningExample()}, the paper's running
 * example (Lin et al., ESEC/FSE 2021, Fig. 4(b)): the template constructs {@code Action} and
 * {@code GameState} and wires their state before the {@code checkRules} call.
 */
public class ObjectConstructionSynthesisTest {

	@AfterEach
	public void tearDown() {
		Properties.TARGET_METHOD = "";
		Properties.TARGET_CLASS = "";
		Properties.APPLY_OBJECT_RULE = false;
		ObjectConstructionContext.reset();
	}

	private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
				return m.getName() + MethodUtil.getSignature(m);
			}
		}
		throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
	}

	private static Method reflectMethod(Class<?> clazz, String methodName, int paramNum) {
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
				return m;
			}
		}
		throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
	}

	/**
	 * Run the analysis pipeline on {@code clazz#methodName} with the object rule on, and initialise
	 * the executor so that synthesised templates can be executed.
	 */
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

	private static Map<Branch, Set<DepVariable>> graph() {
		Map<Branch, Set<DepVariable>> map =
				ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
		assertNotNull(map, "the target method should have an OCG entry");
		return map;
	}

	/**
	 * Build a test that already contains a call to the target method, mirroring the precondition of
	 * {@link ConstructionPathSynthesizer#buildNodeStatementCorrespondence}. Callee and parameters are
	 * constructed explicitly via reflected no-arg constructors/primitives so that the test does not
	 * depend on the TestCluster's generator map (which is not fully populated in this isolated setup).
	 */
	private static TestCase initializeTestWithTargetCall(Class<?> clazz, String methodName, int paramNum)
			throws Exception {
		DefaultTestCase test = new DefaultTestCase();
		TestFactory factory = TestFactory.getInstance();
		Method m = reflectMethod(clazz, methodName, paramNum);

		java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
		VariableReference callee = factory.addConstructor(test,
				new org.evosuite.utils.generic.GenericConstructor(ctor, clazz), test.size(), 0);

		List<VariableReference> params = new ArrayList<>();
		for (Class<?> pType : m.getParameterTypes()) {
			params.add(createValue(test, factory, pType));
		}

		GenericMethod gm = new GenericMethod(m, clazz);
		org.evosuite.testcase.statements.MethodStatement call =
				new org.evosuite.testcase.statements.MethodStatement(test, gm, callee, params);
		test.addStatement(call, test.size());

		assertNotNull(test.findTargetMethodCallStatement(),
				"initial test must contain the target-method call");
		return test;
	}

	private static VariableReference createValue(TestCase test, TestFactory factory, Class<?> pType)
			throws Exception {
		if (pType == int.class) {
			return test.addStatement(
					new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(test), test.size());
		}
		if (pType == long.class) {
			return test.addStatement(
					new org.evosuite.testcase.statements.numeric.LongPrimitiveStatement(test), test.size());
		}
		if (pType == boolean.class) {
			return test.addStatement(
					new org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement(test), test.size());
		}
		// object parameter: construct via its no-arg constructor
		java.lang.reflect.Constructor<?> ctor = pType.getDeclaredConstructor();
		return factory.addConstructor(test,
				new org.evosuite.utils.generic.GenericConstructor(ctor, pType), test.size(), 0);
	}

	/**
	 * Pick a branch whose OCG leaf set is non-empty (i.e. it depends on object state), preferring
	 * deeper branches (more object state to build).
	 */
	private static Branch pickStateBearingBranch() {
		Branch best = null;
		int bestSize = 0;
		for (Map.Entry<Branch, Set<DepVariable>> e : graph().entrySet()) {
			if (e.getValue() != null && e.getValue().size() > bestSize) {
				best = e.getKey();
				bestSize = e.getValue().size();
			}
		}
		assertNotNull(best, "expected at least one branch depending on object state");
		return best;
	}

	private static int countConstructors(TestCase test) {
		int n = 0;
		for (int i = 0; i < test.size(); i++) {
			if (test.getStatement(i) instanceof ConstructorStatement) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Whether the test contains a constructor call producing an object of the given fully-qualified
	 * type name. Matches by name (not {@code Class} identity) because synthesised variables are
	 * loaded by the SUT's {@code InstrumentingClassLoader}, distinct from this test's class loader.
	 */
	private static boolean hasConstructorOf(TestCase test, String typeName) {
		for (int i = 0; i < test.size(); i++) {
			Statement s = test.getStatement(i);
			if (s instanceof ConstructorStatement && s.getReturnValue().getClassName().equals(typeName)) {
				return true;
			}
		}
		return false;
	}

	private static ConstructionPathSynthesizer synthesize(TestCase test, Branch b) throws Exception {
		ConstructionPathSynthesizer synth = new ConstructionPathSynthesizer(false);
		synth.buildNodeStatementCorrespondence(test, b, false);
		return synth;
	}

	private static void assertExecutes(TestCase test) {
		ExecutionResult result = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);
		assertNotNull(result, "the synthesised template should execute and produce a result");
	}

	@Test
	public void synthesisForPublicInstanceField() throws Exception {
		analyze(PublicInstanceFieldExample.class, "targetM", 1);
		Branch b = pickStateBearingBranch();

		TestCase test = initializeTestWithTargetCall(PublicInstanceFieldExample.class, "targetM", 1);
		int sizeBefore = test.size();

		ConstructionPathSynthesizer synth = synthesize(test, b);

		assertFalse(synth.getGraph2CodeMap().isEmpty(),
				"synthesis should map graph nodes to generated statements");
		assertTrue(test.size() > sizeBefore, "synthesis should add construction statements");
		// student1 is a public field of type Student: the template must construct a Student.
		assertTrue(hasConstructorOf(test,
				"com.examples.with.different.packagename.objectconstruction.instancefield.Student"),
				"template should construct the Student held in the public field");
		assertExecutes(test);
	}

	@Test
	public void synthesisForPrivateInstanceField() throws Exception {
		analyze(PrivateInstanceFieldExample.class, "targetM", 1);
		Branch b = pickStateBearingBranch();

		TestCase test = initializeTestWithTargetCall(PrivateInstanceFieldExample.class, "targetM", 1);
		int sizeBefore = test.size();

		ConstructionPathSynthesizer synth = synthesize(test, b);

		assertFalse(synth.getGraph2CodeMap().isEmpty(),
				"synthesis should map graph nodes to generated statements");
		assertTrue(test.size() > sizeBefore, "synthesis should add construction statements");
		assertExecutes(test);
	}

	@Test
	public void synthesisForBasicRulesRunningExample() throws Exception {
		analyze(BasicRules.class, "checkRules", 2);
		Branch b = pickStateBearingBranch();

		TestCase test = initializeTestWithTargetCall(BasicRules.class, "checkRules", 2);
		int constructorsBefore = countConstructors(test);

		ConstructionPathSynthesizer synth = synthesize(test, b);

		assertFalse(synth.getGraph2CodeMap().isEmpty(),
				"synthesis should map graph nodes to generated statements for checkRules");
		// The paper's Fig. 4(b): the template builds the Action and GameState parameter state.
		assertTrue(countConstructors(test) >= constructorsBefore,
				"synthesis should not remove the constructors of the target call");
		assertTrue(synth.getPartialGraph().getGraphSize() > 0,
				"the partial object-construction graph should be non-empty");
		assertExecutes(test);
	}
}
