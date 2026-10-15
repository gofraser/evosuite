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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.Properties;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JUnitTestParsedChromosomeFactoryTest {

    /** A class under test whose binary name differs from its canonical name. */
    public static class NestedCut {
        public NestedCut() {
        }
    }

    private ChromosomeFactory<TestChromosome> defaultFactory;
    private double originalSeedClone;
    private int originalSeedMutations;
    private String originalSelectedJUnit;
    private String originalSeedTestSourceDir;

    private Logger evoLogger;
    private Level originalEvoLevel;
    private ListAppender<ILoggingEvent> evoAppender;

    @BeforeEach
    void setUp() {
        originalSeedClone = Properties.SEED_CLONE;
        originalSeedMutations = Properties.SEED_MUTATIONS;
        originalSelectedJUnit = Properties.SELECTED_JUNIT;
        originalSeedTestSourceDir = Properties.SEED_TEST_SOURCE_DIR;

        evoLogger = (Logger) LoggerFactory.getLogger(LoggingUtils.getEvoLogger().getName());
        originalEvoLevel = evoLogger.getLevel();
        evoLogger.setLevel(Level.INFO);
        evoAppender = new ListAppender<>();
        evoAppender.start();
        evoLogger.addAppender(evoAppender);

        // Default factory that produces empty chromosomes
        defaultFactory = () -> {
            TestChromosome tc = new TestChromosome();
            tc.setTestCase(new DefaultTestCase());
            return tc;
        };
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        Properties.SEED_CLONE = originalSeedClone;
        Properties.SEED_MUTATIONS = originalSeedMutations;
        Properties.SELECTED_JUNIT = originalSelectedJUnit;
        Properties.SEED_TEST_SOURCE_DIR = originalSeedTestSourceDir;

        evoLogger.detachAppender(evoAppender);
        evoLogger.setLevel(originalEvoLevel);
    }

    private List<String> evoWarnings() {
        return evoAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    /** new NestedCut(); int x = 42; assertEquals(42, x); */
    private TestCase createSeedWithAssertion() throws NoSuchMethodException {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(NestedCut.class.getConstructor(), NestedCut.class),
                Collections.emptyList()));
        VariableReference x = tc.addStatement(new IntPrimitiveStatement(tc, 42));
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(x);
        assertion.setValue(42);
        tc.getStatement(x.getStPosition()).addAssertion(assertion);
        return tc;
    }

    private TestCase createSimpleTestCase() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));
        return tc;
    }

    @Test
    void factoryWithParsedTestsProducesChromosomes() {
        List<TestCase> tests = new ArrayList<>();
        tests.add(createSimpleTestCase());

        Properties.SEED_CLONE = 1.0; // always clone
        Properties.SEED_MUTATIONS = 0;

        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, tests);

        TestChromosome chromosome = factory.getChromosome();
        assertNotNull(chromosome);
        assertTrue(chromosome.getTestCase().size() > 0,
                "Cloned test should have statements");
    }

    @Test
    void factoryFallsBackToDefaultWhenNoParsedTests() {
        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, Collections.emptyList());

        assertFalse(factory.hasParsedTestCases());
        assertEquals(0, factory.getNumParsedTestCases());

        TestChromosome chromosome = factory.getChromosome();
        assertNotNull(chromosome);
    }

    @Test
    void getChromosomeRespectsSeedCloneProbability() {
        List<TestCase> tests = new ArrayList<>();
        tests.add(createSimpleTestCase());

        Properties.SEED_CLONE = 0.0; // never clone
        Properties.SEED_MUTATIONS = 0;

        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, tests);

        // With P_clone=0, should always use defaultFactory (which produces empty tests)
        TestChromosome chromosome = factory.getChromosome();
        assertNotNull(chromosome);
        assertEquals(0, chromosome.getTestCase().size(),
                "Should use default factory when SEED_CLONE=0");
    }

    @Test
    void hasParsedTestCasesReturnsCorrectly() {
        List<TestCase> tests = new ArrayList<>();
        tests.add(createSimpleTestCase());

        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, tests);

        assertTrue(factory.hasParsedTestCases());
        assertEquals(1, factory.getNumParsedTestCases());
        assertEquals(1, factory.getParsedTestCases().size());
    }

    @Test
    void clonedTestIsIndependentCopy() {
        List<TestCase> tests = new ArrayList<>();
        TestCase original = createSimpleTestCase();
        tests.add(original);

        Properties.SEED_CLONE = 1.0;
        Properties.SEED_MUTATIONS = 0;

        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, tests);

        TestChromosome chromosome = factory.getChromosome();
        TestCase cloned = chromosome.getTestCase();

        // Should be a different object (clone, not reference)
        assertNotSame(original, cloned);
        assertEquals(original.size(), cloned.size());
    }

    @Test
    void clonedSeedCarriesNoParsedAssertions() throws Exception {
        TestCase seed = createSeedWithAssertion();
        assertTrue(seed.hasAssertions());

        Properties.SEED_CLONE = 1.0;
        Properties.SEED_MUTATIONS = 0;

        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, Collections.singletonList(seed));

        TestCase cloned = factory.getChromosome().getTestCase();
        assertEquals(seed.size(), cloned.size(), "statements are kept");
        assertFalse(cloned.hasAssertions(), "parsed assertions must not reach the search");
        assertTrue(seed.hasAssertions(), "the stored seed itself is left untouched");
    }

    @Test
    void mutatedSeedCarriesNoParsedAssertions() throws Exception {
        TestCase seed = createSeedWithAssertion();

        Properties.SEED_CLONE = 1.0;
        Properties.SEED_MUTATIONS = 5;

        JUnitTestParsedChromosomeFactory factory =
                new JUnitTestParsedChromosomeFactory(defaultFactory, Collections.singletonList(seed));

        for (int i = 0; i < 20; i++) {
            assertFalse(factory.getChromosome().getTestCase().hasAssertions());
        }
    }

    @Test
    void nestedTargetClassMatchesByBinaryName() throws Exception {
        JUnitTestParsedChromosomeFactory factory = new JUnitTestParsedChromosomeFactory(
                defaultFactory, Collections.singletonList(createSeedWithAssertion()));

        factory.retainTestsReferencingTarget(NestedCut.class.getName());

        assertEquals(1, factory.getNumParsedTestCases());
        assertTrue(evoWarnings().isEmpty(), evoWarnings().toString());
    }

    @Test
    void targetFilterStillAcceptsCanonicalName() throws Exception {
        JUnitTestParsedChromosomeFactory factory = new JUnitTestParsedChromosomeFactory(
                defaultFactory, Collections.singletonList(createSeedWithAssertion()));

        factory.retainTestsReferencingTarget(NestedCut.class.getCanonicalName());

        assertEquals(1, factory.getNumParsedTestCases());
    }

    @Test
    void warnsWhenNoParsedTestReferencesTarget() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(createSeedWithAssertion());
        tests.add(createSimpleTestCase());
        JUnitTestParsedChromosomeFactory factory = new JUnitTestParsedChromosomeFactory(defaultFactory, tests);

        factory.retainTestsReferencingTarget("com.example.Unrelated");

        assertFalse(factory.hasParsedTestCases());
        List<String> warnings = evoWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("None of the 2 parsed tests reference the target class"
                + " com.example.Unrelated"), warnings.get(0));
    }

    @Test
    void reportsDroppedTestsWhenSomeReferenceTarget() throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(createSeedWithAssertion());
        tests.add(createSimpleTestCase());
        JUnitTestParsedChromosomeFactory factory = new JUnitTestParsedChromosomeFactory(defaultFactory, tests);

        factory.retainTestsReferencingTarget(NestedCut.class.getName());

        assertEquals(1, factory.getNumParsedTestCases());
        assertTrue(evoWarnings().isEmpty(), evoWarnings().toString());
        assertTrue(evoAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .anyMatch(m -> m.contains("Dropped 1 of 2 parsed tests that do not reference")),
                "dropped seeds are reported");
    }

    @Test
    void warnsWhenSelectedTestSourceCannotBeParsed() {
        Properties.SELECTED_JUNIT = "com.example.DoesNotExistTest";
        Properties.SEED_TEST_SOURCE_DIR = "/nonexistent-seed-dir";

        JUnitTestParsedChromosomeFactory factory = new JUnitTestParsedChromosomeFactory(defaultFactory);

        assertFalse(factory.hasParsedTestCases());
        List<String> warnings = evoWarnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("No test cases could be parsed from"
                        + " com.example.DoesNotExistTest") && w.contains("seeding has no effect")),
                warnings.toString());
    }
}
