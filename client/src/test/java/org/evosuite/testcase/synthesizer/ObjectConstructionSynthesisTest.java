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

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.FieldInsnNode;

import com.examples.with.different.packagename.objectconstruction.basicrules.BasicRules;
import com.examples.with.different.packagename.objectconstruction.instancefield.PrivateInstanceFieldExample;
import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;
import com.examples.with.different.packagename.objectconstruction.tuning.PrimitiveFieldTuningExample;
import com.examples.with.different.packagename.objectconstruction.tuning.OpaqueInterfaceCallExample;
import com.examples.with.different.packagename.objectconstruction.tuning.ReusedLoopSlotExample;
import com.examples.with.different.packagename.objectconstruction.tuning.LazyParserStateExample;
import org.evosuite.testcase.synthesizer.var.VariableCodeGenerationUtil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

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
                TestGenerationContext.getInstance().getObjectRuleRuntime()
                        .getBranchInterestedVarsMap().get(Properties.TARGET_METHOD);
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
		Class<?> sutClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
				.loadClass(clazz.getName());
		Method m = reflectMethod(sutClass, methodName, paramNum);

		java.lang.reflect.Constructor<?> ctor = sutClass.getDeclaredConstructor();
		VariableReference callee = factory.addConstructor(test,
				new org.evosuite.utils.generic.GenericConstructor(ctor, sutClass), test.size(), 0);

		List<VariableReference> params = new ArrayList<>();
		for (Class<?> pType : m.getParameterTypes()) {
			params.add(createValue(test, factory, pType));
		}

		GenericMethod gm = new GenericMethod(m, sutClass);
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

	private static Branch pickBasicRulesAliveGuardBranch() {
		for (Map.Entry<Branch, Set<DepVariable>> entry : graph().entrySet()) {
			if (entry.getKey().getInstruction().getLineNumber() != 9) {
				continue;
			}
			for (DepVariable variable : entry.getValue()) {
				if (variable.getType() == DepVariable.INSTANCE_FIELD
						&& variable.getInstruction().getASMNode() instanceof FieldInsnNode
						&& "alive".equals(((FieldInsnNode) variable.getInstruction().getASMNode()).name)) {
					return entry.getKey();
				}
			}
		}
		throw new AssertionError("expected the BasicRules actor-alive guard branch");
	}

	private static boolean sameReference(TestCase test, VariableReference left,
			VariableReference right) {
		if (left == right) {
			return left != null;
		}
		return ConstructionReferenceUtil.definitionPosition(test, left) >= 0
				&& ConstructionReferenceUtil.definitionPosition(test, left)
				== ConstructionReferenceUtil.definitionPosition(test, right);
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
		ConstructionPathSynthesizer synth = new ConstructionPathSynthesizer();
		synth.buildNodeStatementCorrespondence(test, b, false);
		return synth;
	}

	private static void assertExecutes(TestCase test) {
		ExecutionResult result = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);
		assertNotNull(result, "the synthesised template should execute and produce a result");
	}

	private static int setMarkedIntSlots(TestCase test, int value) {
		int changed = 0;
		for (int i = 0; i < test.size(); i++) {
			Statement statement = test.getStatement(i);
			if (statement instanceof IntPrimitiveStatement
					&& ((IntPrimitiveStatement) statement).isObjectRuleSlot()) {
				((IntPrimitiveStatement) statement).setValue(value);
				changed++;
			}
		}
		return changed;
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
	public void synthesisUsesPackageVisibleMapMutatorForObservedContainer() throws Exception {
		analyze(ReusedLoopSlotExample.class, "scan", 0);
		Class<?> sutClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
				.loadClass(ReusedLoopSlotExample.class.getName());
		Field field = sutClass.getDeclaredField("first");
		Map<Executable, List<ValueSettings>> candidates =
				DataDependencyUtil.searchForPotentialSettersInClass(field, sutClass.getName());
		assertTrue(candidates.keySet().stream()
						.anyMatch(candidate -> candidate.getName().equals("putFirst")));
		assertTrue(candidates.keySet().stream()
						.anyMatch(candidate -> candidate.getName().equals("putFirstViaHelper")),
				"a helper receiver parameter must map back to the field argument at its caller");
		assertFalse(candidates.keySet().stream()
						.anyMatch(candidate -> candidate.getName().equals("copyFirstTo")),
				"mutating an output parameter after observing a field is not a field writer");

		Branch branch = pickStateBearingBranch();
		TestCase test = initializeTestWithTargetCall(ReusedLoopSlotExample.class, "scan", 0);
		int sizeBefore = test.size();

		synthesize(test, branch);

		assertTrue(test.size() > sizeBefore,
				"a private Map observation must produce an owning-object mutation");
		boolean foundMapMutator = false;
		for (int position = 0; position < test.size(); position++) {
			Statement statement = test.getStatement(position);
			if (statement instanceof MethodStatement
					&& ((MethodStatement) statement).getMethod().getName().startsWith("put")) {
				foundMapMutator = true;
				break;
			}
		}
		assertTrue(foundMapMutator,
				"setter discovery must include package-visible methods containing Map.put");
		assertTrue(test.isValid());
		assertExecutes(test);
	}

	@Test
	public void directParserInitializerOutranksTransitiveContainerMutationAndIsCached()
			throws Exception {
		analyze(LazyParserStateExample.class, "target", 0);
		Class<?> sutClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
				.loadClass(LazyParserStateExample.class.getName());
		Field field = sutClass.getDeclaredField("tokens");

		Map<Executable, List<ValueSettings>> first =
				DataDependencyUtil.searchForPotentialSettersInClass(field, sutClass.getName());
		Map<Executable, List<ValueSettings>> second =
				DataDependencyUtil.searchForPotentialSettersInClass(field, sutClass.getName());

		assertSame(first, second, "setter discovery should be reused within one generation run");
		assertEquals(1, DataDependencyUtil.setterAnalysisCacheSize());
		assertEquals(Collections.singletonList("initialize"),
				first.keySet().stream().map(Executable::getName).collect(Collectors.toList()),
				"a direct non-null initializer should terminate discovery before transitive "
						+ "parser and cleanup methods are admitted");
		for (int attempt = 0; attempt < 20; attempt++) {
			Executable selected =
					VariableCodeGenerationUtil.searchForPotentialSetterInClass(
							field, sutClass.getName());
			assertNotNull(selected);
			assertEquals("initialize", selected.getName(),
					"the direct PUTFIELD initializer must outrank parser methods that merely "
							+ "reach List.add through uninitialized state");
		}
	}

	@Test
	public void synthesisInvokesInterfaceMethodsOnConcreteReceiver() throws Exception {
		analyze(OpaqueInterfaceCallExample.class, "target", 1);
		Branch branch = pickStateBearingBranch();

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance()
				.getClassLoaderForSUT();
		Class<?> sutClass = classLoader.loadClass(OpaqueInterfaceCallExample.class.getName());
		Class<?> responseClass = classLoader.loadClass(
				OpaqueInterfaceCallExample.ResponseImpl.class.getName());
		DefaultTestCase test = new DefaultTestCase();
		TestFactory factory = TestFactory.getInstance();
		VariableReference callee = factory.addConstructor(test,
				new org.evosuite.utils.generic.GenericConstructor(
						sutClass.getDeclaredConstructor(), sutClass), test.size(), 0);
		VariableReference response = factory.addConstructor(test,
				new org.evosuite.utils.generic.GenericConstructor(
						responseClass.getDeclaredConstructor(), responseClass), test.size(), 0);
		Method target = reflectMethod(sutClass, "target", 1);
		test.addStatement(new MethodStatement(test, new GenericMethod(target, sutClass), callee,
				java.util.Collections.singletonList(response)), test.size());

		int sizeBefore = test.size();
		synthesize(test, branch);

		assertTrue(test.size() > sizeBefore, "the retained behavioral path must add method calls");
		boolean invokesHeaders = false;
		boolean invokesContains = false;
		for (int position = 0; position < test.size(); position++) {
			Statement statement = test.getStatement(position);
			if (!(statement instanceof MethodStatement)) {
				continue;
			}
			String methodName = ((MethodStatement) statement).getMethod().getName();
			invokesHeaders |= "headers".equals(methodName);
			invokesContains |= "contains".equals(methodName);
		}
		assertTrue(invokesHeaders, "synthesis must invoke the interface method on ResponseImpl");
		assertTrue(invokesContains, "synthesis must retain the branch-producing contains() call");
		assertTrue(test.isValid());
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
		assertFalse(synth.hasUnresolvedRequiredRoot(),
				"target parameters must remain bound to their exact target-call arguments");
		assertExecutes(test);
	}

	@Test
	public void basicRulesArrayElementUsesInsertedPlayerAsStateReceiver() throws Exception {
		analyze(BasicRules.class, "checkRules", 2);
		Branch branch = pickBasicRulesAliveGuardBranch();
		TestCase test = initializeTestWithTargetCall(BasicRules.class, "checkRules", 2);

		ConstructionPathSynthesizer synth = synthesize(test, branch);

		MethodStatement addPlayer = null;
		for (int position = 0; position < test.size(); position++) {
			Statement statement = test.getStatement(position);
			if (statement instanceof MethodStatement
					&& "addPlayer".equals(((MethodStatement) statement).getMethod().getName())) {
				addPlayer = (MethodStatement) statement;
				break;
			}
		}
		assertNotNull(addPlayer, "a non-leaf players[index] node must be realized with addPlayer");
		assertEquals(1, addPlayer.getParameterReferences().size());
		VariableReference insertedPlayer = addPlayer.getParameterReferences().get(0);
		Statement insertedPlayerDefinition = test.getStatement(
				ConstructionReferenceUtil.definitionPosition(test, insertedPlayer));
		assertTrue(insertedPlayerDefinition instanceof ConstructorStatement,
				"the inserted element must be a concrete Player construction, but was "
						+ insertedPlayerDefinition + " in:\n" + test.toCode());
		for (int position = 0; position < test.size(); position++) {
			Statement statement = test.getStatement(position);
			assertFalse(statement instanceof MethodStatement
					&& "removePlayer".equals(((MethodStatement) statement).getMethod().getName()),
					"a method that stores null must not be accepted as an element setter");
		}

		DepVariableWrapper arrayElement = synth.getGraph2CodeMap().keySet().stream()
				.filter(node -> node.var.getType() == DepVariable.ARRAY_ELEMENT)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected an array-element node"));
		VarRelevance arrayMapping = synth.getGraph2CodeMap().get(arrayElement);
		assertTrue(arrayMapping.matchedVars.stream()
				.anyMatch(reference -> sameReference(test, reference, insertedPlayer)),
				"the array node must map to addPlayer's exact Player argument");

		boolean playerStateUsesInsertedElement = false;
		for (int position = 0; position < test.size(); position++) {
			Statement statement = test.getStatement(position);
			if (statement instanceof MethodStatement) {
				MethodStatement method = (MethodStatement) statement;
				if (method.getCallee() != null
						&& "Player".equals(method.getCallee().getVariableClass().getSimpleName())
						&& sameReference(test, method.getCallee(), insertedPlayer)) {
					playerStateUsesInsertedElement = true;
					break;
				}
			}
		}
		assertTrue(playerStateUsesInsertedElement,
				"dependent Player state must be synthesized on addPlayer's exact argument");

		assertTrue(test.isValid(), "the setter/alias repair must leave a structurally valid test");
	}

	/**
	 * Full semantic oracle: graph discovery identifies the nested primitive field, synthesis wires
	 * it to the target receiver, and a chosen slot value covers the intended (non-zero) direction.
	 */
	@Test
	public void synthesisConstructsExpectedStateAndCoversChosenDirection() throws Exception {
		analyze(PrimitiveFieldTuningExample.class, "target", 1);
		Branch branch = pickStateBearingBranch();

		TestCase test = initializeTestWithTargetCall(PrimitiveFieldTuningExample.class, "target", 1);
		ConstructionPathSynthesizer synth = synthesize(test, branch);
		PartialGraph partialGraph = synth.getPartialGraph();
		assertEquals(3, partialGraph.getGraphSize(),
				"the exact partial graph is this -> state -> branchValue, with no extra nodes");
		assertEquals(1, partialGraph.getTopLayer().size());
		assertEquals(1, partialGraph.getLeaves().size());
		DepVariableWrapper receiver = partialGraph.getTopLayer().get(0);
		assertEquals(DepVariable.THIS, receiver.var.getType());
		assertEquals(1, receiver.children.size());
		DepVariableWrapper stateNode = receiver.children.get(0);
		assertEquals("state", ((FieldInsnNode) stateNode.var.getInstruction().getASMNode()).name);
		assertEquals(1, stateNode.parents.size());
		assertEquals(1, stateNode.children.size());
		DepVariableWrapper valueNode = stateNode.children.get(0);
		assertEquals("branchValue", ((FieldInsnNode) valueNode.var.getInstruction().getASMNode()).name);
		assertEquals(1, valueNode.parents.size());
		assertTrue(valueNode.children.isEmpty());
		assertTrue(setMarkedIntSlots(test, 1) > 0,
				"synthesis must expose the primitive field input as an object-rule slot");
		assertTrue(test.isValid(), "the synthesized statement sequence must be internally valid");
		TestCase clone = assertDoesNotThrow(test::clone,
				"the synthesized test must have no dangling references and remain GA-cloneable");
		assertTrue(clone.isValid());

		MethodStatement targetCall = test.findTargetMethodCallStatement();
		assertNotNull(targetCall);
		// Execute every synthesized statement directly. This still exercises the real Statement
		// implementations while avoiding the platform-dependent executor/sandbox worker used by
		// system tests; reaching the end proves that the target call completed synchronously.
		Scope scope = new Scope();
		for (int position = 0; position < test.size(); position++) {
			Throwable thrown = test.getStatement(position).execute(scope, System.out);
			assertNull(thrown, "statement " + position + " threw: " + thrown);
		}

		Object callee = scope.getObject(targetCall.getCallee());
		assertNotNull(callee, "the constructed target receiver must remain observable in the final scope");
		java.lang.reflect.Field stateField = callee.getClass().getField("state");
		Object state = stateField.get(callee);
		assertNotNull(state, "synthesis must construct and assign the nested state object");
		Object branchValue = state.getClass().getMethod("getBranchValue").invoke(state);
		assertEquals(1, ((Number) branchValue).intValue(),
				"the marked primitive must feed the nested branch-controlling field");
		assertEquals(Boolean.TRUE, scope.getObject(targetCall.getReturnValue()),
				"branchValue=1 must take target()'s chosen non-zero/true direction");
	}
}
