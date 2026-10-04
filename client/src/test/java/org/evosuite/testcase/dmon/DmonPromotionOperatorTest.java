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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.runtime.classhandling.ModifiedTargetStaticFields;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
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

    /** Inherits Inflater's private field "input", which java.base does not open on JDK 9+. */
    public static class InflaterSubclass extends java.util.zip.Inflater {
    }

    @Test
    public void unopenedJdkFieldIsNotAnInjectionTargetAndDoesNotEscape() {
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
            // No inferred type or member token: the mock type must come from the field,
            // which forces setAccessible on the JDK-private field.
            result.setDmonPromotionPlan(new DmonPromotionPlan(
                    new DmonFailureSite(InflaterSubclass.class.getName(), "<init>", 10,
                            DmonFailureSiteKind.CONSTRUCTOR_INIT),
                    Optional.of("this.input"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));

            DmonPromotionOperator.applyIfNeeded(chromosome, result);

            Assert.assertTrue(result.isDmonPromotionConsumed());
            Assert.assertFalse(chromosome.isChanged());
            Assert.assertEquals(0, chromosome.getTestCase().size());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
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
        int oldTimeout = Properties.TIMEOUT;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;
            // This test drives a real TestCaseExecutor.runTest() (via
            // validateOrRollback) that creates a Mockito mock. The first such
            // execution in the JVM can pay a one-off, occasionally multi-second
            // AWT/ByteBuddy class-generation warm-up cost that the default 3s
            // Properties.TIMEOUT does not budget for, independent of anything
            // under test here; give it generous headroom like other tests that
            // execute real code do (e.g. SymbolicObserverTest).
            Properties.TIMEOUT = 60000;

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
            Properties.TIMEOUT = oldTimeout;
            ValidateTarget.dep = null;
        }
    }

    @Test
    public void validatePromotedOnceRollsBackFailingPromotion() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldValidate = Properties.DMON_VALIDATE_PROMOTED_ONCE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        int oldTimeout = Properties.TIMEOUT;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;
            // See validatePromotedOnceAcceptsPassingPromotion: this also drives a
            // real TestCaseExecutor.runTest() and can hit the same one-off warm-up
            // cost.
            Properties.TIMEOUT = 60000;

            ValidateFailTarget.dep = null;

            TestChromosome chromosome = new TestChromosome();
            DefaultTestCase tc = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    ValidateFailTarget.class.getDeclaredConstructor(), ValidateFailTarget.class);
            tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));
            chromosome.setTestCase(tc);
            chromosome.setChanged(false);

            ExecutionResult original = new ExecutionResult(chromosome.getTestCase(), null);
            // A production ExecutionResult always carries a real trace (it comes from
            // TestCaseExecutor.runTest), which ExecutionResult.clone() relies on
            // unconditionally (trace.lazyClone()). The rollback path below calls
            // clone() on this object, so give it a trace here too, the same way a
            // real execution would have.
            original.setTrace(new ExecutionTraceImpl());
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
            Properties.TIMEOUT = oldTimeout;
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

    /**
     * Regression coverage for the JacksonDatabind-96 Defects4J symptom: with
     * {@code RESET_STATIC_FINAL_FIELDS=true} (the default), EvoSuite's instrumentation
     * strips {@code ACC_FINAL} from an originally-final static field and separately
     * registers it in {@link ModifiedTargetStaticFields} - exactly what
     * {@code CreateClassResetClassAdapter.visitEnd()} does at class-load time. A field
     * selector that only checks raw {@code java.lang.reflect.Modifier.isFinal} cannot
     * see that stripping and will happily select the field as an assignment target,
     * producing a statement like {@code Owner.field = mock;} that then fails to
     * compile ("cannot assign a value to final variable") because the field is still
     * declared {@code final} in source.
     *
     * <p>
     * {@link FieldTarget#dep} is not actually declared final (making the reflective
     * {@code Field} object itself final is awkward to arrange in a plain unit test),
     * but registering it in {@link ModifiedTargetStaticFields} reproduces precisely the
     * state the selector must defend against: a field that reflects as non-final yet
     * must never be used as a generated-test assignment target.
     * </p>
     */
    @Test
    public void staticFieldSelectorRefusesFieldWithStrippedFinalModifier() {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        boolean oldReset = Properties.RESET_STATIC_FINAL_FIELDS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;
            Properties.RESET_STATIC_FINAL_FIELDS = true;
            ModifiedTargetStaticFields.getInstance()
                    .addFinalField(FieldTarget.class.getName(), "dep");

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
            Assert.assertFalse(
                    "A field whose final modifier was only stripped by instrumentation "
                            + "must never be selected as a static assignment target",
                    chromosome.isChanged());
            Assert.assertEquals("No statement should have been inserted",
                    0, chromosome.getTestCase().size());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
            Properties.RESET_STATIC_FINAL_FIELDS = oldReset;
            ModifiedTargetStaticFields.resetSingleton();
        }
    }

    /**
     * Complementary case to
     * {@link #staticFieldSelectorRefusesFieldWithStrippedFinalModifier()}: here the
     * mock statement {@code has} already been inserted into the chromosome's live
     * {@link TestCase} - exactly what {@code factory.addFunctionalMock} does in place
     * inside {@code DmonPromotionOperator.tryPromoteInPlace} - and only then does a
     * later step of the promotion plan fail. Every one of the five failure exits past
     * that mutation point (two guard checks, two catch clauses) now funnels through
     * the same private {@code rollbackMutatedTestCase} helper, so this test drives
     * that helper directly with the exact object-identity setup production code
     * builds: a pre-mutation {@code backup} clone taken before the mutation, and the
     * live {@code TestCase} already carrying the orphaned mock.
     *
     * <p>
     * Driving this through the five individual call sites is not practical: each one
     * is reached only after upstream discovery
     * ({@code DmonInjectionDiscovery}/{@code TestClusterUtils.isFinalField}, fixed in
     * d78ed5255) and statement construction ({@code AssignmentStatement}'s
     * final-field guard, fixed in 46f4cffd6) have already screened out exactly the
     * conditions that would make them fire - by design, none of the five should ever
     * trigger with correctly-screened input; they exist as defense in depth against a
     * future discovery bug. {@code rollbackMutatedTestCase} is itself that shared
     * defense, so testing it directly with a real, production-identical mutation is
     * the precise and honest way to verify it.
     * </p>
     */
    @Test
    public void rollbackAfterMockAddedDiscardsOrphanedMockAndClearsChangedFlag() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;

            TestChromosome chromosome = new TestChromosome();
            DefaultTestCase tc = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    ValidateTarget.class.getDeclaredConstructor(), ValidateTarget.class);
            tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));
            chromosome.setTestCase(tc);
            chromosome.setChanged(false);

            ExecutionResult original = new ExecutionResult(chromosome.getTestCase(), null);
            // rollbackMutatedTestCase calls originalResult.clone(), which (like
            // validateOrRollback's own rollback path) unconditionally dereferences
            // the trace; give this result a trace the way a real execution would.
            original.setTrace(new ExecutionTraceImpl());
            chromosome.setLastExecutionResult(original);

            // Mirror tryPromoteInPlace lines 245/248: snapshot a backup before the
            // mutation, then mutate the live TestCase in place exactly as
            // factory.addFunctionalMock does - the orphaned mock a failed promotion
            // attempt would otherwise leave behind.
            TestCase backup = tc.clone();
            TestFactory.getInstance().addFunctionalMock(tc, Runnable.class, 0, new GenerationContext());
            Assert.assertEquals("Sanity check: the mock really did mutate the live test case in place",
                    2, chromosome.getTestCase().size());
            Assert.assertTrue(chromosome.getTestCase().getStatement(0) instanceof FunctionalMockStatement);

            Method rollback = DmonPromotionOperator.class.getDeclaredMethod(
                    "rollbackMutatedTestCase", TestChromosome.class, TestCase.class, ExecutionResult.class);
            rollback.setAccessible(true);
            ExecutionResult restored = (ExecutionResult) rollback.invoke(null, chromosome, backup, original);

            Assert.assertEquals("A failure exit after the mock was added must leave the chromosome's "
                            + "test case unchanged (same size as before the promotion attempt)",
                    1, chromosome.getTestCase().size());
            Assert.assertFalse(chromosome.getTestCase().getStatement(0) instanceof FunctionalMockStatement);
            Assert.assertFalse("The chromosome must not be left claiming to be unchanged while its "
                            + "test case still differs from what was last evaluated - nor, after the "
                            + "rollback runs, claiming a change that was in fact undone",
                    chromosome.isChanged());
            Assert.assertSame("The chromosome must be evaluated against the restored result next time",
                    restored, chromosome.getLastExecutionResult());
            Assert.assertSame("The restored result must point at the same TestCase object the "
                            + "chromosome now holds",
                    chromosome.getTestCase(), restored.test);
            Assert.assertTrue("A plan that failed at construction time cannot succeed on retry without "
                            + "code changes, so it must not be retried",
                    restored.isDmonPromotionConsumed());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
        }
    }

    /**
     * Same rollback helper, but starting from {@code chromosome.setChanged(true)} to
     * prove the helper does not merely leave whatever flag value was already there: it
     * must actively force {@code isChanged()} back to {@code false}, because after the
     * rollback the chromosome's test case is, byte for byte, what {@code originalResult}
     * was last evaluated against.
     */
    @Test
    public void rollbackAfterMockAddedForcesChangedFlagFalseEvenIfPreviouslyTrue() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;

            TestChromosome chromosome = new TestChromosome();
            DefaultTestCase tc = new DefaultTestCase();
            chromosome.setTestCase(tc);
            chromosome.setChanged(false);

            ExecutionResult original = new ExecutionResult(chromosome.getTestCase(), null);
            original.setTrace(new ExecutionTraceImpl());
            chromosome.setLastExecutionResult(original);

            TestCase backup = tc.clone();
            TestFactory.getInstance().addFunctionalMock(tc, Runnable.class, 0, new GenerationContext());
            // Simulate some intervening code having speculatively (and, for this
            // failure exit, wrongly) marked the chromosome as changed.
            chromosome.setChanged(true);

            Method rollback = DmonPromotionOperator.class.getDeclaredMethod(
                    "rollbackMutatedTestCase", TestChromosome.class, TestCase.class, ExecutionResult.class);
            rollback.setAccessible(true);
            rollback.invoke(null, chromosome, backup, original);

            Assert.assertFalse("isChanged() must be forced back to false: after the rollback the test "
                            + "case is exactly what was last evaluated, so the GA must not re-run fitness "
                            + "evaluation needlessly, nor skip re-evaluation of a genuinely stale cache",
                    chromosome.isChanged());
            Assert.assertEquals(0, chromosome.getTestCase().size());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
        }
    }
}
