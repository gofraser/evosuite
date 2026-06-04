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
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchStatisticsTest {

    @BeforeEach
    public void setUp() {
        SearchStatistics.clearAllInstances();
        Properties.STATISTICS_BACKEND = Properties.StatisticsBackend.DEBUG;
    }

    @AfterEach
    public void tearDown() {
        SearchStatistics.clearAllInstances();
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void test_setOutputVariable() {
        SearchStatistics statistics = SearchStatistics.getInstance();
        statistics.setOutputVariable(RuntimeVariable.DiversityTimeline, 0.42);
    }

    @Test
    public void testSuccessfulZeroGoalsKeepsVacuousFullCoverage() throws Exception {
        Map<String, OutputVariable<?>> map = outputVariables("SUCCESS", 0, 0, 0.0);

        Double coverage = normalizeGoalsAndCoverage(map);

        Assertions.assertEquals(1.0, coverage);
        Assertions.assertEquals(1.0, map.get(RuntimeVariable.Coverage.toString()).getValue());
    }

    @Test
    public void testErrorZeroGoalsDoesNotBecomeFullCoverage() throws Exception {
        Map<String, OutputVariable<?>> map = outputVariables("ERROR", 0, 0, 0.0);

        Double coverage = normalizeGoalsAndCoverage(map);

        Assertions.assertEquals(0.0, coverage);
        Assertions.assertEquals(0.0, map.get(RuntimeVariable.Coverage.toString()).getValue());
    }

    @Test
    public void testTimeoutWithMissingGoalCountsDoesNotBecomeFullCoverage() throws Exception {
        Map<String, OutputVariable<?>> map = new LinkedHashMap<>();
        map.put(RuntimeVariable.Test_Generation_Status.toString(),
                new OutputVariable<>(RuntimeVariable.Test_Generation_Status.toString(), "TIMEOUT"));
        map.put(RuntimeVariable.Coverage.toString(),
                new OutputVariable<>(RuntimeVariable.Coverage.toString(), 0.0));

        Double coverage = normalizeGoalsAndCoverage(map);

        Assertions.assertEquals(0.0, coverage);
        Assertions.assertEquals(0.0, map.get(RuntimeVariable.Coverage.toString()).getValue());
        Assertions.assertEquals(0, map.get(RuntimeVariable.Total_Goals.toString()).getValue());
        Assertions.assertEquals(0, map.get(RuntimeVariable.Covered_Goals.toString()).getValue());
    }

    @Test
    public void testCoveredGoalsRatioStillComputesCoverage() throws Exception {
        Map<String, OutputVariable<?>> map = outputVariables("SUCCESS", 3, 10, null);

        Double coverage = normalizeGoalsAndCoverage(map);

        Assertions.assertEquals(0.3, coverage);
        Assertions.assertEquals(0.3, map.get(RuntimeVariable.Coverage.toString()).getValue());
    }

    private static Map<String, OutputVariable<?>> outputVariables(String status, int coveredGoals,
                                                                  int totalGoals, Double coverage) {
        Map<String, OutputVariable<?>> map = new LinkedHashMap<>();
        map.put(RuntimeVariable.Test_Generation_Status.toString(),
                new OutputVariable<>(RuntimeVariable.Test_Generation_Status.toString(), status));
        map.put(RuntimeVariable.Covered_Goals.toString(),
                new OutputVariable<>(RuntimeVariable.Covered_Goals.toString(), coveredGoals));
        map.put(RuntimeVariable.Total_Goals.toString(),
                new OutputVariable<>(RuntimeVariable.Total_Goals.toString(), totalGoals));
        if (coverage != null) {
            map.put(RuntimeVariable.Coverage.toString(),
                    new OutputVariable<>(RuntimeVariable.Coverage.toString(), coverage));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Double normalizeGoalsAndCoverage(Map<String, OutputVariable<?>> map) throws Exception {
        Method method = SearchStatistics.class.getDeclaredMethod(
                "normalizeGoalsAndCoverage", Map.class, TestSuiteChromosome.class);
        method.setAccessible(true);
        return (Double) method.invoke(SearchStatistics.getInstance(), map, new TestSuiteChromosome());
    }
}
