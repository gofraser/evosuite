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
package org.evosuite.llm.search.operator;

import org.evosuite.Properties;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.GoalId;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link OperatorAttemptLog} and {@link OperatorAttemptRecord}: the
 * open/response/evaluated/admitted/survivors lifecycle, every derived
 * parse-status outcome, the statement multiset diff, the aggregate counters,
 * and that {@link OperatorAttemptLog#flush()} only ever writes when enabled.
 */
class OperatorAttemptLogTest {

    private String originalTargetClass;
    private String originalReportDir;
    private boolean originalEnabled;

    @BeforeEach
    void saveProperties() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalReportDir = Properties.REPORT_DIR;
        originalEnabled = Properties.LLM_OPERATOR_ATTEMPT_LOG_ENABLED;
        OperatorAttemptLog.resetInstance();
    }

    @AfterEach
    void restoreProperties() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.REPORT_DIR = originalReportDir;
        Properties.LLM_OPERATOR_ATTEMPT_LOG_ENABLED = originalEnabled;
        OperatorAttemptLog.resetInstance();
    }

    private static TestChromosome chromosome(int marker) {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, marker));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        return chromosome;
    }

    private static TestFitnessFunction goal(String name) {
        return new OperatorTargetTest.StubGoal(name);
    }

    private static String[] cols(OperatorAttemptRecord record) {
        return record.toCsvRow().split(",", -1);
    }

    // ---- full lifecycle ---------------------------------------------------------------

    @Test
    void openThroughSurvivorsRendersExpectedRow() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        TestChromosome parent = chromosome(5);
        TestFitnessFunction goal = goal("g1");
        OperatorTarget target = OperatorTarget.builder(parent, goal).distance(2.5).build();

        log.open("a1", 3, target, "SYNC", 1);

        TestCase offspringTestCase = parent.getTestCase().clone();
        TestChromosome offspring = new TestChromosome();
        offspring.setTestCase(offspringTestCase);

        TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(10, 2, 5, 123L);
        log.recordResponse("a1", TargetedResponse.Status.DELIVERED, true, true, 1, usage, offspringTestCase);
        log.recordEvaluated("a1", 1.0, true);
        log.recordAdmitted("a1", offspring, 3);
        log.recordSurvivors(Collections.singletonList(offspring), 3);
        log.recordSurvivors(Collections.singletonList(offspring), 7);

        OperatorAttemptRecord record = log.recordFor("a1");
        String[] c = cols(record);

        assertEquals("3", c[0], "generation");
        assertEquals("a1", c[1], "attempt_id");
        assertEquals("SYNC", c[2], "mode");
        assertEquals("MUTATION", c[3], "kind");
        assertEquals("1", c[4], "batch_size");
        assertEquals(String.valueOf(System.identityHashCode(parent)), c[5], "parent_hash");
        assertEquals("", c[6], "partner_hash absent for mutation");
        assertEquals(GoalId.of(goal), c[7], "goal_id");
        assertEquals("", c[8], "card_type absent");
        assertEquals("0", c[9], "hint_present default off");
        assertEquals("2.5", c[10], "distance_before");
        assertEquals("1.0", c[11], "distance_after");
        assertEquals("1", c[12], "target_hit");
        assertEquals("PARSED", c[13], "parse_status");
        assertEquals("1", c[14], "repair_turns");
        assertEquals("1", c[15], "statements_kept");
        assertEquals("0", c[16], "statements_added");
        assertEquals("0", c[17], "statements_removed");
        assertEquals("10", c[18], "input_tokens");
        assertEquals("2", c[19], "cached_input_tokens");
        assertEquals("5", c[20], "output_tokens");
        assertEquals("123", c[21], "latency_ms");
        assertEquals("1", c[22], "admitted");
        assertEquals("3", c[23], "admitted_generation");
        assertEquals("1", c[24], "survived_plus1");
        assertEquals("1", c[25], "survived_plus5");
    }

    @Test
    void crossoverTargetCarriesPartnerHash() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        TestChromosome parent = chromosome(1);
        TestChromosome partner = chromosome(2);
        OperatorTarget target = OperatorTarget.builder(parent, goal("g")).partner(partner).build();

        log.open("x1", 0, target, "ASYNC", 2);

        OperatorAttemptRecord record = log.recordFor("x1");
        String[] c = cols(record);
        assertEquals("CROSSOVER", c[3]);
        assertEquals(String.valueOf(System.identityHashCode(partner)), c[6]);
    }

    // ---- open() edge cases --------------------------------------------------------------

    @Test
    void openIgnoresNullEmptyAndDuplicateAttemptIds() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        OperatorTarget target = OperatorTarget.builder(chromosome(1), goal("g")).build();

        log.open(null, 0, target, "SYNC", 1);
        log.open("", 0, target, "SYNC", 1);
        assertEquals(0, log.getAttempts());

        log.open("dup", 1, target, "SYNC", 1);
        log.open("dup", 99, target, "SYNC", 9);

        assertEquals(1, log.getAttempts());
        assertEquals("1", cols(log.recordFor("dup"))[0], "the first open() call must win");
    }

    // ---- parse-status derivation ----------------------------------------------------------

    @Test
    void everyParseStatusOutcomeIsDerivedCorrectly() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        OperatorTarget target = OperatorTarget.builder(chromosome(1), goal("g")).build();
        TargetedResponse.CallUsage usage = TargetedResponse.CallUsage.NONE;

        log.open("noBudget", 0, target, "SYNC", 1);
        log.recordResponse("noBudget", TargetedResponse.Status.NO_BUDGET, false, false, 0, usage, null);
        assertEquals("NO_BUDGET", cols(log.recordFor("noBudget"))[13]);

        log.open("failed", 0, target, "SYNC", 1);
        log.recordResponse("failed", TargetedResponse.Status.FAILED, false, false, 0, usage, null);
        assertEquals("FAILED", cols(log.recordFor("failed"))[13]);

        log.open("parseFailed", 0, target, "SYNC", 1);
        log.recordResponse("parseFailed", TargetedResponse.Status.DELIVERED, false, false, 2, usage, null);
        assertEquals("PARSE_FAILED", cols(log.recordFor("parseFailed"))[13]);

        log.open("unmatched", 0, target, "SYNC", 1);
        log.recordResponse("unmatched", TargetedResponse.Status.DELIVERED, true, false, 1, usage, null);
        assertEquals("UNMATCHED", cols(log.recordFor("unmatched"))[13]);

        log.open("parsed", 0, target, "SYNC", 1);
        log.recordResponse("parsed", TargetedResponse.Status.DELIVERED, true, true, 1, usage, new DefaultTestCase());
        assertEquals("PARSED", cols(log.recordFor("parsed"))[13]);

        log.open("undelivered", 0, target, "SYNC", 1);
        log.recordUndelivered("undelivered");
        String[] undeliveredCols = cols(log.recordFor("undelivered"));
        assertEquals("UNDELIVERED", undeliveredCols[13]);
        assertEquals("", undeliveredCols[14], "repair_turns must stay empty when never delivered");
        assertEquals("", undeliveredCols[18], "input_tokens must stay empty when never delivered");
    }

    @Test
    void recordUndeliveredDoesNotOverwriteAnAlreadyRecordedResponse() {
        // A parsed candidate stays open in the ASYNC executor until the GA reports its outcome;
        // shutdown() then marks every still-open attempt undelivered, including this one. That
        // must not clobber the response it already got.
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        OperatorTarget target = OperatorTarget.builder(chromosome(1), goal("g")).build();
        TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(10, 2, 5, 123L);
        log.open("parsedThenShutdown", 0, target, "SYNC", 1);
        log.recordResponse("parsedThenShutdown", TargetedResponse.Status.DELIVERED, true, true, 1, usage,
                new DefaultTestCase());

        log.recordUndelivered("parsedThenShutdown");

        String[] c = cols(log.recordFor("parsedThenShutdown"));
        assertEquals("PARSED", c[13], "a parsed row must not be downgraded to UNDELIVERED");
        assertEquals("1", c[14], "repair_turns must be kept");
        assertEquals("10", c[18], "input_tokens must be kept");
    }

    @Test
    void recordResponseWithoutOffspringLeavesStatementColumnsEmpty() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        OperatorTarget target = OperatorTarget.builder(chromosome(1), goal("g")).build();
        log.open("noOffspring", 0, target, "SYNC", 1);

        log.recordResponse("noOffspring", TargetedResponse.Status.DELIVERED, true, false, 0,
                TargetedResponse.CallUsage.NONE, null);

        String[] c = cols(log.recordFor("noOffspring"));
        assertEquals("", c[15]);
        assertEquals("", c[16]);
        assertEquals("", c[17]);
    }

    @Test
    void recordResponseUnknownAttemptIdIsANoOp() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        log.recordResponse("never-opened", TargetedResponse.Status.FAILED, false, false, 0,
                TargetedResponse.CallUsage.NONE, null);
        log.recordUndelivered("never-opened");
        log.recordEvaluated("never-opened", 1.0, true);
        log.recordAdmitted("never-opened", chromosome(1), 1);

        assertEquals(0, log.getAttempts());
    }

    // ---- statement multiset diff --------------------------------------------------------

    @Test
    void diffStatementCodesComputesMultisetKeptAddedRemoved() {
        List<String> before = Arrays.asList("a", "a", "b");
        List<String> after = Arrays.asList("a", "b", "b", "c");

        int[] diff = OperatorAttemptRecord.diffStatementCodes(before, after);

        assertEquals(2, diff[0], "kept");
        assertEquals(2, diff[1], "added");
        assertEquals(1, diff[2], "removed");
    }

    @Test
    void diffStatementCodesHandlesIdenticalAndEmptyLists() {
        assertEquals(0, OperatorAttemptRecord.diffStatementCodes(
                Collections.emptyList(), Collections.emptyList())[0]);

        int[] identical = OperatorAttemptRecord.diffStatementCodes(
                Arrays.asList("x", "y"), Arrays.asList("x", "y"));
        assertEquals(2, identical[0]);
        assertEquals(0, identical[1]);
        assertEquals(0, identical[2]);

        int[] allAdded = OperatorAttemptRecord.diffStatementCodes(
                Collections.emptyList(), Collections.singletonList("x"));
        assertEquals(0, allAdded[0]);
        assertEquals(1, allAdded[1]);
        assertEquals(0, allAdded[2]);

        int[] allRemoved = OperatorAttemptRecord.diffStatementCodes(
                Collections.singletonList("x"), Collections.emptyList());
        assertEquals(0, allRemoved[0]);
        assertEquals(0, allRemoved[1]);
        assertEquals(1, allRemoved[2]);
    }

    // ---- counters -------------------------------------------------------------------------

    @Test
    void countersAggregateAcrossAttempts() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        OperatorTarget mutationTarget = OperatorTarget.builder(chromosome(1), goal("g")).distance(2.0).build();
        OperatorTarget crossoverTarget = OperatorTarget.builder(chromosome(1), goal("g"))
                .partner(chromosome(2)).distance(2.0).build();

        // A: mutation, parsed, admitted, target hit and improved.
        log.open("A", 1, mutationTarget, "SYNC", 1);
        TestChromosome offspringA = chromosome(9);
        log.recordResponse("A", TargetedResponse.Status.DELIVERED, true, true, 0,
                TargetedResponse.CallUsage.NONE, offspringA.getTestCase());
        log.recordEvaluated("A", 1.0, true);
        log.recordAdmitted("A", offspringA, 1);

        // B: mutation, unmatched.
        log.open("B", 1, mutationTarget, "SYNC", 1);
        log.recordResponse("B", TargetedResponse.Status.DELIVERED, true, false, 0,
                TargetedResponse.CallUsage.NONE, null);

        // C: crossover, parse failed.
        log.open("C", 1, crossoverTarget, "SYNC", 1);
        log.recordResponse("C", TargetedResponse.Status.DELIVERED, false, false, 1,
                TargetedResponse.CallUsage.NONE, null);

        // D: mutation, parsed but not improved/hit.
        log.open("D", 1, mutationTarget, "SYNC", 1);
        log.recordResponse("D", TargetedResponse.Status.DELIVERED, true, true, 0,
                TargetedResponse.CallUsage.NONE, chromosome(3).getTestCase());
        log.recordEvaluated("D", 3.0, false);

        assertEquals(4, log.getAttempts());
        assertEquals(3, log.getAttempts(OperatorTarget.Kind.MUTATION));
        assertEquals(1, log.getAttempts(OperatorTarget.Kind.CROSSOVER));
        assertEquals(2, log.getOffspringParsed(), "A and D parsed");
        assertEquals(1, log.getOffspringAdmitted(), "only A admitted");
        assertEquals(1, log.getTargetHits(), "only A hit");
        assertEquals(1, log.getTargetImproved(), "only A improved (1.0 < 2.0)");
        assertEquals(0, log.getOffspringSurvived(), "no survivor scan has run yet");

        log.recordSurvivors(Collections.singletonList(offspringA), 1);
        assertEquals(1, log.getOffspringSurvived(), "A outlives the selection of its admission generation");
    }

    // ---- flush ------------------------------------------------------------------------------

    @Test
    void flushWritesNothingWhenDisabled(@TempDir Path tempDir) {
        Properties.LLM_OPERATOR_ATTEMPT_LOG_ENABLED = false;
        Properties.REPORT_DIR = tempDir.toString();
        Properties.TARGET_CLASS = "com.example.Foo";
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        log.open("a1", 0, OperatorTarget.builder(chromosome(1), goal("g")).build(), "SYNC", 1);

        log.flush();

        assertFalse(Files.exists(java.nio.file.Paths.get(OperatorAttemptLog.resolveSidecarPath())));
    }

    @Test
    void flushWritesHeaderAndRowsWhenEnabled(@TempDir Path tempDir) throws IOException {
        Properties.LLM_OPERATOR_ATTEMPT_LOG_ENABLED = true;
        Properties.REPORT_DIR = tempDir.toString();
        Properties.TARGET_CLASS = "com.example.Foo";
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        log.open("a1", 0, OperatorTarget.builder(chromosome(1), goal("g")).build(), "SYNC", 1);

        log.flush();

        Path csv = java.nio.file.Paths.get(OperatorAttemptLog.resolveSidecarPath());
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(2, lines.size());
        assertEquals(OperatorAttemptRecord.CSV_HEADER, lines.get(0));
        assertTrue(lines.get(1).startsWith("0,a1,SYNC,MUTATION,1,"));
    }

    @Test
    void flushIsANoOpWhenThereAreNoRows(@TempDir Path tempDir) {
        Properties.LLM_OPERATOR_ATTEMPT_LOG_ENABLED = true;
        Properties.REPORT_DIR = tempDir.toString();
        Properties.TARGET_CLASS = "com.example.Foo";

        OperatorAttemptLog.getInstance().flush();

        assertFalse(Files.exists(java.nio.file.Paths.get(OperatorAttemptLog.resolveSidecarPath())));
    }

    // ---- resetInstance ------------------------------------------------------------------------

    @Test
    void resetInstanceStartsAFreshEmptyLog() {
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        log.open("a1", 0, OperatorTarget.builder(chromosome(1), goal("g")).build(), "SYNC", 1);
        assertEquals(1, log.getAttempts());

        OperatorAttemptLog.resetInstance();

        assertEquals(0, OperatorAttemptLog.getInstance().getAttempts());
    }
}
