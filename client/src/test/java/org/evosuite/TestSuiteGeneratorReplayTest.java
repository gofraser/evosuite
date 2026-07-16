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
