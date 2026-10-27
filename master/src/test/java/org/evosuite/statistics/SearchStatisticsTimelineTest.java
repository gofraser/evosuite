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
package org.evosuite.statistics;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchCoverageSuiteFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageSuiteFitness;
import org.evosuite.coverage.cbranch.CBranchSuiteFitness;
import org.evosuite.coverage.exception.ExceptionCoverageSuiteFitness;
import org.evosuite.coverage.io.input.InputCoverageSuiteFitness;
import org.evosuite.coverage.io.output.OutputCoverageSuiteFitness;
import org.evosuite.coverage.line.LineCoverageSuiteFitness;
import org.evosuite.coverage.method.MethodCoverageSuiteFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageSuiteFitness;
import org.evosuite.coverage.method.MethodTraceCoverageSuiteFitness;
import org.evosuite.coverage.mutation.OnlyMutationSuiteFitness;
import org.evosuite.coverage.mutation.WeakMutationSuiteFitness;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins which value each default output variable and timeline reads from the best individual.
 */
class SearchStatisticsTimelineTest {

    private static final Class<?>[] CRITERIA = {
            BranchCoverageSuiteFitness.class, OnlyBranchCoverageSuiteFitness.class, CBranchSuiteFitness.class,
            MethodTraceCoverageSuiteFitness.class, MethodCoverageSuiteFitness.class,
            MethodNoExceptionCoverageSuiteFitness.class, LineCoverageSuiteFitness.class,
            OutputCoverageSuiteFitness.class, InputCoverageSuiteFitness.class,
            ExceptionCoverageSuiteFitness.class, WeakMutationSuiteFitness.class, OnlyMutationSuiteFitness.class
    };

    @BeforeEach
    void setUp() {
        SearchStatistics.clearAllInstances();
        Properties.STATISTICS_BACKEND = Properties.StatisticsBackend.DEBUG;
    }

    @AfterEach
    void tearDown() {
        SearchStatistics.clearAllInstances();
        Properties.getInstance().resetToDefaults();
    }

    @Test
    void timelinesAndSuiteVariablesReadTheirSource() throws Exception {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(new DefaultTestCase());
        double value = 0.01;
        Map<TestSuiteFitnessFunction, Double> fitnesses = new LinkedHashMap<>();
        Map<TestSuiteFitnessFunction, Double> coverages = new LinkedHashMap<>();
        for (Class<?> criterion : CRITERIA) {
            TestSuiteFitnessFunction function = mockOf(criterion);
            fitnesses.put(function, value += 0.01);
            coverages.put(function, value += 0.01);
        }
        suite.setFitnessValues(fitnesses);
        suite.setCoverageValues(coverages);

        SearchStatistics statistics = SearchStatistics.getInstance();
        statistics.currentIndividual(suite);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("FitnessTimeline", suite.getFitness());
        expected.put("CoverageTimeline", suite.getCoverage());
        expected.put("SizeTimeline", suite.size());
        expected.put("LengthTimeline", suite.totalLengthOfTestCases());
        expected.put("IBranchGoalsTimeline", suite.getNumOfNotCoveredGoals());
        expected.put("RhoScoreTimeline", 0.0);
        expected.put("AmbiguityScoreTimeline", 0.0);
        expected.put("BranchCoverageTimeline", suite.getCoverageInstanceOf(BranchCoverageSuiteFitness.class));
        expected.put("OnlyBranchFitnessTimeline", suite.getFitnessInstanceOf(OnlyBranchCoverageSuiteFitness.class));
        expected.put("OnlyBranchCoverageTimeline",
                suite.getCoverageInstanceOf(OnlyBranchCoverageSuiteFitness.class));
        expected.put("CBranchFitnessTimeline", suite.getFitnessInstanceOf(CBranchSuiteFitness.class));
        expected.put("CBranchCoverageTimeline", suite.getCoverageInstanceOf(CBranchSuiteFitness.class));
        expected.put("MethodTraceFitnessTimeline", suite.getFitnessInstanceOf(MethodTraceCoverageSuiteFitness.class));
        expected.put("MethodTraceCoverageTimeline",
                suite.getCoverageInstanceOf(MethodTraceCoverageSuiteFitness.class));
        expected.put("MethodFitnessTimeline", suite.getFitnessInstanceOf(MethodCoverageSuiteFitness.class));
        expected.put("MethodCoverageTimeline", suite.getCoverageInstanceOf(MethodCoverageSuiteFitness.class));
        expected.put("MethodNoExceptionFitnessTimeline",
                suite.getFitnessInstanceOf(MethodNoExceptionCoverageSuiteFitness.class));
        expected.put("MethodNoExceptionCoverageTimeline",
                suite.getCoverageInstanceOf(MethodNoExceptionCoverageSuiteFitness.class));
        expected.put("LineFitnessTimeline", suite.getFitnessInstanceOf(LineCoverageSuiteFitness.class));
        expected.put("LineCoverageTimeline", suite.getCoverageInstanceOf(LineCoverageSuiteFitness.class));
        expected.put("OutputFitnessTimeline", suite.getFitnessInstanceOf(OutputCoverageSuiteFitness.class));
        expected.put("OutputCoverageTimeline", suite.getCoverageInstanceOf(OutputCoverageSuiteFitness.class));
        expected.put("InputFitnessTimeline", suite.getFitnessInstanceOf(InputCoverageSuiteFitness.class));
        expected.put("InputCoverageTimeline", suite.getCoverageInstanceOf(InputCoverageSuiteFitness.class));
        expected.put("ExceptionFitnessTimeline", suite.getFitnessInstanceOf(ExceptionCoverageSuiteFitness.class));
        expected.put("ExceptionCoverageTimeline", suite.getCoverageInstanceOf(ExceptionCoverageSuiteFitness.class));
        expected.put("WeakMutationCoverageTimeline", suite.getCoverageInstanceOf(WeakMutationSuiteFitness.class));
        expected.put("OnlyMutationFitnessTimeline", suite.getFitnessInstanceOf(OnlyMutationSuiteFitness.class));
        expected.put("OnlyMutationCoverageTimeline", suite.getCoverageInstanceOf(OnlyMutationSuiteFitness.class));

        Map<String, SequenceOutputVariableFactory<?>> timelines = field(statistics, "sequenceOutputVariableFactories");
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            SequenceOutputVariableFactory<?> factory = timelines.get(entry.getKey());
            List<?> values = field(factory, "values");
            assertEquals(1, values.size(), entry.getKey());
            assertEquals(entry.getValue(), values.get(0), entry.getKey());
        }

        Map<String, ChromosomeOutputVariableFactory<?>> suiteVariables = field(statistics, "variableFactories");
        assertEquals(suite.totalLengthOfTestCases(), suiteVariables.get("Length").getVariable(suite).getValue());
        assertEquals(suite.size(), suiteVariables.get("Size").getVariable(suite).getValue());
        assertEquals(suite.getCoverage(), suiteVariables.get("Coverage").getVariable(suite).getValue());
        assertEquals(suite.getFitness(), suiteVariables.get("Fitness").getVariable(suite).getValue());
    }

    private static TestSuiteFitnessFunction mockOf(Class<?> criterion) {
        return (TestSuiteFitnessFunction) Mockito.mock(criterion);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (true) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
    }
}
