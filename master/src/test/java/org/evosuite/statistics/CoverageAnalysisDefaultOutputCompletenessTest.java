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

import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.junit.CoverageAnalysis;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the contract for the standalone {@code -measureCoverage} (SELECTED_JUNIT) path: an
 * incomplete coverage-analysis sweep never has its {@link RuntimeVariable#Total_Goals}, {@link
 * RuntimeVariable#Covered_Goals} or {@link RuntimeVariable#Coverage} published as if the sweep
 * had finished, and a row written for an incomplete sweep keeps the same column order and field
 * count as one written for a complete sweep under the same {@code -Doutput_variables} selection
 * -- including the default selection and selections that omit one or both goal counters. Paths
 * other than the guarded {@code publishDirectCoverageSweepResult} (e.g. {@link
 * org.evosuite.statistics.StatisticsSender#executedAndThenSendIndividualToMaster}) can still put
 * a stale, per-criterion {@link RuntimeVariable#Coverage}/{@link RuntimeVariable#Covered_Goals}
 * into the written map; {@link SearchStatistics#writeStatisticsForAnalysis()} is the one place
 * that must consult {@link RuntimeVariable#CoverageAnalysisComplete} before writing the row
 * regardless of how the map got there.
 *
 * <p>This test drives the real {@link org.evosuite.statistics.StatisticsSender}, the real
 * (private, invoked here via reflection) {@code publishDirectCoverageSweepResult}, and the real
 * {@link SearchStatistics#writeStatisticsForAnalysis()} with a real {@link
 * org.evosuite.statistics.backend.CSVStatisticsBackend} writing to a scratch report directory, in
 * the same order {@code CoverageAnalysis.analyzeCoverage()}'s SELECTED_JUNIT branch calls them.
 * The suite/completeness state driving each call is synthetic -- {@link #runSweep(boolean)} sets
 * it directly rather than running the analyzer's actual per-goal sweep. {@code
 * Properties.CLIENT_ON_THREAD} makes {@link ClientNodeImpl} forward statistics synchronously to
 * the real {@link SearchStatistics} instance in this same JVM, without needing an RMI registry.
 */
public class CoverageAnalysisDefaultOutputCompletenessTest {

    @TempDir
    File reportDir;

    private ClientNodeImpl<?> previousClientNode;
    private ClientNodeImpl<TestSuiteChromosome> client;
    private boolean previousClientOnThread;
    private boolean previousNewStatistics;
    private Properties.StatisticsBackend previousBackend;
    private String previousOutputVariables;
    private String previousReportDir;

    @BeforeEach
    public void setUp() throws Exception {
        SearchStatistics.clearAllInstances();
        resetCoverageAnalysisProgress();

        previousClientOnThread = Properties.CLIENT_ON_THREAD;
        previousNewStatistics = Properties.NEW_STATISTICS;
        previousBackend = Properties.STATISTICS_BACKEND;
        previousOutputVariables = Properties.OUTPUT_VARIABLES;
        previousReportDir = Properties.REPORT_DIR;

        Properties.CLIENT_ON_THREAD = true;
        Properties.NEW_STATISTICS = true;
        Properties.OUTPUT_VARIABLES = null;
        Properties.STATISTICS_BACKEND = Properties.StatisticsBackend.CSV;
        Properties.REPORT_DIR = reportDir.getAbsolutePath();

        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        previousClientNode = (ClientNodeImpl<?>) clientNodeField.get(ClientServices.getInstance());
        // registry=null -> ensureMasterNode() fails -> with CLIENT_ON_THREAD, trackOutputVariable
        // and updateStatistics fall back to calling SearchStatistics directly in this JVM, the
        // same code path a real client uses when the master connection is not (yet) available.
        client = new ClientNodeImpl<>(null, ClientProcess.DEFAULT_CLIENT_NAME);
        clientNodeField.set(ClientServices.getInstance(), client);

        // SearchStatistics.getInstance() must be created (and pick up STATISTICS_BACKEND) only
        // after clientNode/Properties are set up above.
        SearchStatistics.getInstance();
    }

    @AfterEach
    public void tearDown() throws Exception {
        resetCoverageAnalysisProgress();
        CoverageAnalysis.resetMeasurementFinished();

        Properties.CLIENT_ON_THREAD = previousClientOnThread;
        Properties.NEW_STATISTICS = previousNewStatistics;
        Properties.STATISTICS_BACKEND = previousBackend;
        Properties.OUTPUT_VARIABLES = previousOutputVariables;
        Properties.REPORT_DIR = previousReportDir;

        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        if (client != null) {
            client.stop();
        }
        if (previousClientNode != null) {
            clientNodeField.set(ClientServices.getInstance(), previousClientNode);
        }
        SearchStatistics.clearAllInstances();
    }

    @Test
    public void incompleteSelectedJunitSweepLeavesDefaultCsvAggregatesUnavailable() throws Exception {
        // Simulate runDirectCoverageSweep's own OOM catch: an earlier criterion's
        // TestSuiteFitnessFunction#getFitness(suite) call already left its coverage average on
        // the suite, and some goals were already covered, before a later configured criterion was
        // never reached.
        CoverageCriteriaAnalyzer.markCoverageAnalysisIncomplete();

        TestSuiteChromosome testSuite = new TestSuiteChromosome();
        TestSuiteFitnessFunction earlierCriterion = mock(TestSuiteFitnessFunction.class);
        testSuite.setCoverage(earlierCriterion, 0.75);

        // Production order from CoverageAnalysis.analyzeCoverage()'s SELECTED_JUNIT branch:
        // StatisticsSender runs first, then the guarded publisher (which correctly withholds
        // Total_Goals/Covered_Goals/Coverage for an incomplete sweep -- that part already works).
        StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
        invokePublishDirectCoverageSweepResult(testSuite, 2);

        boolean wrote = SearchStatistics.getInstance().writeStatisticsForAnalysis();
        assertTrue(wrote, "writeStatisticsForAnalysis() must still write a row for this run");

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(2, lines.size(), "expected a header line and exactly one data row");

        String[] header = lines.get(0).split(",", -1);
        String[] data = lines.get(1).split(",", -1);

        String coverage = columnValue(header, data, RuntimeVariable.Coverage.toString());
        String totalGoals = columnValue(header, data, RuntimeVariable.Total_Goals.toString());
        String coveredGoals = columnValue(header, data, RuntimeVariable.Covered_Goals.toString());

        assertFalse("0.75".equals(coverage),
                "the default CSV must not export the suite's stale per-criterion Coverage "
                        + "average for an incomplete sweep; got Coverage=" + coverage);
        assertTrue(totalGoals == null || totalGoals.trim().isEmpty(),
                "the default CSV must not export a goal total for an incomplete sweep; got "
                        + "Total_Goals=" + totalGoals);
        assertTrue(coveredGoals == null || coveredGoals.trim().isEmpty(),
                "the default CSV must not export a covered-goal count (sent unconditionally by "
                        + "StatisticsSender) while the goal total is withheld; got Covered_Goals="
                        + coveredGoals);
    }

    /**
     * Companion to the incomplete-sweep test above: a genuinely complete sweep must keep
     * reporting its ordinary aggregates exactly as before this fix -- the new completeness gate
     * in {@code writeStatisticsForAnalysis()} must only change behavior for the explicit
     * incomplete case, not normal, successful analysis runs. Zero configured goals exercises the
     * existing "vacuous full coverage" convention ({@code goals == 0 -> Coverage = 1.0}, also
     * covered by {@code SearchStatisticsTest#testSuccessfulZeroGoalsKeepsVacuousFullCoverage}), so
     * this also checks the fix did not disturb that unrelated convention.
     */
    @Test
    public void completeSelectedJunitSweepStillExportsOrdinaryAggregates() throws Exception {
        TestSuiteChromosome testSuite = new TestSuiteChromosome();

        // CoverageCriteriaAnalyzer.isCoverageAnalysisComplete() defaults to true; nothing in this
        // test marks it incomplete, matching a sweep that measured every configured criterion.
        StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
        invokePublishDirectCoverageSweepResult(testSuite, 0);

        boolean wrote = SearchStatistics.getInstance().writeStatisticsForAnalysis();
        assertTrue(wrote, "writeStatisticsForAnalysis() must still write a row for this run");

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(2, lines.size(), "expected a header line and exactly one data row");

        String[] header = lines.get(0).split(",", -1);
        String[] data = lines.get(1).split(",", -1);

        assertEquals("1.0", columnValue(header, data, RuntimeVariable.Coverage.toString()));
        assertEquals("0", columnValue(header, data, RuntimeVariable.Total_Goals.toString()));
        assertEquals("0", columnValue(header, data, RuntimeVariable.Covered_Goals.toString()));
    }

    /**
     * The two single-row tests above each write to a fresh, empty file, so neither can see the
     * shared-file schema contract: {@link SearchStatistics#writeStatisticsForAnalysis()} and
     * {@link org.evosuite.statistics.backend.CSVStatisticsBackend} write a header only for the
     * first row of a report file, then append every later row in that row's own {@link
     * java.util.LinkedHashMap} key order without realigning it to the header already on disk, so
     * every row appended to one file must share the same key set and order regardless of
     * completeness. This test calls {@link #runSweep(boolean)} twice -- once complete, once
     * incomplete -- against the same report directory, so both rows share one header, and reads
     * every value back by column name against that one header rather than by position. {@code
     * runSweep} sets synthetic suite/completeness state and invokes the real reporting calls; it
     * does not run the analyzer's actual per-goal sweep.
     */
    @Test
    public void appendedRowsKeepIdenticalColumnOrderCompleteThenIncomplete() throws Exception {
        runSweep(false);
        runSweep(true);

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(3, lines.size(), "expected one shared header line and two data rows");

        assertEquals("TARGET_CLASS,criterion,Test_Generation_Status,Coverage,Total_Goals,Covered_Goals",
                lines.get(0), "default essential output variables fix this column order");

        String[] header = lines.get(0).split(",", -1);
        String[] completeRow = lines.get(1).split(",", -1);
        String[] incompleteRow = lines.get(2).split(",", -1);

        assertEquals(header.length, completeRow.length,
                "the complete row must have exactly as many columns as the shared header");
        assertEquals(header.length, incompleteRow.length,
                "the incomplete row must have exactly as many columns as the shared header, not "
                        + "shift its unavailable-Coverage value into Covered_Goals' column");

        assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
        assertEquals("0", columnValue(header, completeRow, RuntimeVariable.Total_Goals.toString()));
        assertEquals("0", columnValue(header, completeRow, RuntimeVariable.Covered_Goals.toString()));

        assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()),
                "an incomplete sweep's Coverage column must hold the CSV backend's own "
                        + "unavailable-coverage value, read back by name");
        assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.Total_Goals.toString())));
        assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.Covered_Goals.toString())));
    }

    /** Companion to the test above, with the two sweeps in the opposite order. */
    @Test
    public void appendedRowsKeepIdenticalColumnOrderIncompleteThenComplete() throws Exception {
        runSweep(true);
        runSweep(false);

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(3, lines.size(), "expected one shared header line and two data rows");

        assertEquals("TARGET_CLASS,criterion,Test_Generation_Status,Coverage,Total_Goals,Covered_Goals",
                lines.get(0), "the header must not depend on which run wrote the first row");

        String[] header = lines.get(0).split(",", -1);
        String[] incompleteRow = lines.get(1).split(",", -1);
        String[] completeRow = lines.get(2).split(",", -1);

        assertEquals(header.length, incompleteRow.length,
                "the incomplete row must have exactly as many columns as the shared header");
        assertEquals(header.length, completeRow.length,
                "the complete row must have exactly as many columns as the shared header, not "
                        + "read its Total_Goals/Covered_Goals out of Coverage's column");

        assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()),
                "an incomplete sweep's Coverage column must hold the CSV backend's own "
                        + "unavailable-coverage value, read back by name");
        assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.Total_Goals.toString())));
        assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.Covered_Goals.toString())));

        assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()),
                "a complete sweep's row must stay exactly as before this fix, even though an "
                        + "incomplete row with a different column order was appended ahead of it");
        assertEquals("0", columnValue(header, completeRow, RuntimeVariable.Total_Goals.toString()));
        assertEquals("0", columnValue(header, completeRow, RuntimeVariable.Covered_Goals.toString()));
    }

    /**
     * Rereview7 finding 1: a custom {@code -Doutput_variables} selection that omits both goal
     * counters must still get the same column count on every row, whatever the completeness of
     * each sweep. {@code TARGET_CLASS,Coverage} selects neither {@link RuntimeVariable#Total_Goals}
     * nor {@link RuntimeVariable#Covered_Goals}, so {@code withholdAggregatesForIncompleteCoverageAnalysis}
     * must not insert either for the incomplete row.
     */
    @Test
    public void customSelectionOmittingBothGoalColumnsCompleteThenIncomplete() throws Exception {
        Properties.OUTPUT_VARIABLES = "TARGET_CLASS,Coverage";

        runSweep(false);
        runSweep(true);

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(3, lines.size(), "expected one shared header line and two data rows");
        assertEquals("TARGET_CLASS,Coverage", lines.get(0),
                "the header must be exactly the requested selection, nothing inserted");

        String[] header = lines.get(0).split(",", -1);
        String[] completeRow = lines.get(1).split(",", -1);
        String[] incompleteRow = lines.get(2).split(",", -1);

        assertEquals(header.length, completeRow.length,
                "the complete row must have exactly as many fields as the two-column header");
        assertEquals(header.length, incompleteRow.length,
                "the incomplete row must not grow extra fields for goal counters nobody selected");

        assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
        assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
    }

    /** Companion to the test above, with the two sweeps in the opposite order. */
    @Test
    public void customSelectionOmittingBothGoalColumnsIncompleteThenComplete() throws Exception {
        Properties.OUTPUT_VARIABLES = "TARGET_CLASS,Coverage";

        runSweep(true);
        runSweep(false);

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(3, lines.size(), "expected one shared header line and two data rows");
        assertEquals("TARGET_CLASS,Coverage", lines.get(0),
                "the header must not depend on which run wrote the first row");

        String[] header = lines.get(0).split(",", -1);
        String[] incompleteRow = lines.get(1).split(",", -1);
        String[] completeRow = lines.get(2).split(",", -1);

        assertEquals(header.length, incompleteRow.length,
                "the incomplete row must not invent columns nobody selected");
        assertEquals(header.length, completeRow.length,
                "the complete row must not come up short against a header the incomplete row "
                        + "inflated");

        assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
        assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
    }

    /**
     * Same defect, with a selection that keeps one goal counter ({@link
     * RuntimeVariable#Total_Goals}) but omits the other ({@link RuntimeVariable#Covered_Goals}).
     * The kept counter must still be blanked for the incomplete row; the omitted one must never
     * appear.
     */
    @Test
    public void customSelectionOmittingOneGoalColumnCompleteThenIncomplete() throws Exception {
        Properties.OUTPUT_VARIABLES = "TARGET_CLASS,Coverage,Total_Goals";

        runSweep(false);
        runSweep(true);

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(3, lines.size(), "expected one shared header line and two data rows");
        assertEquals("TARGET_CLASS,Coverage,Total_Goals", lines.get(0),
                "the header must be exactly the requested selection, nothing inserted");

        String[] header = lines.get(0).split(",", -1);
        String[] completeRow = lines.get(1).split(",", -1);
        String[] incompleteRow = lines.get(2).split(",", -1);

        assertEquals(header.length, completeRow.length,
                "the complete row must have exactly as many fields as the three-column header");
        assertEquals(header.length, incompleteRow.length,
                "the incomplete row must not grow a fourth field for the unselected Covered_Goals");

        assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
        assertEquals("0", columnValue(header, completeRow, RuntimeVariable.Total_Goals.toString()));

        assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
        assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.Total_Goals.toString())),
                "the selected Total_Goals counter must be blanked, not left as a stale value");
    }

    /** Companion to the test above, with the two sweeps in the opposite order. */
    @Test
    public void customSelectionOmittingOneGoalColumnIncompleteThenComplete() throws Exception {
        Properties.OUTPUT_VARIABLES = "TARGET_CLASS,Coverage,Total_Goals";

        runSweep(true);
        runSweep(false);

        List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
        assertEquals(3, lines.size(), "expected one shared header line and two data rows");
        assertEquals("TARGET_CLASS,Coverage,Total_Goals", lines.get(0),
                "the header must not depend on which run wrote the first row");

        String[] header = lines.get(0).split(",", -1);
        String[] incompleteRow = lines.get(1).split(",", -1);
        String[] completeRow = lines.get(2).split(",", -1);

        assertEquals(header.length, incompleteRow.length,
                "the incomplete row must not invent a fourth field for the unselected "
                        + "Covered_Goals");
        assertEquals(header.length, completeRow.length,
                "the complete row must not come up short against a header the incomplete row "
                        + "inflated");

        assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
        assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.Total_Goals.toString())));

        assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
        assertEquals("0", columnValue(header, completeRow, RuntimeVariable.Total_Goals.toString()));
    }

    /**
     * Rereview8 finding 1: with a single {@code BRANCH} criterion, {@link
     * SearchStatistics#writeStatisticsForAnalysis()} inserts {@link
     * RuntimeVariable#BranchCoverage} only on the complete-sweep path ({@code
     * normalizeCriterionCoverage}), never on the incomplete one, so the two outcomes produce
     * different schemas -- a 7-field header/row for a complete sweep and a 6-field one for an
     * incomplete sweep, under the very default selection {@link
     * #appendedRowsKeepIdenticalColumnOrderCompleteThenIncomplete()} already covers for the
     * multi-criterion default, which never exercises this single-{@code BRANCH} fallback.
     * {@code Properties.CRITERION} is saved and restored around the sweep so this does not leak
     * into other tests in this class or elsewhere.
     */
    @Test
    public void branchOnlyCriterionDefaultOutputCompleteThenIncomplete() throws Exception {
        Criterion[] previousCriterion = Properties.CRITERION;
        try {
            Properties.CRITERION = new Criterion[]{Criterion.BRANCH};

            runSweep(false);
            runSweep(true);

            List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
            assertEquals(3, lines.size(), "expected one shared header line and two data rows");
            assertEquals(
                    "TARGET_CLASS,criterion,Test_Generation_Status,Coverage,Total_Goals,Covered_Goals,BranchCoverage",
                    lines.get(0),
                    "a single BRANCH criterion's fallback adds BranchCoverage to the default columns");

            String[] header = lines.get(0).split(",", -1);
            String[] completeRow = lines.get(1).split(",", -1);
            String[] incompleteRow = lines.get(2).split(",", -1);

            assertEquals(header.length, completeRow.length,
                    "the complete row must have exactly as many columns as the shared header");
            assertEquals(header.length, incompleteRow.length,
                    "the incomplete row must gain the same BranchCoverage column as the complete "
                            + "row, not fall one field short of the header it shares a file with");

            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.BranchCoverage.toString()),
                    "a complete single-BRANCH sweep must still publish BranchCoverage exactly as "
                            + "before this fix");

            assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
            assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.BranchCoverage.toString())),
                    "an incomplete single-BRANCH sweep must not synthesize a BranchCoverage number");
        } finally {
            Properties.CRITERION = previousCriterion;
        }
    }

    /** Companion to the test above, with the two sweeps in the opposite order. */
    @Test
    public void branchOnlyCriterionDefaultOutputIncompleteThenComplete() throws Exception {
        Criterion[] previousCriterion = Properties.CRITERION;
        try {
            Properties.CRITERION = new Criterion[]{Criterion.BRANCH};

            runSweep(true);
            runSweep(false);

            List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
            assertEquals(3, lines.size(), "expected one shared header line and two data rows");
            assertEquals(
                    "TARGET_CLASS,criterion,Test_Generation_Status,Coverage,Total_Goals,Covered_Goals,BranchCoverage",
                    lines.get(0),
                    "the header must not depend on which run (complete or incomplete) wrote first");

            String[] header = lines.get(0).split(",", -1);
            String[] incompleteRow = lines.get(1).split(",", -1);
            String[] completeRow = lines.get(2).split(",", -1);

            assertEquals(header.length, incompleteRow.length,
                    "the incomplete row must not fall short of a header an earlier incomplete "
                            + "sweep itself established with the BranchCoverage column");
            assertEquals(header.length, completeRow.length,
                    "the complete row must not shift its BranchCoverage value out of its column "
                            + "because an incomplete row was appended ahead of it");

            assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
            assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.BranchCoverage.toString())));

            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.BranchCoverage.toString()),
                    "a complete single-BRANCH sweep must still publish BranchCoverage exactly as "
                            + "before this fix");
        } finally {
            Properties.CRITERION = previousCriterion;
        }
    }

    /**
     * Same BRANCH-only fallback, but with a custom {@code -Doutput_variables} selection that never
     * named {@link RuntimeVariable#BranchCoverage}: the fallback still adds it to both the complete
     * and incomplete rows (never only to the complete one), so the four-field schema stays fixed
     * regardless of completeness.
     */
    @Test
    public void branchOnlyCriterionCustomSelectionCompleteThenIncomplete() throws Exception {
        Criterion[] previousCriterion = Properties.CRITERION;
        try {
            Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
            Properties.OUTPUT_VARIABLES = "TARGET_CLASS,criterion,Coverage";

            runSweep(false);
            runSweep(true);

            List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
            assertEquals(3, lines.size(), "expected one shared header line and two data rows");
            assertEquals("TARGET_CLASS,criterion,Coverage,BranchCoverage", lines.get(0),
                    "the BRANCH fallback appends BranchCoverage even though the selection did "
                            + "not name it");

            String[] header = lines.get(0).split(",", -1);
            String[] completeRow = lines.get(1).split(",", -1);
            String[] incompleteRow = lines.get(2).split(",", -1);

            assertEquals(header.length, completeRow.length,
                    "the complete row must have exactly as many fields as the four-column header");
            assertEquals(header.length, incompleteRow.length,
                    "the incomplete row must gain the same BranchCoverage column as the complete "
                            + "row");

            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.BranchCoverage.toString()));

            assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
            assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.BranchCoverage.toString())),
                    "an incomplete single-BRANCH sweep must not synthesize a BranchCoverage number "
                            + "even though the selection never named that column");
        } finally {
            Properties.CRITERION = previousCriterion;
        }
    }

    /** Companion to the test above, with the two sweeps in the opposite order. */
    @Test
    public void branchOnlyCriterionCustomSelectionIncompleteThenComplete() throws Exception {
        Criterion[] previousCriterion = Properties.CRITERION;
        try {
            Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
            Properties.OUTPUT_VARIABLES = "TARGET_CLASS,criterion,Coverage";

            runSweep(true);
            runSweep(false);

            List<String> lines = Files.readAllLines(new File(reportDir, "statistics.csv").toPath());
            assertEquals(3, lines.size(), "expected one shared header line and two data rows");
            assertEquals("TARGET_CLASS,criterion,Coverage,BranchCoverage", lines.get(0),
                    "the header must not depend on which run wrote the first row");

            String[] header = lines.get(0).split(",", -1);
            String[] incompleteRow = lines.get(1).split(",", -1);
            String[] completeRow = lines.get(2).split(",", -1);

            assertEquals(header.length, incompleteRow.length,
                    "the incomplete row must not fall short of a header it established itself");
            assertEquals(header.length, completeRow.length,
                    "the complete row must not come up short against a header the incomplete row "
                            + "established");

            assertEquals("N/A", columnValue(header, incompleteRow, RuntimeVariable.Coverage.toString()));
            assertTrue(isBlank(columnValue(header, incompleteRow, RuntimeVariable.BranchCoverage.toString())));

            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.Coverage.toString()));
            assertEquals("1.0", columnValue(header, completeRow, RuntimeVariable.BranchCoverage.toString()));
        } finally {
            Properties.CRITERION = previousCriterion;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Runs one full sweep -- complete or incomplete -- through the same production call sequence
     * as the two single-row tests above ({@link StatisticsSender#executedAndThenSendIndividualToMaster},
     * the guarded publisher, then {@link SearchStatistics#writeStatisticsForAnalysis()}) and
     * appends its row to the shared {@code reportDir}. Each call resets {@link SearchStatistics}
     * and the coverage-analysis progress tracker and installs a fresh {@link ClientNodeImpl}, the
     * same way a fresh client process would for each run, without touching {@code
     * Properties.REPORT_DIR} so the CSV file -- and its header -- carries over between calls.
     */
    private void runSweep(boolean incomplete) throws Exception {
        SearchStatistics.clearAllInstances();
        resetCoverageAnalysisProgress();

        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        if (client != null) {
            client.stop();
        }
        client = new ClientNodeImpl<>(null, ClientProcess.DEFAULT_CLIENT_NAME);
        clientNodeField.set(ClientServices.getInstance(), client);

        SearchStatistics.getInstance();

        TestSuiteChromosome testSuite = new TestSuiteChromosome();
        int goals;
        if (incomplete) {
            CoverageCriteriaAnalyzer.markCoverageAnalysisIncomplete();
            TestSuiteFitnessFunction earlierCriterion = mock(TestSuiteFitnessFunction.class);
            testSuite.setCoverage(earlierCriterion, 0.75);
            goals = 2;
        } else {
            goals = 0;
        }

        StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
        invokePublishDirectCoverageSweepResult(testSuite, goals);

        boolean wrote = SearchStatistics.getInstance().writeStatisticsForAnalysis();
        assertTrue(wrote, "writeStatisticsForAnalysis() must still write a row for this run");
    }

    private static String columnValue(String[] header, String[] data, String column) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(column)) {
                return i < data.length ? data[i] : "";
            }
        }
        return null;
    }

    private static void invokePublishDirectCoverageSweepResult(TestSuiteChromosome testSuite, int goals)
            throws Exception {
        Method m = CoverageAnalysis.class.getDeclaredMethod(
                "publishDirectCoverageSweepResult", TestSuiteChromosome.class, int.class);
        m.setAccessible(true);
        m.invoke(null, testSuite, goals);
    }

    private static void resetCoverageAnalysisProgress() throws Exception {
        Method reset = CoverageCriteriaAnalyzer.class.getDeclaredMethod("resetCoverageAnalysisProgress");
        reset.setAccessible(true);
        reset.invoke(null);
    }
}
