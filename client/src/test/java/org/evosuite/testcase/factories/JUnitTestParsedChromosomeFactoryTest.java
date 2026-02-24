/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JUnitTestParsedChromosomeFactoryTest {

    private ChromosomeFactory<TestChromosome> defaultFactory;
    private double originalSeedClone;
    private int originalSeedMutations;

    @BeforeEach
    void setUp() {
        originalSeedClone = Properties.SEED_CLONE;
        originalSeedMutations = Properties.SEED_MUTATIONS;

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
}
