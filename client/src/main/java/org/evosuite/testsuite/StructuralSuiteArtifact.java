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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Reads and writes validated structural-suite artifacts. */
public final class StructuralSuiteArtifact {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private StructuralSuiteArtifact() {
        // utility class
    }

    public static StructuralSuiteMetadata write(File artifactFile, String targetClass,
                                                TestSuiteChromosome suite,
                                                MinimizationResult minimizationResult) throws IOException {
        validatePath(artifactFile);
        if (targetClass == null || targetClass.trim().isEmpty()) {
            throw new IllegalArgumentException("targetClass must not be empty");
        }
        if (suite == null) {
            throw new IllegalArgumentException("suite must not be null");
        }

        File artifact = artifactFile.getAbsoluteFile();
        File parent = artifact.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create artifact directory " + parent);
        }

        TestSuiteChromosome structuralSuite = suite.clone();
        for (TestCase test : structuralSuite.getTests()) {
            test.removeAssertions();
            TestPresentationMetadata.clear(test);
        }
        Path artifactPath = artifact.toPath();
        Path metadataPath = metadataFile(artifact).toPath();
        Path artifactTemp = new File(artifact.getPath() + ".tmp").toPath();
        Path metadataTemp = new File(metadataPath.toString() + ".tmp").toPath();

