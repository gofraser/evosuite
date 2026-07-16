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
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testsuite;

import org.evosuite.Properties;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.llm.postprocess.LlmPostProcessingMetadata;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StructuralSuiteArtifactTest {

    @TempDir
    Path tempDirectory;

    @Test
    void artifactRoundTripPreservesStructureAndMinimizationMetadata() throws Exception {
        TestSuiteChromosome suite = suiteWithValue(42);
        MinimizationResult minimization = new MinimizationResult(
                MinimizationStatus.COMPLETED, MinimizationStopCause.NONE,
                3, 12, 1, 1, 17L);
        File artifact = tempDirectory.resolve("suite.structural").toFile();

        StructuralSuiteMetadata written = StructuralSuiteArtifact.write(
                artifact, "example.Target", suite, minimization);
        StructuralSuiteArtifact.Loaded loaded = StructuralSuiteArtifact.read(
                artifact, "example.Target");

        assertTrue(artifact.isFile());
        assertTrue(StructuralSuiteArtifact.metadataFile(artifact).isFile());
        assertEquals(written.getStructuralFingerprint(),
                StructuralSuiteFingerprint.compute(loaded.getSuite()));
        assertEquals(1, loaded.getSuite().size());
        assertEquals(1, loaded.getSuite().totalLengthOfTestCases());
        assertEquals(MinimizationStatus.COMPLETED,
                loaded.getMetadata().toMinimizationResult().getStatus());
        assertEquals(3, loaded.getMetadata().getMinimizationOriginalTests());
        assertEquals(17L, loaded.getMetadata().getMinimizationElapsedMillis());
    }

    @Test
    void exportStripsAssertionsAndReadabilityMetadataWithoutMutatingSource() throws Exception {
        TestSuiteChromosome suite = suiteWithValue(7);
        TestCase source = suite.getTestChromosome(0).getTestCase();
        IntPrimitiveStatement statement = (IntPrimitiveStatement) source.getStatement(0);
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(statement.getReturnValue());
        assertion.setValue(7);
        statement.addAssertion(assertion);
        LlmPostProcessingMetadata.getOrCreate(source).setTestName("descriptiveName");

        String beforeOracleMetadata = StructuralSuiteFingerprint.compute(suite);
        File artifact = tempDirectory.resolve("clean.structural").toFile();
        StructuralSuiteArtifact.write(artifact, "example.Target", suite,
                MinimizationResult.disabled(suite));
        StructuralSuiteArtifact.Loaded loaded = StructuralSuiteArtifact.read(
                artifact, "example.Target");
        TestCase replayInput = loaded.getSuite().getTestChromosome(0).getTestCase();

        assertEquals(beforeOracleMetadata, StructuralSuiteFingerprint.compute(loaded.getSuite()));
        assertEquals(1, source.getAssertions().size(), "export must not modify the live suite");
        assertNotNull(LlmPostProcessingMetadata.get(source));
        assertTrue(replayInput.getAssertions().isEmpty());
        assertNull(LlmPostProcessingMetadata.get(replayInput));
    }

    @Test
    void replayRejectsWrongTargetClass() throws Exception {
        TestSuiteChromosome suite = suiteWithValue(1);
        File artifact = tempDirectory.resolve("wrong-target.structural").toFile();
        StructuralSuiteArtifact.write(artifact, "example.Expected", suite,
                MinimizationResult.disabled(suite));

        IOException error = assertThrows(IOException.class,
                () -> StructuralSuiteArtifact.read(artifact, "example.Other"));
        assertTrue(error.getMessage().contains("example.Expected"));
        assertTrue(error.getMessage().contains("example.Other"));
    }

    @Test
    void structuralFingerprintDetectsExecutableChanges() {
        assertNotEquals(StructuralSuiteFingerprint.compute(suiteWithValue(1)),
                StructuralSuiteFingerprint.compute(suiteWithValue(2)));
    }

    @Test
    void structuralFingerprintDoesNotDependOnAssertionArm() throws Exception {
        TestSuiteChromosome suite = suiteWithValue(9);
        File artifact = tempDirectory.resolve("assertion-arm.structural").toFile();
        boolean previous = Properties.ASSERTIONS;
        try {
            Properties.ASSERTIONS = false;
            StructuralSuiteMetadata metadata = StructuralSuiteArtifact.write(
                    artifact, "example.Target", suite, MinimizationResult.disabled(suite));

            Properties.ASSERTIONS = true;
            StructuralSuiteArtifact.Loaded loaded = StructuralSuiteArtifact.read(
                    artifact, "example.Target");

            assertEquals(metadata.getStructuralFingerprint(),
                    StructuralSuiteFingerprint.compute(loaded.getSuite()));
            assertTrue(Properties.ASSERTIONS,
                    "fingerprinting must restore the selected assertion arm");
        } finally {
            Properties.ASSERTIONS = previous;
        }
    }

    private static TestSuiteChromosome suiteWithValue(int value) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, value));
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);
        return suite;
    }
}
