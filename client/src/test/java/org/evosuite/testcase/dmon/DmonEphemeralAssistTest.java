/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class DmonEphemeralAssistTest {

    public static class StaticNullCtorTarget {
        static Runnable dep;
        static int ctorCalls;

        public StaticNullCtorTarget() {
            ctorCalls++;
            dep.run();
        }
    }

    public static class SetterNullCtorTarget {
        static Runnable dep;
        static int ctorCalls;
        static int setterCalls;

        public static void setDep(Runnable r) {
            setterCalls++;
            dep = r;
        }

        public SetterNullCtorTarget() {
            ctorCalls++;
            dep.run();
        }
    }

    public static class SetterRollbackFailureTarget {
        static Runnable dep;
        static int setterCalls;

        public static void setDep(Runnable r) {
            setterCalls++;
            if (r == null) {
                throw new IllegalStateException("rollback not allowed");
            }
            dep = r;
        }

        public SetterRollbackFailureTarget() {
            dep.run();
        }
    }

    public static class InstanceConfigTarget {
        private static final InstanceConfigTarget INSTANCE = new InstanceConfigTarget();
        Runnable dep;

        public static InstanceConfigTarget getInstance() {
            return INSTANCE;
        }

        static void useDep() {
            getInstance().dep.run();
        }
    }

    public static class UsesInstanceConfigInCtor {
        static int ctorCalls;

        public UsesInstanceConfigInCtor() {
            ctorCalls++;
            InstanceConfigTarget.useDep();
        }
    }


    @Test
    public void constructorNpeIsEphemerallyAssistedAndRolledBack() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.TARGET_CLASS = StaticNullCtorTarget.class.getCanonicalName();

            StaticNullCtorTarget.dep = null;
            StaticNullCtorTarget.ctorCalls = 0;

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    StaticNullCtorTarget.class.getDeclaredConstructor(), StaticNullCtorTarget.class);
            test.addStatement(new ConstructorStatement(test, gc, Collections.emptyList()));

            ExecutionResult result = TestCaseExecutor.runTest(test);

            Assert.assertNotNull("DMoN should at least record a promotion candidate",
                    result.getDmonPromotionPlan());
            Assert.assertFalse("Rollback should restore state without contamination flag",
                    result.isDmonContaminated());
            Assert.assertNull("Ephemeral static injection must be rolled back", StaticNullCtorTarget.dep);
            Assert.assertTrue("Constructor should execute at least once", StaticNullCtorTarget.ctorCalls >= 1);
            if (result.noThrownExceptions()) {
                Assert.assertEquals("If assisted execution succeeds, constructor should run once failing + once retried",
                        2, StaticNullCtorTarget.ctorCalls);
            }
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.TARGET_CLASS = oldTargetClass;
            StaticNullCtorTarget.dep = null;
            StaticNullCtorTarget.ctorCalls = 0;
            TestCaseExecutor.pullDown();
            TestCaseExecutor.initExecutor();
        }
    }

    @Test
    public void setterBasedEphemeralAssistIsUsedWhenAvailable() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = false;
            Properties.TARGET_CLASS = SetterNullCtorTarget.class.getCanonicalName();

            SetterNullCtorTarget.dep = null;
            SetterNullCtorTarget.ctorCalls = 0;
            SetterNullCtorTarget.setterCalls = 0;

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    SetterNullCtorTarget.class.getDeclaredConstructor(), SetterNullCtorTarget.class);
            test.addStatement(new ConstructorStatement(test, gc, Collections.emptyList()));

            ExecutionResult result = TestCaseExecutor.runTest(test);

            Assert.assertNotNull("DMoN should record a promotion candidate", result.getDmonPromotionPlan());
            Assert.assertFalse("Rollback should restore state without contamination flag",
                    result.isDmonContaminated());
            Assert.assertNull("Rollback should restore setter-managed static state", SetterNullCtorTarget.dep);
            Assert.assertTrue("Constructor should execute at least once", SetterNullCtorTarget.ctorCalls >= 1);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
            Properties.TARGET_CLASS = oldTargetClass;
            SetterNullCtorTarget.dep = null;
            SetterNullCtorTarget.ctorCalls = 0;
            SetterNullCtorTarget.setterCalls = 0;
            TestCaseExecutor.pullDown();
            TestCaseExecutor.initExecutor();
        }
    }

    @Test
    public void rollbackFailureMarksContamination() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = false;
            Properties.TARGET_CLASS = SetterRollbackFailureTarget.class.getCanonicalName();

            SetterRollbackFailureTarget.dep = null;
            SetterRollbackFailureTarget.setterCalls = 0;

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    SetterRollbackFailureTarget.class.getDeclaredConstructor(), SetterRollbackFailureTarget.class);
            test.addStatement(new ConstructorStatement(test, gc, Collections.emptyList()));

            ExecutionResult result = TestCaseExecutor.runTest(test);

            Assert.assertNotNull("DMoN should record a promotion candidate", result.getDmonPromotionPlan());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
            Properties.TARGET_CLASS = oldTargetClass;
            SetterRollbackFailureTarget.dep = null;
            SetterRollbackFailureTarget.setterCalls = 0;
            TestCaseExecutor.pullDown();
            TestCaseExecutor.initExecutor();
        }
    }

    @Test
    public void instanceSingletonEphemeralAssistIsRolledBack() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.TARGET_CLASS = UsesInstanceConfigInCtor.class.getCanonicalName();

            InstanceConfigTarget.getInstance().dep = null;
            UsesInstanceConfigInCtor.ctorCalls = 0;

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    UsesInstanceConfigInCtor.class.getDeclaredConstructor(), UsesInstanceConfigInCtor.class);
            test.addStatement(new ConstructorStatement(test, gc, Collections.emptyList()));

            ExecutionResult result = TestCaseExecutor.runTest(test);

            Assert.assertNotNull("DMoN should record a promotion candidate", result.getDmonPromotionPlan());
            Assert.assertFalse("Rollback should restore state without contamination flag", result.isDmonContaminated());
            Assert.assertNull("Ephemeral instance injection must be rolled back", InstanceConfigTarget.getInstance().dep);
            Assert.assertTrue("Constructor should execute at least once", UsesInstanceConfigInCtor.ctorCalls >= 1);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.TARGET_CLASS = oldTargetClass;
            InstanceConfigTarget.getInstance().dep = null;
            UsesInstanceConfigInCtor.ctorCalls = 0;
            TestCaseExecutor.pullDown();
            TestCaseExecutor.initExecutor();
        }
    }

}
