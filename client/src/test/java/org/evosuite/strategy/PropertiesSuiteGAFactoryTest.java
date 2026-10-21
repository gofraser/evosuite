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
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.archive.ArchiveTestChromosomeFactory;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.LIPS;
import org.evosuite.ga.metaheuristics.MIO;
import org.evosuite.ga.metaheuristics.TestSuiteAdapter;
import org.evosuite.ga.metaheuristics.mosa.DynaMOSA;
import org.evosuite.ga.metaheuristics.mosa.MOSA;
import org.evosuite.llm.factory.LlmTestChromosomeFactory;
import org.evosuite.llm.search.TestSuiteChromosomeInjectionAdapter;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.AllMethodsTestChromosomeFactory;
import org.evosuite.testcase.factories.JUnitTestParsedChromosomeFactory;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.factories.TestSuiteChromosomeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins which test chromosome factory the suite GA factory hands to the search algorithms:
 * under MOSUITE only PARSED_JUNIT changes it, every other TEST_FACTORY value (including the
 * default ARCHIVE) keeps the plain random-length factory.
 */
class PropertiesSuiteGAFactoryTest {

    private final PropertiesSuiteGAFactory gaFactory = new PropertiesSuiteGAFactory();

    @BeforeEach
    void setUp() {
        Properties.getInstance().resetToDefaults();
        // Nothing to parse: the factory is built but stays empty.
        Properties.SELECTED_JUNIT = "";
    }

    @AfterEach
    void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    void defaultsAreMosuiteWithArchiveFactoryAndDynaMosa() {
        assertEquals(Properties.Strategy.MOSUITE, Properties.STRATEGY);
        assertEquals(Properties.TestFactory.ARCHIVE, Properties.TEST_FACTORY);
        assertEquals(Properties.Algorithm.DYNAMOSA, Properties.ALGORITHM);
    }

    @Test
    void mosuiteWithDefaultTestFactoryUsesRandomLengthFactory() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;

