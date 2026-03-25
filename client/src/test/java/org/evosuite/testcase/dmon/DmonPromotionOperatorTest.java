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
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

public class DmonPromotionOperatorTest {

    public static class SetterTarget {
        private static Runnable dep;

        public static void setDep(Runnable r) {
            dep = r;
        }
    }

    public static class FieldTarget {
        static Runnable dep;
    }

    public static class ValidateTarget {
        static Runnable dep;

        public static void setDep(Runnable r) {
            dep = r;
        }

        public ValidateTarget() {
            dep.run();
        }
    }

    public static class ValidateFailTarget {
        static Runnable dep;

        public static void setDep(Runnable r) {
            dep = r;
        }

        public ValidateFailTarget() {
            if (dep == null) {
                throw new NullPointerException("dep");
            }
            throw new IllegalStateException("constructor still fails after setup");
        }
    }

    public static class InstanceFieldTarget {
        private static final InstanceFieldTarget INSTANCE = new InstanceFieldTarget();
        Runnable dep;

        public static InstanceFieldTarget getInstance() {
            return INSTANCE;
        }
    }

    @Test
    public void promotesUsingStaticSetter() {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;

            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(new DefaultTestCase());
            chromosome.setChanged(false);

            ExecutionResult result = new ExecutionResult(chromosome.getTestCase(), null);
            result.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(SetterTarget.class.getName(), "<init>", 10, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.dep"),
                    Optional.of("this"),
                    Optional.of("dep"),
                    Optional.of(Runnable.class.getName())));

            DmonPromotionOperator.applyIfNeeded(chromosome, result);

            Assert.assertTrue(result.isDmonPromotionConsumed());
            Assert.assertTrue(chromosome.isChanged());
            Assert.assertEquals(2, chromosome.getTestCase().size());
            Assert.assertTrue(chromosome.getTestCase().getStatement(0) instanceof FunctionalMockStatement);
            Assert.assertTrue(chromosome.getTestCase().getStatement(1) instanceof MethodStatement);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
        }
    }

    @Test
    public void fallsBackToStaticFieldAssignment() {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;

            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(new DefaultTestCase());
            chromosome.setChanged(false);

            ExecutionResult result = new ExecutionResult(chromosome.getTestCase(), null);
            result.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(FieldTarget.class.getName(), "<init>", 10, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.dep"),
                    Optional.of("this"),
                    Optional.of("dep"),
                    Optional.of(Runnable.class.getName())));

            DmonPromotionOperator.applyIfNeeded(chromosome, result);

            Assert.assertTrue(result.isDmonPromotionConsumed());
            Assert.assertTrue(chromosome.isChanged());
            Assert.assertEquals(2, chromosome.getTestCase().size());
            Assert.assertTrue(chromosome.getTestCase().getStatement(0) instanceof FunctionalMockStatement);
            Assert.assertTrue(chromosome.getTestCase().getStatement(1) instanceof AssignmentStatement);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
        }
    }

    @Test
    public void validatePromotedOnceAcceptsPassingPromotion() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldValidate = Properties.DMON_VALIDATE_PROMOTED_ONCE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;

            ValidateTarget.dep = null;

            TestChromosome chromosome = new TestChromosome();
            DefaultTestCase tc = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    ValidateTarget.class.getDeclaredConstructor(), ValidateTarget.class);
            tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));
            chromosome.setTestCase(tc);
            chromosome.setChanged(false);

            ExecutionResult original = new ExecutionResult(chromosome.getTestCase(), null);
            original.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(ValidateTarget.class.getName(), "<init>", 10, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.dep"),
                    Optional.of("this"),
                    Optional.of("dep"),
                    Optional.of(Runnable.class.getName())));

            ExecutionResult effective = DmonPromotionOperator.applyIfNeeded(chromosome, original);

            Assert.assertTrue(original.isDmonPromotionConsumed());
            Assert.assertTrue("Validated promoted execution should pass", effective.noThrownExceptions());
            Assert.assertEquals("Mock + setup + original constructor", 3, chromosome.getTestCase().size());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = oldValidate;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
            ValidateTarget.dep = null;
        }
    }

    @Test
    public void validatePromotedOnceRollsBackFailingPromotion() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldValidate = Properties.DMON_VALIDATE_PROMOTED_ONCE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;

            ValidateFailTarget.dep = null;

            TestChromosome chromosome = new TestChromosome();
            DefaultTestCase tc = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    ValidateFailTarget.class.getDeclaredConstructor(), ValidateFailTarget.class);
            tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));
            chromosome.setTestCase(tc);
            chromosome.setChanged(false);

            ExecutionResult original = new ExecutionResult(chromosome.getTestCase(), null);
            original.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(ValidateFailTarget.class.getName(), "<init>", 0, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.dep"),
                    Optional.of("this"),
                    Optional.of("dep"),
                    Optional.of(Runnable.class.getName())));

            ExecutionResult effective = DmonPromotionOperator.applyIfNeeded(chromosome, original);

            Assert.assertTrue(original.isDmonPromotionConsumed());
            Assert.assertNotNull(effective);
            Assert.assertEquals("Rollback should restore original test shape", 1, chromosome.getTestCase().size());
            Assert.assertNull("Validation rollback should restore static state", ValidateFailTarget.dep);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = oldValidate;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
            ValidateFailTarget.dep = null;
        }
    }

    @Test
    public void contaminatedResultDoesNotPromote() {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;

            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(new DefaultTestCase());
            chromosome.setChanged(false);

            ExecutionResult result = new ExecutionResult(chromosome.getTestCase(), null);
            result.setDmonContaminated(true);
            result.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(SetterTarget.class.getName(), "<init>", 10, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.dep"),
                    Optional.of("this"),
                    Optional.of("dep"),
                    Optional.of(Runnable.class.getName())));

            DmonPromotionOperator.applyIfNeeded(chromosome, result);

            Assert.assertTrue(result.isDmonPromotionConsumed());
            Assert.assertEquals("Contaminated executions must not mutate test", 0, chromosome.getTestCase().size());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
        }
    }

    @Test
    public void fallsBackToInstanceFieldAssignmentViaSingletonAccessor() {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;

            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(new DefaultTestCase());
            chromosome.setChanged(false);

            ExecutionResult result = new ExecutionResult(chromosome.getTestCase(), null);
            result.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(InstanceFieldTarget.class.getName(), "<init>", 10, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.dep"),
                    Optional.of("this"),
                    Optional.of("dep"),
                    Optional.of(Runnable.class.getName())));

            DmonPromotionOperator.applyIfNeeded(chromosome, result);

            Assert.assertTrue(result.isDmonPromotionConsumed());
            Assert.assertTrue(chromosome.isChanged());
            Assert.assertEquals(3, chromosome.getTestCase().size());
            Assert.assertTrue(chromosome.getTestCase().getStatement(0) instanceof FunctionalMockStatement);
            Assert.assertTrue("Accessor call should be inserted before field write",
                    chromosome.getTestCase().getStatement(1) instanceof MethodStatement);
            Assert.assertTrue(chromosome.getTestCase().getStatement(2) instanceof AssignmentStatement);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
        }
    }
}
