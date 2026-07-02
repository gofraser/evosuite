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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.MethodUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.FieldInsnNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M2 verification for the object construction graph (OCG) analysis. Each test runs the
 * full {@link DependencyAnalysis#analyzeClass} pipeline (with {@code apply_object_rule=true}) on a
 * fixture and asserts the structure of {@link InterproceduralGraphAnalysis#branchInterestedVarsMap}:
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
				InterproceduralGraphAnalysis.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
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

	private static boolean hasArrayElementLeaf() {
		for (DepVariable v : allLeaves()) {
			if (v.getType() == DepVariable.ARRAY_ELEMENT) {
				return true;
			}
		}
		return false;
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
	}

	@Test
	public void testArrayElement() {
		analyze(com.examples.with.different.packagename.objectconstruction.arrayelement.ArrayElementExample.class,
				"targetM", 1, true);

		DepVariable array = findField(DepVariable.INSTANCE_FIELD, "ArrayElementExample", "array");
		assertNotNull(array, "the branch depends on the array field 'array'");
		assertTrue(hasValidRootedPath(array));

		assertTrue(hasArrayElementLeaf(), "array[index] must appear as an ARRAY_ELEMENT leaf");
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
	}

	@Test
	public void testBasicRulesRunningExample() {
		analyze(com.examples.with.different.packagename.objectconstruction.basicrules.BasicRules.class,
				"checkRules", 2, true);

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
	public void testDisabledWhenObjectRuleOff() {
		// With apply_object_rule=false the hook must not run: no OCG is built, leaving upstream
		// behaviour untouched (regression safety).
		analyze(com.examples.with.different.packagename.objectconstruction.instancefield.PrivateInstanceFieldExample.class,
				"targetM", 1, false);

		assertNull(InterproceduralGraphAnalysis.branchInterestedVarsMap.get(Properties.TARGET_METHOD),
				"no OCG should be built when apply_object_rule is off");
		assertFalse(Properties.APPLY_OBJECT_RULE);
	}
}