        assertSame(RandomLengthTestFactory.class, innerFactory(gaFactory.getChromosomeFactory()).getClass());
    }

    @ParameterizedTest
    @EnumSource(value = Properties.TestFactory.class, names = {"PARSED_JUNIT", "LLM"},
            mode = EnumSource.Mode.EXCLUDE)
    void mosuiteIgnoresEveryTestFactoryButParsedJUnit(Properties.TestFactory testFactory) {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = testFactory;

        assertSame(RandomLengthTestFactory.class, innerFactory(gaFactory.getChromosomeFactory()).getClass());
    }

    @Test
    void mosuiteWithLlmTestFactoryWrapsRandomLengthFactory() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.LLM;

        ChromosomeFactory<TestChromosome> inner = innerFactory(gaFactory.getChromosomeFactory());
        LlmTestChromosomeFactory llm = assertInstanceOf(LlmTestChromosomeFactory.class, inner);
        assertSame(RandomLengthTestFactory.class, llm.getFallbackFactory().getClass());
    }

    @Test
    void mosuiteWithParsedJUnitUsesParsedFactory() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;

        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, innerFactory(gaFactory.getChromosomeFactory()));
    }

    @Test
    void evosuiteStrategyStillHonoursTestFactory() {
        Properties.STRATEGY = Properties.Strategy.EVOSUITE;

        Properties.TEST_FACTORY = Properties.TestFactory.ARCHIVE;
        assertInstanceOf(ArchiveTestChromosomeFactory.class, innerFactory(gaFactory.getChromosomeFactory()));

        Properties.TEST_FACTORY = Properties.TestFactory.ALLMETHODS;
        assertInstanceOf(AllMethodsTestChromosomeFactory.class, innerFactory(gaFactory.getChromosomeFactory()));

        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;
        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, innerFactory(gaFactory.getChromosomeFactory()));
    }

    @Test
    void dynaMosaReceivesParsedFactoryUnderMosuite() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;
        Properties.ALGORITHM = Properties.Algorithm.DYNAMOSA;
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH};

        GeneticAlgorithm<?> algorithm = wrappedAlgorithm(buildAlgorithm());

        assertInstanceOf(DynaMOSA.class, algorithm);
        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, factoryOf(algorithm));
    }

    @Test
    void dynaMosaKeepsRandomLengthFactoryByDefault() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.ALGORITHM = Properties.Algorithm.DYNAMOSA;
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH};

        GeneticAlgorithm<?> algorithm = wrappedAlgorithm(buildAlgorithm());

        assertInstanceOf(DynaMOSA.class, algorithm);
        assertSame(RandomLengthTestFactory.class, factoryOf(algorithm).getClass());
    }

    @Test
    void dynaMosaFallbackToMosaReceivesParsedFactory() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;
        Properties.ALGORITHM = Properties.Algorithm.DYNAMOSA;
        Properties.CRITERION = new Criterion[]{Criterion.LINE};

        GeneticAlgorithm<?> algorithm = wrappedAlgorithm(buildAlgorithm());

        assertSame(MOSA.class, algorithm.getClass());
        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, factoryOf(algorithm));
    }

    @Test
    void mosaReceivesParsedFactoryUnderMosuite() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;
        Properties.ALGORITHM = Properties.Algorithm.MOSA;

        GeneticAlgorithm<?> algorithm = wrappedAlgorithm(buildAlgorithm());

        assertSame(MOSA.class, algorithm.getClass());
        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, factoryOf(algorithm));
    }

    @Test
    void mioReceivesParsedFactoryUnderMosuite() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;
        Properties.ALGORITHM = Properties.Algorithm.MIO;

        GeneticAlgorithm<?> algorithm = wrappedAlgorithm(buildAlgorithm());

        assertInstanceOf(MIO.class, algorithm);
        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, factoryOf(algorithm));
    }

    @Test
    void lipsReceivesParsedFactoryUnderMosuite() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.PARSED_JUNIT;
        Properties.ALGORITHM = Properties.Algorithm.LIPS;
        Properties.CRITERION = new Criterion[]{Criterion.LINE};

        GeneticAlgorithm<?> algorithm = wrappedAlgorithm(buildAlgorithm());

        assertInstanceOf(LIPS.class, algorithm);
        assertInstanceOf(JUnitTestParsedChromosomeFactory.class, factoryOf(algorithm));
    }

    @ParameterizedTest
    @EnumSource(value = Properties.Algorithm.class, names = {"STANDARD_GA", "MONOTONIC_GA"})
    void wholeSuiteGaInjectsLlmTestsAsSuites(Properties.Algorithm algorithm) {
        Properties.STRATEGY = Properties.Strategy.EVOSUITE;
        Properties.ALGORITHM = algorithm;
        Properties.SHUTDOWN_HOOK = false;
        Properties.LLM_SEED_INITIAL_POPULATION = true;
        Properties.LLM_ASYNC_PRODUCER = true;
        Properties.LLM_ON_STAGNATION = true;

        GeneticAlgorithm<TestSuiteChromosome> ga = gaFactory.getSearchAlgorithm();

        assertInstanceOf(TestSuiteChromosomeInjectionAdapter.class, ga.getLlmInjectionAdapter(),
                "without an adapter the GA drops every LLM test it is handed");
    }

    private GeneticAlgorithm<TestSuiteChromosome> buildAlgorithm() {
        return gaFactory.getGeneticAlgorithm(gaFactory.getChromosomeFactory());
    }

    private static ChromosomeFactory<TestChromosome> innerFactory(ChromosomeFactory<TestSuiteChromosome> factory) {
        return assertInstanceOf(TestSuiteChromosomeFactory.class, factory).getTestChromosomeFactory();
    }

    private static GeneticAlgorithm<?> wrappedAlgorithm(GeneticAlgorithm<TestSuiteChromosome> adapter) {
        assertInstanceOf(TestSuiteAdapter.class, adapter);
        return (GeneticAlgorithm<?>) readField(TestSuiteAdapter.class, adapter, "algorithm");
    }

    private static Object factoryOf(GeneticAlgorithm<?> algorithm) {
        return readField(GeneticAlgorithm.class, algorithm, "chromosomeFactory");
    }

    private static Object readField(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot read " + owner.getSimpleName() + "." + name, e);
        }
    }
}
