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
package org.evosuite.testcase.statements;

import com.examples.with.different.packagename.StaticFinalFieldHolder;
import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteSerialization;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the JacksonDatabind-96 Defects4J symptom: EvoSuite generated
 * {@code BasicDeserializerFactory._collectionFallbacks = hashMap0;} against a
 * package-private {@code static final} field, which javac rejects
 * ("cannot assign a value to final variable"). Visibility permitted the write (same
 * package); only finality forbade it, and nothing in the statement-creation or
 * rendering path checked finality for an assignment target.
 *
 * <p>
 * {@link StaticFinalFieldHolder#collectionFallbacks} reproduces the exact shape:
 * package-private, static, final, non-primitive.
 * </p>
 */
public class FinalFieldAssignmentGuardTest {

    private String previousClassPrefix;

    @BeforeEach
    public void saveClassPrefix() {
        previousClassPrefix = Properties.CLASS_PREFIX;
    }

    @AfterEach
    public void restoreClassPrefix() {
        Properties.CLASS_PREFIX = previousClassPrefix;
    }

    /**
     * 1. Creation guard: no {@link AssignmentStatement} may ever be constructed with a
     * final field as its target.
     */
    @Test
    public void testConstructorRejectsFinalFieldTarget() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference hashMapVar = addHashMapConstructor(tc);
        FieldReference finalFieldTarget = finalFieldReference(tc);

        assertTrue(finalFieldTarget.getField().isFinal());
        assertThrows(IllegalArgumentException.class,
                () -> new AssignmentStatement(tc, finalFieldTarget, hashMapVar));
    }

    /**
     * 1b. Setter guard: {@link AbstractStatement#setRetval} is a second way to point an
     * already-constructed {@link AssignmentStatement} at a target, bypassing the
     * constructor entirely. {@link AssignmentStatement} overrides it to apply the exact
     * same {@code rejectFinalFieldTarget} check, so repointing a legal statement at a
     * final field after construction must fail exactly like constructing one directly
     * at that target would.
     */
    @Test
    public void testSetRetvalRejectsRepointingAtFinalFieldTarget() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference hashMapVar = addHashMapConstructor(tc);
        FieldReference finalFieldTarget = finalFieldReference(tc);

        AssignmentStatement statement = new AssignmentStatement(tc, hashMapVar, hashMapVar);
        VariableReference originalRetval = statement.getReturnValue();

        assertThrows(IllegalArgumentException.class,
                () -> statement.setRetval(finalFieldTarget));
        // Rejected repointing must leave the statement's existing target untouched.
        assertTrue(statement.getReturnValue() == originalRetval);
    }

    /**
     * 1c. The setter guard must not be overzealous: it has to keep accepting the two
     * kinds of retval that are legitimately not an illegal final-field write - a
     * non-final field target, and a plain (non-{@link FieldReference}) variable, which
     * is what every other statement type uses {@code retval} for (see
     * {@link AssignmentStatement#setRetval} javadoc for why only this subclass's
     * retval is unambiguously an assignment target).
     */
    @Test
    public void testSetRetvalAcceptsNonFinalFieldAndNonFieldReferenceTargets() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference hashMapVar = addHashMapConstructor(tc);
        AssignmentStatement statement = new AssignmentStatement(tc, hashMapVar, hashMapVar);

        // A non-FieldReference retval (e.g. repointing at another plain variable, as
        // ArrayReference.changeClassLoader and DefaultTestCase.clone() do) is accepted.
        VariableReference anotherVar = addHashMapConstructor(tc);
        statement.setRetval(anotherVar);
        assertTrue(statement.getReturnValue() == anotherVar);

        // A FieldReference retval targeting a non-final field is also accepted.
        Field rawField = StaticFinalFieldHolder.class.getDeclaredField("nonFinalCollectionFallbacks");
        assertFalse(java.lang.reflect.Modifier.isFinal(rawField.getModifiers()));
        GenericField genericField = new GenericField(rawField, StaticFinalFieldHolder.class);
        FieldReference nonFinalFieldTarget = new FieldReference(tc, genericField, (VariableReference) null);

        statement.setRetval(nonFinalFieldTarget);
        assertTrue(statement.getReturnValue() == nonFinalFieldTarget);
    }

    /**
     * 2. Closing the deserialization hole: {@link GenericField#readObject} re-resolves
     * its target {@code Field} on every deserialization but never re-checks finality.
     * A statement that reaches that boundary already targeting a final field (here
     * built by writing the inherited {@code retval} field directly via reflection,
     * since both the constructor and {@code setRetval} now refuse that target -
     * exactly mirroring what plain Java deserialization also does: populate fields
     * directly, without ever running a constructor or calling a setter) must be
     * revalidated and rejected by the round trip that
     * {@code StructuralSuiteArtifact}/{@code TestSuiteSerialization} performs, not
     * silently rebuilt with the illegal statement intact.
     */
    @Test
    public void testSerializationRoundTripRejectsReconstructedFinalFieldAssignment() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference hashMapVar = addHashMapConstructor(tc);
        FieldReference finalFieldTarget = finalFieldReference(tc);

        AssignmentStatement illegal = buildStatementWithTargetBypassingGuard(tc, hashMapVar,
                finalFieldTarget);
        tc.addStatement(illegal);
        // Precondition: the illegal statement is actually present before the round
        // trip (checked structurally, not via toCode(): the renderer itself already
        // refuses to emit a final-field write, which is exactly the other half of
        // this fix and is covered separately by testRendererDropsAssignmentToFinalField).
        assertTrue(illegal.getReturnValue() instanceof FieldReference);
        assertTrue(((FieldReference) illegal.getReturnValue()).getField().isFinal());

        File tmp = File.createTempFile("final-field-assignment-guard", ".bin");
        tmp.deleteOnExit();
        try {
            TestSuiteChromosome suite = new TestSuiteChromosome();
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(tc);
            suite.addTestChromosome(chromosome);

            assertTrue(TestSuiteSerialization.saveTests(suite, tmp));
            List<TestChromosome> loaded = TestSuiteSerialization.loadTests(tmp);

            // The reconstructed statement must be revalidated and rejected -
            // TestSuiteSerialization#loadTests drops the one test case it belongs to
            // rather than let it survive the round trip with an illegal direct write.
            assertTrue(loaded.isEmpty(),
                    "A test case whose only content is a final-field write must be dropped, not"
                            + " silently rebuilt");
            for (TestChromosome survivor : loaded) {
                assertFalse(survivor.getTestCase().toCode().contains("collectionFallbacks ="));
            }
        } finally {
            tmp.delete();
        }
    }

    /**
     * 3. Render path: given a statement that somehow targets a final field (again
     * built by bypassing the constructor guard, to isolate the renderer's own
     * behaviour from the creation guard), {@link TestCodeVisitor} must not emit a
     * direct {@code Class.field = value;} assignment - nor a reflective
     * {@code PrivateAccess.setVariable} write, which was explicitly rejected as a fix
     * because it would only work because instrumentation stripped {@code final}.
     */
    @Test
    public void testRendererDropsAssignmentToFinalField() throws Exception {
        // Same package as the fixture, so canUseDirectFieldAccess's visibility check
        // alone would have allowed a direct write - exactly the JacksonDatabind-96
        // scenario, where only finality (not visibility) made the statement illegal.
        Properties.CLASS_PREFIX = "com.examples.with.different.packagename";

        DefaultTestCase tc = new DefaultTestCase();
        VariableReference hashMapVar = addHashMapConstructor(tc);
        FieldReference finalFieldTarget = finalFieldReference(tc);

        AssignmentStatement illegal = buildStatementWithTargetBypassingGuard(tc, hashMapVar,
                finalFieldTarget);
        tc.addStatement(illegal);

        String code = tc.toCode();
        assertFalse(code.contains("collectionFallbacks ="),
                "must not emit a direct write to a final field");
        assertFalse(code.contains("\"collectionFallbacks\""),
                "must not fall back to a reflective PrivateAccess write either");
    }

    private static VariableReference addHashMapConstructor(DefaultTestCase tc) throws Exception {
        GenericConstructor hashMapCtor = new GenericConstructor(
                HashMap.class.getConstructor(), HashMap.class);
        return tc.addStatement(new ConstructorStatement(tc, hashMapCtor, Collections.emptyList()));
    }

    private static FieldReference finalFieldReference(DefaultTestCase tc) throws Exception {
        Field rawField = StaticFinalFieldHolder.class.getDeclaredField("collectionFallbacks");
        GenericField genericField = new GenericField(rawField, StaticFinalFieldHolder.class);
        return new FieldReference(tc, genericField, (VariableReference) null);
    }

    /**
     * Builds an {@link AssignmentStatement} whose target is a final field without ever
     * passing that target through the guarded constructor - first constructing a
     * harmless, legal statement, then repointing it by writing the inherited
     * {@code retval} field directly via reflection, bypassing both the constructor
     * guard and the (now also guarded) {@link AbstractStatement#setRetval} API. This is
     * the only way left to produce the illegal object graph once both of those refuse
     * it, and it stands in for what plain Java deserialization does: populate fields
     * directly, never running a constructor or calling a setter.
     */
    private static AssignmentStatement buildStatementWithTargetBypassingGuard(DefaultTestCase tc,
            VariableReference value, FieldReference finalFieldTarget) throws Exception {
        AssignmentStatement statement = new AssignmentStatement(tc, value, value);
        Field retvalField = AbstractStatement.class.getDeclaredField("retval");
        retvalField.setAccessible(true);
        retvalField.set(statement, finalFieldTarget);
        return statement;
    }
}
