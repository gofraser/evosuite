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
package org.evosuite.graphs.interprocedural;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.MethodUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M2 verification for the object construction graph (OCG) analysis. Each test runs the
 * full {@link DependencyAnalysis#analyzeClass} pipeline (with {@code apply_object_rule=true}) on a
 * fixture and asserts the structure of {@link ObjectConstructionContext#branchInterestedVarsMap}:
 * which state variables each branch of the target method depends on, and that they trace back to a
 * valid root ({@code this}/parameter of the target class, or a static field).
 *
 * <p>The centrepiece is {@link #testBasicRulesRunningExample()}, the paper's running example
 * (Lin et al., ESEC/FSE 2021, Fig. 4/5).
 */
public class ObjectConstructionGraphTest {

	/**
	 * {@code TARGET_METHOD} / {@code TARGET_CLASS} are mutable statics and the analysis caches its
	 * result in a static map keyed by method signature — reset both between tests to avoid leakage.
	 */
	@AfterEach
	public void tearDown() {
		Properties.TARGET_METHOD = "";
		Properties.TARGET_CLASS = "";
		Properties.APPLY_OBJECT_RULE = false;
		InterproceduralGraphAnalysis.clear();
	}

	private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
				return m.getName() + MethodUtil.getSignature(m);
			}
		}
		throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
	}

	private static void analyze(Class<?> clazz, String methodName, int paramNum, boolean applyObjectRule) {
		Properties.TARGET_CLASS = clazz.getName();
		Properties.TARGET_METHOD = signatureOf(clazz, methodName, paramNum);
		Properties.APPLY_OBJECT_RULE = applyObjectRule;
		Properties.CRITERION = new Criterion[] { Criterion.BRANCH };

		String cp = System.getProperty("user.dir") + "/target/test-classes";
		ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
		List<String> classpath = new ArrayList<>();
		classpath.add(cp);
		try {
			DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
		} catch (ClassNotFoundException | RuntimeException e) {
			throw new AssertionError("analyzeClass failed for " + clazz, e);
		}
	}

	private static Map<Branch, Set<DepVariable>> graph() {
		Map<Branch, Set<DepVariable>> map =
				ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
		assertNotNull(map, "the target method should have an entry in branchInterestedVarsMap");
		return map;
	}

	private static Set<DepVariable> allLeaves() {
		Set<DepVariable> all = new LinkedHashSet<>();
		for (Set<DepVariable> leaves : graph().values()) {
			all.addAll(leaves);
		}
		return all;
	}

	private static DepVariable findField(int type, String ownerContains, String name) {
		for (DepVariable v : allLeaves()) {
			if (v.getType() == type && v.getInstruction().getASMNode() instanceof FieldInsnNode) {
				FieldInsnNode f = (FieldInsnNode) v.getInstruction().getASMNode();
				if (f.owner.contains(ownerContains) && f.name.equals(name)) {
					return v;
				}
			}
		}
		return null;
	}

	private static DepVariable findMethodCall(String ownerContains, String name) {
		for (DepVariable variable : allLeaves()) {
			if (variable.getInstruction().getASMNode() instanceof MethodInsnNode) {
				MethodInsnNode call = (MethodInsnNode) variable.getInstruction().getASMNode();
				if (call.owner.contains(ownerContains) && call.name.equals(name)) {
					return variable;
				}
			}
		}
		return null;
	}

	private static boolean hasArrayElementLeaf() {
		for (DepVariable v : allLeaves()) {
			if (v.getType() == DepVariable.ARRAY_ELEMENT) {
				return true;
			}
		}
		return false;
	}

	private static String stateLabel(DepVariable variable) {
		if (variable.getInstruction().getASMNode() instanceof FieldInsnNode) {
			FieldInsnNode field = (FieldInsnNode) variable.getInstruction().getASMNode();
			String owner = field.owner.substring(field.owner.lastIndexOf('/') + 1);
			return owner + "." + field.name;
		}
		if (variable.getType() == DepVariable.ARRAY_ELEMENT) {
			return "ARRAY_ELEMENT";
		}
		return variable.getTypeString();
	}

	private static Set<String> stateLabels(Set<DepVariable> variables) {
		Set<String> labels = new LinkedHashSet<>();
		for (DepVariable variable : variables) {
			labels.add(stateLabel(variable));
		}
		return labels;
	}

	private static List<Map.Entry<Branch, Set<DepVariable>>> branchesAtLine(int line) {
		List<Map.Entry<Branch, Set<DepVariable>>> matches = new ArrayList<>();
		for (Map.Entry<Branch, Set<DepVariable>> entry : graph().entrySet()) {
			if (entry.getKey().getInstruction().getLineNumber() == line) {
				matches.add(entry);
			}
		}
		matches.sort(Comparator.comparingInt(entry -> entry.getKey().getActualBranchId()));
		return matches;
	}

	private static void assertBranchContains(Map.Entry<Branch, Set<DepVariable>> branch,
			String... expectedLabels) {
		Set<String> actual = stateLabels(branch.getValue());
		for (String expected : expectedLabels) {
			assertTrue(actual.contains(expected),
					"branch " + branch.getKey().getActualBranchId() + " at line "
							+ branch.getKey().getInstruction().getLineNumber()
							+ " should contain " + expected + "; actual=" + actual);
		}
	}

	/** Exact state-variable projection; catches both missing and spurious OCG leaves. */
	private static void assertExactStateLeaves(String... expected) {
		Set<String> actual = new LinkedHashSet<>();
		List<String> details = new ArrayList<>();
		for (DepVariable variable : allLeaves()) {
			actual.add(stateLabel(variable));
			details.add(stateLabel(variable) + "=" + variable.getInstruction());
		}
		assertEquals(new LinkedHashSet<>(Arrays.asList(expected)), actual,
				"unexpected graph leaves: " + details);
	}

	/**
	 * Assert an exact root-to-leaf path after projecting away bytecode plumbing nodes (getter calls,
	 * casts, and local temporaries). This makes the meaningful object-state edges explicit.
	 */
	private static void assertExactStatePath(DepVariable leaf, String... expected) {
		List<String> wanted = Arrays.asList(expected);
		List<List<String>> candidates = new ArrayList<>();
		for (DepVariable root : leaf.getRootVars().keySet()) {
			ConstructionPath path = root.findPath(leaf);
			if (path == null) {
				continue;
			}
			List<String> projected = new ArrayList<>();
			for (DepVariable variable : path.getPath()) {
				if (variable.isStateVariable()) {
					projected.add(stateLabel(variable));
				}
			}
			candidates.add(projected);
			if (projected.equals(wanted)) {
				return;
			}
		}
		assertTrue(candidates.contains(wanted),
				"no valid OCG path had the expected state-edge projection; actual=" + candidates);
	}

	/**
	 * Mirrors {@code InterproceduralGraphAnalysis.checkObjectDifficultPath}: a leaf is reachable
	 * from a valid root when some root-to-leaf {@link ConstructionPath} has a valid root (rooted in
	 * the target class or a static field).
	 */
	private static boolean hasValidRootedPath(DepVariable leaf) {
		for (DepVariable root : leaf.getRootVars().keySet()) {
			ConstructionPath path = root.findPath(leaf);
			if (path != null && path.hasValidRoot()) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------

	@Test
	public void testPrivateInstanceField() {
		analyze(com.examples.with.different.packagename.objectconstruction.instancefield.PrivateInstanceFieldExample.class,
				"targetM", 1, true);

		DepVariable field = findField(DepVariable.INSTANCE_FIELD, "PrivateInstanceFieldExample", "field");
		assertNotNull(field, "the branch depends on the private instance field 'field'");
		assertTrue(hasValidRootedPath(field), "'field' must trace back to this of the target class");
		assertExactStateLeaves("PrivateInstanceFieldExample.field");
		assertExactStatePath(field, "PrivateInstanceFieldExample.field");
	}

	@Test
	public void testPublicInstanceFieldWithInterproceduralGetter() {
		analyze(com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample.class,
				"targetM", 1, true);

		// the field of the CUT that holds the sub-object
		DepVariable student1 = findField(DepVariable.INSTANCE_FIELD, "PublicInstanceFieldExample", "student1");
		assertNotNull(student1, "the branch depends on instance field 'student1'");
		assertTrue(hasValidRootedPath(student1));

		// interprocedural: student1.getAge() < 18 reaches Student.age through the getter
		DepVariable age = findField(DepVariable.INSTANCE_FIELD, "Student", "age");
		assertNotNull(age, "interprocedural analysis must reach Student.age via getAge()");
		assertTrue(hasValidRootedPath(age), "Student.age must trace back to this of the target class");
		assertExactStateLeaves("PublicInstanceFieldExample.student1", "Student.age");
		assertExactStatePath(age, "PublicInstanceFieldExample.student1", "Student.age");
	}

	@Test
	public void testArrayElement() {
		analyze(com.examples.with.different.packagename.objectconstruction.arrayelement.ArrayElementExample.class,
				"targetM", 1, true);

		DepVariable array = findField(DepVariable.INSTANCE_FIELD, "ArrayElementExample", "array");
		assertNotNull(array, "the branch depends on the array field 'array'");
		assertTrue(hasValidRootedPath(array));

		assertTrue(hasArrayElementLeaf(), "array[index] must appear as an ARRAY_ELEMENT leaf");
		assertExactStateLeaves("ArrayElementExample.array", "ARRAY_ELEMENT");
		DepVariable element = null;
		for (DepVariable variable : allLeaves()) {
			if (variable.getType() == DepVariable.ARRAY_ELEMENT) {
				element = variable;
				break;
			}
		}
		assertNotNull(element);
		assertExactStatePath(element, "ArrayElementExample.array", "ARRAY_ELEMENT");
	}

	@Test
	public void testStaticFields() {
		analyze(com.examples.with.different.packagename.objectconstruction.staticfield.PrivateStaticFieldExample.class,
				"targetM", 0, true);

		DepVariable numPrivate = findField(DepVariable.STATIC_FIELD, "Config", "numPrivate");
		assertNotNull(numPrivate, "the branch depends on the private static field Config.numPrivate");
		assertTrue(numPrivate.getInstruction().isStaticDefUse());
		assertTrue(hasValidRootedPath(numPrivate), "a static field is itself a valid root");

		DepVariable objPrivate = findField(DepVariable.STATIC_FIELD, "Config", "objPrivate");
		assertNotNull(objPrivate, "the branch depends on the private static field Config.objPrivate");

		// interprocedural: Config.getObjPrivate().getName() reaches the instance field 'name'
		DepVariable name = findField(DepVariable.INSTANCE_FIELD, "PublicStaticFieldExample", "name");
		assertNotNull(name, "interprocedural analysis must reach the instance field 'name'");
		assertNull(findMethodCall("String", "equals"),
				"String.equals is controlled by the String value and is not opaque collaborator state");
		assertNull(findMethodCall("StringHelper", "StringEquals"),
				"instrumented String equality is analyzer plumbing, not object state");
		assertExactStateLeaves("Config.numPrivate", "Config.objPrivate", "PublicStaticFieldExample.name");
		// The static object and its nested field are retained as separately rooted state leaves;
		// the getter-call plumbing joins them later in the partial synthesis graph.
		assertExactStatePath(name, "PublicStaticFieldExample.name");
	}

	@Test
	public void testMultiplePathsToOneLeaf() {
		analyze(com.examples.with.different.packagename.objectconstruction.multipath.MultiPathExample.class,
				"checkRules1", 2, true);

		DepVariable state = findField(DepVariable.INSTANCE_FIELD, "Param", "state");
		DepVariable player = findField(DepVariable.INSTANCE_FIELD, "State", "player");
		DepVariable alive = findField(DepVariable.INSTANCE_FIELD, "Player", "alive");
		assertNotNull(state, "Param.state must be a leaf");
		assertNotNull(player, "State.player must be a leaf");
		assertNotNull(alive, "Player.alive must be a leaf (reached interprocedurally)");

		// the same leaf is reachable from both parameters p1 and p2 -> multiple root paths
		assertTrue(alive.getRootVars().size() >= 2,
				"Player.alive must be reachable through more than one root (both parameters)");
		assertTrue(hasValidRootedPath(alive));
		assertExactStateLeaves("Param.state", "State.player", "Player.alive");
		// Each getter-reached field is an exact rooted leaf. Their shared parameter/getter plumbing is
		// represented by non-state nodes and merged by ConstructionPathSynthesizer.
		assertExactStatePath(state, "Param.state");
		assertExactStatePath(player, "State.player");
		assertExactStatePath(alive, "Player.alive");
	}

	@Test
	public void testPrimitiveFieldSemanticFixtureHasExactGraph() {
		analyze(com.examples.with.different.packagename.objectconstruction.tuning.PrimitiveFieldTuningExample.class,
				"target", 1, true);

		DepVariable branchValue = findField(DepVariable.INSTANCE_FIELD,
				"PrimitiveFieldTuningState", "branchValue");
		assertNotNull(branchValue);
		assertExactStateLeaves("PrimitiveFieldTuningExample.state", "PrimitiveFieldTuningState.branchValue");
		assertExactStatePath(branchValue,
				"PrimitiveFieldTuningExample.state", "PrimitiveFieldTuningState.branchValue");
	}

	@Test
	public void testReusedIteratorLocalDoesNotJoinConsecutiveLoops() {
		Class<?> fixture = com.examples.with.different.packagename.objectconstruction.tuning
				.ReusedLoopSlotExample.class;
		analyze(fixture, "scan", 0, true);

		Set<String> expectedMaps = new LinkedHashSet<>(
				Arrays.asList("first", "second", "third", "fourth"));
		Set<String> observedMaps = new LinkedHashSet<>();
		for (Map.Entry<Branch, Set<DepVariable>> entry : graph().entrySet()) {
			Set<String> branchMaps = new LinkedHashSet<>();
			for (DepVariable variable : entry.getValue()) {
				if (!(variable.getInstruction().getASMNode() instanceof FieldInsnNode)) {
					continue;
				}
				FieldInsnNode field = (FieldInsnNode) variable.getInstruction().getASMNode();
				if (field.owner.contains("ReusedLoopSlotExample")
						&& expectedMaps.contains(field.name)) {
					branchMaps.add(field.name);
				}
			}
			assertTrue(branchMaps.size() <= 1,
					"a loop predicate must not inherit maps from earlier stores to javac's reused "
							+ "iterator slot; branch=" + entry.getKey() + ", maps=" + branchMaps
							+ ", graph=" + entry.getValue());
			observedMaps.addAll(branchMaps);
		}
		assertEquals(expectedMaps, observedMaps,
				"each map must still occur in its own loop's object graph");
	}

	@Test
	public void testOpaqueInterfaceCallsRetainReceiverConstructionPath() {
		Class<?> fixture = com.examples.with.different.packagename.objectconstruction.tuning
				.OpaqueInterfaceCallExample.class;
		analyze(fixture, "target", 1, true);

		DepVariable headers = findMethodCall("OpaqueInterfaceCallExample$Response", "headers");
		DepVariable contains = findMethodCall("OpaqueInterfaceCallExample$Headers", "contains");
		assertNotNull(headers, "the opaque headers() result must remain a behavioral leaf");
		assertNotNull(contains, "the branch-producing contains() call must remain a behavioral leaf");

		boolean foundReceiverPath = false;
		for (DepVariable root : contains.getRootVars().keySet()) {
			if (!root.isParameter()) {
				continue;
			}
			ConstructionPath path = root.findPath(contains);
			if (path == null) {
				continue;
			}
			List<String> calls = new ArrayList<>();
			for (DepVariable node : path.getPath()) {
				if (node.getInstruction().getASMNode() instanceof MethodInsnNode) {
					calls.add(((MethodInsnNode) node.getInstruction().getASMNode()).name);
				}
			}
			if (calls.equals(Arrays.asList("headers", "contains"))) {
				foundReceiverPath = true;
				break;
			}
		}
		assertTrue(foundReceiverPath,
				"contains() must have the path parameter -> headers() -> contains()");
	}

	@Test
	public void testBasicRulesRunningExample() {
		analyze(com.examples.with.different.packagename.objectconstruction.basicrules.BasicRules.class,
				"checkRules", 2, true);

		assertEquals(39, graph().size(), "the full BasicRules fixture has 39 branch predicates");
		for (Map.Entry<Branch, Set<DepVariable>> entry : graph().entrySet()) {
			assertFalse(entry.getValue().isEmpty(),
					"every BasicRules predicate is controlled by object state; empty OCG for branch "
							+ entry.getKey().getActualBranchId() + " at line "
							+ entry.getKey().getInstruction().getLineNumber());
		}

		List<Map.Entry<Branch, Set<DepVariable>>> gameStateBranches = branchesAtLine(7);
		assertEquals(1, gameStateBranches.size());
		assertEquals(new LinkedHashSet<>(Collections.singletonList("GameState.gamestate")),
				stateLabels(gameStateBranches.get(0).getValue()),
				"the first predicate depends only on the GameState state field");

		List<Map.Entry<Branch, Set<DepVariable>>> actorGuardBranches = branchesAtLine(9);
		assertEquals(2, actorGuardBranches.size());
		assertBranchContains(actorGuardBranches.get(0), "Action.actor", "GameState.players", "ARRAY_ELEMENT");
		assertBranchContains(actorGuardBranches.get(1), "Action.actor", "GameState.players", "ARRAY_ELEMENT",
				"Player.alive");

		List<Map.Entry<Branch, Set<DepVariable>>> guardBranches = branchesAtLine(10);
		assertEquals(4, guardBranches.size());
		assertBranchContains(guardBranches.get(0), "Action.action");
		assertBranchContains(guardBranches.get(1), "Action.action");
		assertBranchContains(guardBranches.get(2), "Action.target", "GameState.players", "ARRAY_ELEMENT");
		assertBranchContains(guardBranches.get(3), "Action.target", "GameState.players", "ARRAY_ELEMENT",
				"Player.alive");

		List<Map.Entry<Branch, Set<DepVariable>>> switchBranches = branchesAtLine(13);
		assertEquals(10, switchBranches.size());
		for (Map.Entry<Branch, Set<DepVariable>> switchBranch : switchBranches) {
			assertBranchContains(switchBranch, "Action.action");
		}

		Map.Entry<Branch, Set<DepVariable>> actorBoss = branchesAtLine(19).get(0);
		assertBranchContains(actorBoss, "Action.actor", "GameState.players", "ARRAY_ELEMENT", "Party.boss");
		Map.Entry<Branch, Set<DepVariable>> kickBoss = branchesAtLine(108).get(0);
		assertBranchContains(kickBoss, "Action.actor", "Action.target", "GameState.players",
				"ARRAY_ELEMENT", "Player.boss");

		// Action state read through action.getActor()/getTarget()/getAction()
		DepVariable actor = findField(DepVariable.INSTANCE_FIELD, "Action", "actor");
		DepVariable target = findField(DepVariable.INSTANCE_FIELD, "Action", "target");
		DepVariable action = findField(DepVariable.INSTANCE_FIELD, "Action", "action");
		// GameState state: the players array, its elements, and the game state flag
		DepVariable players = findField(DepVariable.INSTANCE_FIELD, "GameState", "players");
		DepVariable gamestate = findField(DepVariable.INSTANCE_FIELD, "GameState", "gamestate");

		assertNotNull(actor, "graph must contain action.actor");
		assertNotNull(target, "graph must contain action.target");
		assertNotNull(action, "graph must contain action.action");
		assertNotNull(players, "graph must contain state.players");
		assertNotNull(gamestate, "graph must contain state.gamestate");
		assertTrue(hasArrayElementLeaf(), "graph must contain the players[index] array element");

		// every one of these traces back to a parameter/this of BasicRules.checkRules
		for (DepVariable leaf : new DepVariable[] { actor, target, action, players, gamestate }) {
			assertTrue(hasValidRootedPath(leaf),
					"leaf " + leaf.getName() + " must trace back to a valid root of the target method");
		}

		// the branch guarding state.getGameState() depends on gamestate
		boolean someBranchDependsOnGamestate = false;
		for (Set<DepVariable> leaves : graph().values()) {
			if (leaves.contains(gamestate)) {
				someBranchDependsOnGamestate = true;
				break;
			}
		}
		assertTrue(someBranchDependsOnGamestate);
	}

	@Test
	public void testCfgInstructionResolutionDoesNotRequireFreshAsmNodeIdentity() throws Exception {
		Class<?> target = com.examples.with.different.packagename.objectconstruction.basicrules.BasicRules.class;
		analyze(target, "checkRules", 2, true);

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader)
				.getActualCFG(Properties.TARGET_CLASS, Properties.TARGET_METHOD);
		RawControlFlowGraph rawCfg = GraphPool.getInstance(classLoader)
				.getRawCFG(Properties.TARGET_CLASS, Properties.TARGET_METHOD);
		assertNotNull(cfg);
		assertNotNull(rawCfg);
		BytecodeInstruction original = rawCfg.vertexSet().stream()
				.filter(instruction -> instruction.getASMNode() != null
						&& instruction.getASMNode().getOpcode() >= 0)
				.findFirst().orElseThrow(AssertionError::new);
		ClassNode freshClass = new ClassNode();
		try (InputStream stream = target.getClassLoader().getResourceAsStream(
				target.getName().replace('.', '/') + ".class")) {
			assertNotNull(stream);
			new ClassReader(stream).accept(freshClass, ClassReader.SKIP_FRAMES);
		}
		MethodNode freshlyParsed = freshClass.methods.stream()
				.filter(method -> (method.name + method.desc).equals(Properties.TARGET_METHOD))
				.findFirst().orElseThrow(AssertionError::new);
		assertNotNull(freshlyParsed);
		int foreignIndex = freshlyParsed.instructions.indexOf(original.getASMNode());
		assertTrue(foreignIndex >= 0,
				"ASM caches an instruction's index even when queried against a different list");
		assertNotSame(original.getASMNode(), freshlyParsed.instructions.get(foreignIndex),
				"the cached foreign-list index must not be mistaken for node membership");
		assertSame(original, DefUseAnalyzer.convert2BytecodeInstruction(
				cfg, freshlyParsed, original.getASMNode()));
	}

	@Test
	public void testDisabledWhenObjectRuleOff() {
		// With apply_object_rule=false the hook must not run: no OCG is built, leaving upstream
		// behaviour untouched (regression safety).
		analyze(com.examples.with.different.packagename.objectconstruction.instancefield.PrivateInstanceFieldExample.class,
				"targetM", 1, false);

		assertNull(ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD),
				"no OCG should be built when apply_object_rule is off");
		assertFalse(Properties.APPLY_OBJECT_RULE);
	}
}