        StructuralSuiteMetadata metadata;
        try {
            Files.deleteIfExists(artifactTemp);
            Files.deleteIfExists(metadataTemp);
            if (!TestSuiteSerialization.saveTests(structuralSuite, artifactTemp.toFile())) {
                throw new IOException("Could not serialize structural suite to " + artifact);
            }

            // Class-loader normalization performed while deserializing can
            // change how otherwise equivalent reflective statements render.
            // Fingerprint the persisted representation, which is the exact
            // representation every replay arm will load, rather than the
            // pre-serialization in-memory graph.
            List<TestChromosome> normalizedTests = TestSuiteSerialization.loadTests(
                    artifactTemp.toFile());
            TestSuiteChromosome normalizedSuite = new TestSuiteChromosome();
            for (TestChromosome test : normalizedTests) {
                normalizedSuite.addTestChromosome(test);
            }
            boolean normalizationChangedShape = normalizedSuite.size() != structuralSuite.size()
                    || normalizedSuite.totalLengthOfTestCases()
                    != structuralSuite.totalLengthOfTestCases();
            if (normalizationChangedShape) {
                // A test can deserialize yet fail class-loader normalization.
                // Persist the successfully normalized subset as the canonical
                // common input rather than leaving a truncated, unusable
                // artifact or losing every test that followed the bad one.
                if (!TestSuiteSerialization.saveTests(normalizedSuite, artifactTemp.toFile())) {
                    throw new IOException("Could not serialize normalized structural suite to "
                            + artifact);
                }
                List<TestChromosome> validationTests = TestSuiteSerialization.loadTests(
                        artifactTemp.toFile());
                TestSuiteChromosome validationSuite = new TestSuiteChromosome();
                for (TestChromosome test : validationTests) {
                    validationSuite.addTestChromosome(test);
                }
                if (validationSuite.size() != normalizedSuite.size()
                        || validationSuite.totalLengthOfTestCases()
                        != normalizedSuite.totalLengthOfTestCases()) {
                    throw new IOException("Normalized structural suite is not round-trip stable: expected "
                            + normalizedSuite.size() + " tests/"
                            + normalizedSuite.totalLengthOfTestCases() + " statements but loaded "
                            + validationSuite.size() + " tests/"
                            + validationSuite.totalLengthOfTestCases() + " statements");
                }
                normalizedSuite = validationSuite;
            }
            MinimizationResult persistedMinimization = minimizationResult;
            if (normalizationChangedShape && minimizationResult != null) {
                persistedMinimization = new MinimizationResult(
                        minimizationResult.getStatus(),
                        minimizationResult.getUnderlyingStopCause(),
                        minimizationResult.getOriginalTests(),
                        minimizationResult.getOriginalLength(),
                        normalizedSuite.size(),
                        normalizedSuite.totalLengthOfTestCases(),
                        minimizationResult.getElapsedMillis());
            }
            metadata = StructuralSuiteMetadata.create(
                    targetClass.trim(), normalizedSuite, persistedMinimization);
            JSON.writeValue(metadataTemp.toFile(), metadata);
            moveReplacing(artifactTemp, artifactPath);
            moveReplacing(metadataTemp, metadataPath);
        } finally {
            Files.deleteIfExists(artifactTemp);
            Files.deleteIfExists(metadataTemp);
        }
        return metadata;
    }

    public static Loaded read(File artifactFile, String expectedTargetClass) throws IOException {
        validatePath(artifactFile);
        File artifact = artifactFile.getAbsoluteFile();
        if (!artifact.isFile()) {
            throw new IOException("Structural-suite artifact does not exist: " + artifact);
        }
        File metadataFile = metadataFile(artifact);
        if (!metadataFile.isFile()) {
            throw new IOException("Structural-suite metadata does not exist: " + metadataFile);
        }

        StructuralSuiteMetadata metadata = JSON.readValue(metadataFile, StructuralSuiteMetadata.class);
        validateMetadata(metadata, expectedTargetClass);

        List<TestChromosome> tests = TestSuiteSerialization.loadTests(artifact);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (TestChromosome test : tests) {
            suite.addTestChromosome(test);
        }

        if (suite.size() != metadata.getTestCount()
                || suite.totalLengthOfTestCases() != metadata.getStatementCount()) {
            throw new IOException("Structural-suite shape does not match its metadata: expected "
                    + metadata.getTestCount() + " tests/" + metadata.getStatementCount()
                    + " statements but loaded " + suite.size() + " tests/"
                    + suite.totalLengthOfTestCases() + " statements");
        }

        String fingerprint = StructuralSuiteFingerprint.compute(suite);
        if (!fingerprint.equals(metadata.getStructuralFingerprint())) {
            throw new IOException("Structural-suite fingerprint mismatch for " + artifact
                    + ": expected " + metadata.getStructuralFingerprint()
                    + " but computed " + fingerprint);
        }
        return new Loaded(suite, metadata);
    }

    public static File metadataFile(File artifactFile) {
        return new File(artifactFile.getPath() + ".json");
    }

    private static void validatePath(File artifactFile) {
        if (artifactFile == null || artifactFile.getPath().trim().isEmpty()) {
            throw new IllegalArgumentException("artifactFile must not be empty");
        }
    }

    private static void validateMetadata(StructuralSuiteMetadata metadata,
                                         String expectedTargetClass) throws IOException {
        if (metadata == null) {
            throw new IOException("Structural-suite metadata is empty");
        }
        if (metadata.getSchemaVersion() != StructuralSuiteMetadata.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported structural-suite schema version "
                    + metadata.getSchemaVersion());
        }
        if (metadata.getStructuralFingerprint() == null
                || !metadata.getStructuralFingerprint().matches("[0-9a-f]{64}")) {
            throw new IOException("Structural-suite metadata has an invalid fingerprint");
        }
        if (metadata.getTargetClass() == null || metadata.getTargetClass().trim().isEmpty()) {
            throw new IOException("Structural-suite metadata has no target class");
        }
        if (expectedTargetClass != null && !expectedTargetClass.trim().isEmpty()
                && !expectedTargetClass.trim().equals(metadata.getTargetClass())) {
            throw new IOException("Structural suite targets " + metadata.getTargetClass()
                    + " but replay requested " + expectedTargetClass.trim());
        }
        if (metadata.getTestCount() < 0 || metadata.getStatementCount() < 0
                || metadata.getMinimizationOriginalTests() < 0
                || metadata.getMinimizationOriginalLength() < 0
                || metadata.getMinimizationFinalTests() < 0
                || metadata.getMinimizationFinalLength() < 0
                || metadata.getMinimizationElapsedMillis() < 0L) {
            throw new IOException("Structural-suite metadata contains negative counts");
        }
        try {
            metadata.toMinimizationResult();
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class Loaded {
        private final TestSuiteChromosome suite;
        private final StructuralSuiteMetadata metadata;

        private Loaded(TestSuiteChromosome suite, StructuralSuiteMetadata metadata) {
            this.suite = suite;
            this.metadata = metadata;
        }

        public TestSuiteChromosome getSuite() { return suite; }
        public StructuralSuiteMetadata getMetadata() { return metadata; }
    }
}
