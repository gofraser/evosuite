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

import org.evosuite.Properties;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M1 (method-level targeting): with {@code Properties.TARGET_METHOD}
 * set and the object rule enabled, a generated test calls the target method and
 * that call is protected from deletion. All behaviour is a no-op unless both the
 * object rule is enabled and {@code TARGET_METHOD} is set.
 */
public class TargetMethodModeTest {

    /** Fixture with a static target method and a non-target method. */
    static class Target {
        public static boolean run(int x) {
            return x > 0;
        }

        public void other() {
            // no-op
        }
    }

    private final String savedTargetMethod = Properties.TARGET_METHOD;
    private final String savedTargetClass = Properties.TARGET_CLASS;
    private final boolean savedObjectRule = Properties.APPLY_OBJECT_RULE;
    private final boolean savedSortCalls = Properties.SORT_CALLS;

    @AfterEach
    public void restoreProperties() {
        Properties.TARGET_METHOD = savedTargetMethod;
        Properties.TARGET_CLASS = savedTargetClass;
        Properties.APPLY_OBJECT_RULE = savedObjectRule;
        Properties.SORT_CALLS = savedSortCalls;
    }

    private static GenericMethod targetGenericMethod() throws Exception {
        Method run = Target.class.getDeclaredMethod("run", int.class);
        return new GenericMethod(run, Target.class);
    }

    private static GenericMethod otherGenericMethod() throws Exception {
        Method other = Target.class.getDeclaredMethod("other");
        return new GenericMethod(other, Target.class);
    }

    /** Build a test: int primitive, then a call to Target.run with it. Returns the call statement position. */
    private static int appendTargetCall(DefaultTestCase tc) throws Exception {
        VariableReference arg = tc.addStatement(new IntPrimitiveStatement(tc, 5));
        MethodStatement call = new MethodStatement(tc, targetGenericMethod(), null,
                new ArrayList<>(Arrays.asList(arg)));
        tc.addStatement(call);
        return tc.size() - 1;
    }

    private static void selectTarget() throws Exception {
        Properties.TARGET_CLASS = Target.class.getName();
        Properties.TARGET_METHOD = targetGenericMethod().getNameWithDescriptor();
        // The M1 generation hooks only take effect when the object rule is enabled.
        Properties.APPLY_OBJECT_RULE = true;
    }

    @Test
    public void findsTargetMethodCallStatementWhenTargetSet() throws Exception {
        selectTarget();
        DefaultTestCase tc = new DefaultTestCase();
        int pos = appendTargetCall(tc);

        MethodStatement found = tc.findTargetMethodCallStatement();
        assertNotNull(found);
        assertEquals(pos, found.getPosition());
        assertEquals(pos, TestGenerationUtil.getTargetMethodPosition(tc, tc.size() - 1));
        assertTrue(TestGenerationUtil.checkTargetMethodInvocation(tc));
        assertFalse(TestGenerationUtil.checkTwiceTargetMethodInvocation(tc));
    }

    @Test
    public void findsNothingWhenTargetMethodAbsent() throws Exception {
        selectTarget();
        DefaultTestCase tc = new DefaultTestCase();
        // no target-method call present, only a primitive
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        assertNull(tc.findTargetMethodCallStatement());
        assertEquals(-1, TestGenerationUtil.getTargetMethodPosition(tc, tc.size() - 1));
        assertFalse(TestGenerationUtil.checkTargetMethodInvocation(tc));
    }

    @Test
    public void detectsTwiceTargetInvocation() throws Exception {
        selectTarget();
        DefaultTestCase tc = new DefaultTestCase();
        appendTargetCall(tc);
        appendTargetCall(tc);
        assertTrue(TestGenerationUtil.checkTwiceTargetMethodInvocation(tc));
    }

    @Test
    public void filterTargetMethodRemovesOnlyTarget() throws Exception {
        selectTarget();
        DefaultTestCase tc = new DefaultTestCase();
        List<GenericAccessibleObject<?>> candidates = new ArrayList<>();
        candidates.add(targetGenericMethod());
        candidates.add(otherGenericMethod());

        assertTrue(TestGenerationUtil.isTargetMethod(targetGenericMethod()));
        assertFalse(TestGenerationUtil.isTargetMethod(otherGenericMethod()));

        TestGenerationUtil.filterTargetMethod(tc, candidates);
        assertEquals(1, candidates.size());
        assertFalse(TestGenerationUtil.isTargetMethod(candidates.get(0)));
    }

    @Test
    public void targetCallIsProtectedFromDeletion() throws Exception {
        selectTarget();
        DefaultTestCase tc = new DefaultTestCase();
        int targetPos = appendTargetCall(tc);

        TestFactory factory = TestFactory.getInstance();
        // Deleting the target call itself is refused.
        assertFalse(factory.deleteStatement(tc, targetPos));
        // Deleting a statement the target depends on is refused too (cascade).
        assertFalse(factory.deleteStatement(tc, 0));
        assertNotNull(tc.findTargetMethodCallStatement());
    }

    @Test
    public void targetCallDeletableWhenObjectRuleDisabled() throws Exception {
        // TARGET_METHOD set but the object rule OFF: the M1 deletion guard must not apply, so the
        // target call is deletable exactly like upstream.
        Properties.TARGET_CLASS = Target.class.getName();
        Properties.TARGET_METHOD = targetGenericMethod().getNameWithDescriptor();
        Properties.APPLY_OBJECT_RULE = false;

        DefaultTestCase tc = new DefaultTestCase();
        int targetPos = appendTargetCall(tc);

        TestFactory factory = TestFactory.getInstance();
        assertTrue(factory.deleteStatement(tc, targetPos),
                "with the object rule disabled, the target call must be deletable (upstream behavior)");
    }

    @Test
    public void randomTestCallNotForcedToTargetWhenObjectRuleDisabled() throws Exception {
        // TARGET_METHOD set but the object rule OFF: getRandomTestCall must not force the target
        // method. With forcing active every call would return the target; here a non-target call
        // must surface, proving the forcing loop is gated off.
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        cluster.addTestCall(targetGenericMethod());
        cluster.addTestCall(otherGenericMethod());

        Properties.TARGET_CLASS = Target.class.getName();
        Properties.TARGET_METHOD = targetGenericMethod().getNameWithDescriptor();
        Properties.APPLY_OBJECT_RULE = false;
        Properties.SORT_CALLS = false;
        Randomness.setSeed(42L);

        boolean sawNonTarget = false;
        for (int i = 0; i < 50 && !sawNonTarget; i++) {
            GenericAccessibleObject<?> choice = cluster.getRandomTestCall(new DefaultTestCase());
            if (choice != null && !TestGenerationUtil.isTargetMethod(choice)) {
                sawNonTarget = true;
            }
        }
        TestCluster.reset();

        assertTrue(sawNonTarget,
                "with the object rule disabled, getRandomTestCall must not force the target method");
    }

    @Test
    public void noTargetSetLeavesDeletionAndQueriesUnaffected() throws Exception {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        DefaultTestCase tc = new DefaultTestCase();
        int targetPos = appendTargetCall(tc);

        // With no target method configured, everything behaves as default.
        assertNull(tc.findTargetMethodCallStatement());
        assertEquals(-1, TestGenerationUtil.getTargetMethodPosition(tc, tc.size() - 1));

        TestFactory factory = TestFactory.getInstance();
        assertTrue(factory.deleteStatement(tc, targetPos));
    }
}
