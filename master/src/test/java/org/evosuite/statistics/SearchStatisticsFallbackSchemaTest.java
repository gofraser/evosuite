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
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.MasterNodeImpl;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchStatisticsFallbackSchemaTest {

    @TempDir
    Path reportDir;

    private Field masterNodeField;
    private Object previousMasterNode;

    @BeforeEach
    void setUp() throws Exception {
        SearchStatistics.clearAllInstances();
        Properties.STATISTICS_BACKEND = Properties.StatisticsBackend.CSV;
        Properties.REPORT_DIR = reportDir.toString();
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.LINE};
        Properties.IGNORE_MISSING_STATISTICS = false;
        Properties.IS_RUNNING_A_SYSTEM_TEST = false;

        // Model a master with no DONE clients, so missing statistics take the fallback
        // without the extra client-arrival retries or a live RMI registry.
        masterNodeField = MasterServices.class.getDeclaredField("masterNode");
        masterNodeField.setAccessible(true);
        previousMasterNode = masterNodeField.get(MasterServices.getInstance());
        masterNodeField.set(MasterServices.getInstance(), new MasterNodeImpl(null));
    }

    @AfterEach
    void tearDown() throws Exception {
        SearchStatistics.clearAllInstances();
        masterNodeField.set(MasterServices.getInstance(), previousMasterNode);
        Properties.getInstance().resetToDefaults();
    }

    @Test
    void missingStatisticDoesNotAddAnUnselectedStatusColumn() throws Exception {
        Properties.OUTPUT_VARIABLES = "TARGET_CLASS,JUnit_Check_Incomplete";

        List<String[]> rows = writeSuccessfulAndFallbackRows();

        assertFalse(Arrays.asList(rows.get(0)).contains("Test_Generation_Status"));
        assertEquals(rows.get(0).length, rows.get(1).length);
        assertEquals(rows.get(0).length, rows.get(2).length,
                "Fallback must preserve the successful row's CSV schema");
        assertEquals("successful.Target", rows.get(1)[0]);
        assertEquals("fallback.Target", rows.get(2)[0]);
        assertEquals("false", rows.get(1)[1]);
        assertEquals("", rows.get(2)[1]);
        assertFalse(Arrays.asList(rows.get(2)).contains("ERROR"));
        int coverageColumn = Arrays.asList(rows.get(0)).indexOf("Coverage");
        assertTrue(coverageColumn >= 0);
        assertEquals("N/A", rows.get(2)[coverageColumn],
                "The internal failure status must still prevent fictitious full coverage");
    }

    @Test
    void selectedStatusIsUpdatedInPlaceForMissingStatistics() throws Exception {
        Properties.OUTPUT_VARIABLES = "TARGET_CLASS,Test_Generation_Status,JUnit_Check_Incomplete";

        List<String[]> rows = writeSuccessfulAndFallbackRows();

        assertEquals("Test_Generation_Status", rows.get(0)[1]);
        assertEquals(rows.get(0).length, rows.get(1).length);
        assertEquals(rows.get(0).length, rows.get(2).length);
        assertEquals("SUCCESS", rows.get(1)[1]);
        assertEquals("ERROR", rows.get(2)[1]);
        assertEquals("", rows.get(2)[2]);
    }

    private List<String[]> writeSuccessfulAndFallbackRows() throws Exception {
        Properties.TARGET_CLASS = "successful.Target";
        SearchStatistics success = SearchStatistics.getInstance("successful");
        success.currentIndividual(new TestSuiteChromosome());
        success.setOutputVariable(RuntimeVariable.JUnit_Check_Incomplete, false);
        assertTrue(success.writeStatistics());

        Properties.TARGET_CLASS = "fallback.Target";
        SearchStatistics fallback = SearchStatistics.getInstance("fallback");
        fallback.currentIndividual(new TestSuiteChromosome());
        assertTrue(fallback.writeStatistics());

        List<String> lines = Files.readAllLines(reportDir.resolve("statistics.csv"));
        assertEquals(3, lines.size());
        return Arrays.asList(lines.get(0).split(",", -1), lines.get(1).split(",", -1),
                lines.get(2).split(",", -1));
    }
}
