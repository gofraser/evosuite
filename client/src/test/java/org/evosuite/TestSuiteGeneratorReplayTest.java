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
 */
package org.evosuite;

import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testsuite.StructuralSuiteFingerprint;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSuiteGeneratorReplayTest {

    @Test
    void restoringReplayStructureTransfersOnlyAssertions() {
        TestSuiteChromosome assertedSuite = suiteWithValue(7);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.capture(assertedSuite);
        TestCase asserted = assertedSuite.getTestChromosome(0).getTestCase();
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(asserted.getStatement(0).getReturnValue());
        assertion.setValue(7);
        asserted.getStatement(0).addAssertion(assertion);

        String expectedFingerprint = StructuralSuiteFingerprint.compute(suiteWithValue(7));

        TestSuiteGenerator.restoreReplayStructure(assertedSuite, snapshot);

        assertEquals(expectedFingerprint, StructuralSuiteFingerprint.compute(assertedSuite));
        assertFalse(assertedSuite.getTestChromosome(0).getTestCase().getAssertions().isEmpty());
    }

    @Test
    void restoringReplayStructureReinstatesJUnitRejectedTestWithoutAssertions() {
        TestSuiteChromosome suite = suiteWithValue(7);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.capture(suite);
        suite.clearTests();

        TestSuiteGenerator.restoreReplayStructure(suite, snapshot);

        assertEquals(1, suite.size());
        assertEquals(1, suite.getTestChromosome(0).getTestCase().size());
        assertEquals(0, suite.getTestChromosome(0).getTestCase().getAssertions().size());
    }

    @Test
    void hybridRestoreRetainsMutationAssertionsWhenLlmOracleIsRejected() {
        TestSuiteChromosome suite = suiteWithValue(7);
        TestCase mutationSource = suite.getTestChromosome(0).getTestCase();
        PrimitiveAssertion mutationAssertion = new PrimitiveAssertion();
        mutationAssertion.setSource(mutationSource.getStatement(0).getReturnValue());
        mutationAssertion.setValue(7);
        mutationSource.getStatement(0).addAssertion(mutationAssertion);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.captureWithFallbackAssertions(suite);

        suite.clearTests();
        TestSuiteGenerator.restoreReplayStructure(suite, snapshot);

        assertEquals(1, suite.size());
        assertEquals(1, suite.getTestChromosome(0).getTestCase().getAssertions().size());
    }

    @Test
    void hybridRestoreTransfersMutationAndLlmAssertionsWhenOracleIsRetained() {
        TestSuiteChromosome suite = suiteWithValue(7);
        TestCase source = suite.getTestChromosome(0).getTestCase();
        PrimitiveAssertion mutationAssertion = new PrimitiveAssertion();
        mutationAssertion.setSource(source.getStatement(0).getReturnValue());
        mutationAssertion.setValue(7);
        source.getStatement(0).addAssertion(mutationAssertion);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.captureWithFallbackAssertions(suite);
        PrimitiveAssertion llmStandIn = new PrimitiveAssertion();
        llmStandIn.setSource(source.getStatement(0).getReturnValue());
        llmStandIn.setValue(8);
        source.getStatement(0).addAssertion(llmStandIn);

        TestSuiteGenerator.restoreReplayStructure(suite, snapshot);

        assertEquals(2, suite.getTestChromosome(0).getTestCase().getAssertions().size());
    }

    @Test
    void replaySnapshotDetectsSameSizeStatementReplacement() {
        TestSuiteChromosome suite = suiteWithValue(7);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.capture(suite);

        assertTrue(snapshot.hasSameExecutableStructure(suite));
        TestCase test = suite.getTestChromosome(0).getTestCase();
        test.setStatement(new IntPrimitiveStatement(test, 8), 0);

        assertFalse(snapshot.hasSameExecutableStructure(suite));
    }

    @Test
    void restoringReplayStructureUsesAssertionGenerationClassLoader() {
        TestSuiteChromosome suite = suiteWithValue(7);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.capture(suite);
        DefaultTestCase assertionSource = (DefaultTestCase)
                suite.getTestChromosome(0).getTestCase();
        ClassLoader assertionLoader = new ClassLoader(getClass().getClassLoader()) { };
        assertionSource.changeClassLoader(assertionLoader);

        TestSuiteGenerator.restoreReplayStructure(suite, snapshot);

        DefaultTestCase restored = (DefaultTestCase)
                suite.getTestChromosome(0).getTestCase();
        assertSame(assertionLoader, restored.getChangedClassLoader());
    }

    @Test
    void replaySnapshotDetectsWriterStyleChopping() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        TestSuiteChromosome suite = suiteContaining(test);
        TestSuiteGenerator.ReplayStructureSnapshot snapshot =
                TestSuiteGenerator.ReplayStructureSnapshot.capture(suite);

        test.chop(1);

        assertFalse(snapshot.hasSameExecutableStructure(suite));
    }

    @Test
    void writerGuardToleratesSameCountStatementReconstruction() {
        // The JUnit writer may reconstruct statement objects while rendering
        // (for example when TestCodeVisitor resolves descriptors through a
        // swapped instrumentation class loader). A structure-preserving
        // rendering keeps the same test and statement counts and must not be
        // reported as a change, even though object identity differs.
        TestSuiteChromosome suite = suiteWithValue(7);
        int inputTests = suite.size();
        int inputStatements = suite.totalLengthOfTestCases();

        TestCase test = suite.getTestChromosome(0).getTestCase();
        test.setStatement(new IntPrimitiveStatement(test, 8), 0);

        assertTrue(TestSuiteGenerator.writerPreservedExecutableStructure(
                suite, inputTests, inputStatements));
    }

    @Test
    void writerGuardToleratesExceptionChopping() {
        // The writer chops statements after an uncaught exception so the emitted
        // JUnit compiles. That reduces the statement count while keeping the same
        // test, is deterministic, and is applied identically for every arm
        // replaying the same structural suite, so it must be tolerated.
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        TestSuiteChromosome suite = suiteContaining(test);
        int inputTests = suite.size();
        int inputStatements = suite.totalLengthOfTestCases();

        test.chop(1);

        assertTrue(TestSuiteGenerator.writerPreservedExecutableStructure(
                suite, inputTests, inputStatements));
    }

    @Test
    void writerGuardRejectsDroppedTest() {
        // Chopping only removes trailing statements; it never removes a whole
        // test. A change in the number of tests is a genuine structural change.
        DefaultTestCase first = new DefaultTestCase();
        first.addStatement(new IntPrimitiveStatement(first, 7));
        TestSuiteChromosome suite = suiteContaining(first);
        int inputTests = suite.size() + 1;
        int inputStatements = suite.totalLengthOfTestCases() + 1;

        assertFalse(TestSuiteGenerator.writerPreservedExecutableStructure(
                suite, inputTests, inputStatements));
    }

    @Test
    void writerGuardRejectsGrownSuite() {
        // The writer must never add statements; a grown suite is a real change.
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        TestSuiteChromosome suite = suiteContaining(test);

        assertFalse(TestSuiteGenerator.writerPreservedExecutableStructure(
                suite, suite.size(), suite.totalLengthOfTestCases() - 1));
    }

    private static TestSuiteChromosome suiteWithValue(int value) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, value));
        return suiteContaining(test);
    }

    private static TestSuiteChromosome suiteContaining(TestCase test) {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        return suite;
    }
}
