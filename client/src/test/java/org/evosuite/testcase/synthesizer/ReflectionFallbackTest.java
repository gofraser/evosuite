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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.reflection.NoSetterFieldExample;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for the EvoObj reflection fallback. {@link NoSetterFieldExample} has a
 * private leaf field with no reachable setter, so with {@code object_rule_reflection_fallback} on
 * the synthesizer must set it via a {@link PrivateFieldStatement}; with the flag off (parity) the
 * template leaves the field untouched.
 */
public class ReflectionFallbackTest {

	@AfterEach
	public void tearDown() {
		Properties.TARGET_METHOD = "";
		Properties.TARGET_CLASS = "";
		Properties.APPLY_OBJECT_RULE = false;
		Properties.OBJECT_RULE_REFLECTION_FALLBACK = false;
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
		assertNotNull(best, "expected a branch depending on the private field");
		return best;
	}

	private static TestCase initializeTestWithTargetCall(Class<?> clazz) throws Exception {
		DefaultTestCase test = new DefaultTestCase();
		TestFactory factory = TestFactory.getInstance();
		Method m = clazz.getDeclaredMethod("targetM", int.class);

		Constructor<?> ctor = clazz.getDeclaredConstructor();
		VariableReference callee =
				factory.addConstructor(test, new GenericConstructor(ctor, clazz), test.size(), 0);
		VariableReference arg = test.addStatement(new IntPrimitiveStatement(test), test.size());

		List<VariableReference> params = new ArrayList<>();
		params.add(arg);
		MethodStatement call = new MethodStatement(test, new GenericMethod(m, clazz), callee, params);
		test.addStatement(call, test.size());

		assertNotNull(test.findTargetMethodCallStatement(), "initial test must contain the target call");
		return test;
	}

	private static boolean hasPrivateFieldStatement(TestCase test) {
		for (int i = 0; i < test.size(); i++) {
			if (test.getStatement(i) instanceof PrivateFieldStatement) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void setsPrivateFieldViaReflectionWhenEnabled() throws Exception {
		Properties.OBJECT_RULE_REFLECTION_FALLBACK = true;
		analyze(NoSetterFieldExample.class, "targetM", 1);
		Branch b = stateBearingBranch();

		TestCase test = initializeTestWithTargetCall(NoSetterFieldExample.class);
		ConstructionPathSynthesizer synth = new ConstructionPathSynthesizer(false);
		synth.buildNodeStatementCorrespondence(test, b, false);

		assertTrue(hasPrivateFieldStatement(test),
				"the reflection fallback should set the setter-less private field");
		ExecutionResult result = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);
		assertNotNull(result, "the template with the reflection statement should execute");
	}

	@Test
	public void leavesFieldUntouchedWhenDisabled() throws Exception {
		Properties.OBJECT_RULE_REFLECTION_FALLBACK = false;
		analyze(NoSetterFieldExample.class, "targetM", 1);
		Branch b = stateBearingBranch();

		TestCase test = initializeTestWithTargetCall(NoSetterFieldExample.class);
		ConstructionPathSynthesizer synth = new ConstructionPathSynthesizer(false);
		synth.buildNodeStatementCorrespondence(test, b, false);

		assertFalse(hasPrivateFieldStatement(test),
				"with the flag off, no reflection statement should be generated (parity)");
	}
}
