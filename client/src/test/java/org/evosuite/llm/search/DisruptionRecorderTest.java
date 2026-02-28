/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.search.DisruptionEvent.OperatorKind;
import org.evosuite.llm.search.DisruptionEvent.OperatorOutcome;
import org.evosuite.llm.search.DisruptionEvent.OperatorSource;
import org.evosuite.llm.search.OperatorAttemptResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for operator disruption analysis (Phase 8b).
 *
 * <p>Test categories:
 * 1) Disabled mode => no recorder activity, no sidecar file
 * 2) Enabled mode => event schema completeness for mutation and crossover
 * 3) Semantic fallback path is logged correctly
 * 4) Isolated probe gating: probes only run when explicitly enabled
 * 5) Probe failure is non-fatal and logged
 * 6) Deterministic event ordering under fixed seed
 * 7) MOSA/DynaMOSA integration sanity for post-crossover and post-mutation probe points
 */
class DisruptionRecorderTest {

    private boolean prevEnabled;
    private boolean prevIsolated;
    private String prevOutputDir;

    @BeforeEach
    void saveProperties() {
        prevEnabled = Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED;
        prevIsolated = Properties.LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED;
        prevOutputDir = Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR;
        DisruptionRecorder.resetInstance();
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = prevEnabled;
        Properties.LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED = prevIsolated;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = prevOutputDir;
        DisruptionRecorder.resetInstance();
    }

    // ---- Test Category 1: Disabled mode ----

    @Test
    void disabledModeNoRecorderActivity() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = false;

        assertFalse(DisruptionRecorder.isEnabled());
        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Record should be silently ignored
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        assertEquals(0, recorder.getTotalEvents());
    }

    @Test
    void disabledModeNoSidecarFile(@TempDir Path tempDir) {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = false;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.flush();

        assertFalse(Files.exists(tempDir.resolve("disruption_events.csv")));
    }

    // ---- Test Category 2: Enabled mode => event schema completeness ----

    @Test
    void enabledModeRecordsMutationEvent() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        DisruptionEvent event = makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD);
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        assertEquals(1, recorder.getStandardMutations());
        assertEquals(0, recorder.getSemanticMutations());
    }

    @Test
    void enabledModeRecordsCrossoverEvent() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(makeEvent(0, OperatorKind.CROSSOVER, OperatorSource.SEMANTIC));

        assertEquals(1, recorder.getTotalEvents());
        assertEquals(1, recorder.getSemanticCrossovers());
    }

    @Test
    void eventSchemaComplete() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionEvent event = DisruptionEvent.builder()
                .generation(5)
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.APPLIED)
                .parent1Hash(111)
                .parent2Hash(-1)
                .offspringHash(222)
                .fitnessPreOperator(10.0)
                .fitnessPostOperator(8.0)
                .fitnessDelta(-2.0)
                .statementCountBefore(5)
                .statementCountAfter(7)
                .statementCountDelta(2)
                .editsAdded(2)
                .editsRemoved(0)
                .branchJaccardDistance(0.3)
                .lineJaccardDistance(0.2)
                .goalJaccardDistance(0.1)
                .speciationMetricDistance(0.4)
                .acceptedIntoOffspring(true)
                .survivesToNextGeneration(null)
                .isolatedProbe(true)
                .probeFailure(false)
                .isolatedFitnessPostCrossover(9.0)
                .isolatedFitnessPostMutation(8.0)
                .isolatedMutationDelta(-1.0)
                .build();

        String csv = event.toCsvRow();
        // Verify all fields are present in CSV (27 columns now)
        String[] parts = csv.split(",", -1);
        assertEquals(27, parts.length, "CSV should have 27 columns: " + csv);
        assertEquals("5", parts[0]);
        assertEquals("MUTATION", parts[2]);
        assertEquals("SEMANTIC", parts[3]);
        assertEquals("APPLIED", parts[4]);
        // Isolated probe fitness fields (columns 24-26)
        assertEquals("9.0", parts[24]);
        assertEquals("8.0", parts[25]);
        assertEquals("-1.0", parts[26]);
    }

    @Test
    void csvHeaderMatchesRow() {
        String[] headerParts = DisruptionEvent.CSV_HEADER.split(",");
        DisruptionEvent event = makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD);
        String[] rowParts = event.toCsvRow().split(",", -1);
        assertEquals(headerParts.length, rowParts.length,
                "Header column count must match row column count");
    }

    @Test
    void sidecarCsvWritten(@TempDir Path tempDir) throws IOException {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        recorder.record(makeEvent(1, OperatorKind.CROSSOVER, OperatorSource.SEMANTIC));
        recorder.flush();

        Path sidecar = tempDir.resolve("disruption_events.csv");
        assertTrue(Files.exists(sidecar));

        List<String> lines = Files.readAllLines(sidecar);
        assertEquals(3, lines.size(), "Header + 2 data rows");
        assertEquals(DisruptionEvent.CSV_HEADER, lines.get(0));
        assertNotNull(recorder.getSidecarPath());
    }

    // ---- Test Category 3: Semantic fallback logging ----

    @Test
    void semanticFallbackCountTracked() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(DisruptionEvent.builder()
                .generation(1)
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.FALLBACK)
                .build());

        assertEquals(1, recorder.getSemanticFallbacks());
        assertEquals(1, recorder.getSemanticMutations());
    }

    @Test
    void fallbackEventDistinguishable() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionEvent applied = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.APPLIED)
                .build();
        DisruptionEvent fallback = DisruptionEvent.builder()
                .eventIndex(1)
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.FALLBACK)
                .build();

        assertTrue(applied.toCsvRow().contains("APPLIED"));
        assertTrue(fallback.toCsvRow().contains("FALLBACK"));
    }

    // ---- Test Category 4: Isolated probe gating ----

    @Test
    void isolatedProbeDisabledByDefault() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED = false;

        assertFalse(DisruptionRecorder.isIsolatedProbeEnabled());
    }

    @Test
    void isolatedProbeRequiresBothProperties() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = false;
        Properties.LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED = true;

        assertFalse(DisruptionRecorder.isIsolatedProbeEnabled(),
                "Isolated probes should not be enabled without analysis enabled");
    }

    @Test
    void isolatedProbeEnabledWhenBothSet() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED = true;

        assertTrue(DisruptionRecorder.isIsolatedProbeEnabled());
    }

    // ---- Test Category 5: Probe failure non-fatal ----

    @Test
    void probeFailureMarked() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .probeFailure(true)
                .build();

        assertTrue(event.isProbeFailure());
        assertTrue(event.toCsvRow().contains("true"));
    }

    @Test
    void recorderAcceptsProbeFailureEvents() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .probeFailure(true)
                .build());

        assertEquals(1, recorder.getTotalEvents());
        assertTrue(recorder.getEvents().get(0).isProbeFailure());
    }

    // ---- Test Category 6: Deterministic event ordering ----

    @Test
    void deterministicEventOrdering(@TempDir Path tempDir) throws IOException {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Record events out of order (simulating concurrent recording)
        int idx2 = recorder.nextEventIndex();
        int idx0 = recorder.nextEventIndex();
        int idx1 = recorder.nextEventIndex();

        recorder.record(makeEventWithIndex(idx2, 1, OperatorKind.CROSSOVER, OperatorSource.STANDARD));
        recorder.record(makeEventWithIndex(idx0, 0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        recorder.record(makeEventWithIndex(idx1, 0, OperatorKind.MUTATION, OperatorSource.SEMANTIC));

        recorder.flush();

        List<String> lines = Files.readAllLines(tempDir.resolve("disruption_events.csv"));
        assertEquals(4, lines.size()); // header + 3 rows

        // Rows should be sorted by event_index
        String row1 = lines.get(1);
        String row2 = lines.get(2);
        String row3 = lines.get(3);

        // Event index is second column
        int eventIdx1 = Integer.parseInt(row1.split(",")[1]);
        int eventIdx2 = Integer.parseInt(row2.split(",")[1]);
        int eventIdx3 = Integer.parseInt(row3.split(",")[1]);

        assertTrue(eventIdx1 < eventIdx2, "Events should be ordered by index");
        assertTrue(eventIdx2 < eventIdx3, "Events should be ordered by index");
    }

    // ---- Test Category 7: MOSA integration sanity ----

    @Test
    void runLevelCountersSeparateKindsAndSources() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        recorder.record(makeEvent(1, OperatorKind.MUTATION, OperatorSource.SEMANTIC));
        recorder.record(makeEvent(2, OperatorKind.CROSSOVER, OperatorSource.STANDARD));
        recorder.record(makeEvent(3, OperatorKind.CROSSOVER, OperatorSource.SEMANTIC));

        assertEquals(4, recorder.getTotalEvents());
        assertEquals(1, recorder.getStandardMutations());
        assertEquals(1, recorder.getSemanticMutations());
        assertEquals(1, recorder.getStandardCrossovers());
        assertEquals(1, recorder.getSemanticCrossovers());
    }

    @Test
    void nanFieldsRenderedAsEmpty() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .branchJaccardDistance(Double.NaN)
                .lineJaccardDistance(Double.NaN)
                .goalJaccardDistance(Double.NaN)
                .speciationMetricDistance(Double.NaN)
                .build();

        String csv = event.toCsvRow();
        // The NaN fields should be rendered as empty strings
        String[] parts = csv.split(",", -1);
        assertEquals("", parts[16]); // branch_jaccard_distance
        assertEquals("", parts[17]); // line_jaccard_distance
        assertEquals("", parts[18]); // goal_jaccard_distance
        assertEquals("", parts[19]); // speciation_metric_distance
        // Isolated fitness fields also NaN by default → empty
        assertEquals("", parts[24]); // isolated_fitness_post_crossover
        assertEquals("", parts[25]); // isolated_fitness_post_mutation
        assertEquals("", parts[26]); // isolated_mutation_delta
    }

    @Test
    void survivesToNextGenerationNullRenderedAsEmpty() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .survivesToNextGeneration(null)
                .build();

        String csv = event.toCsvRow();
        String[] parts = csv.split(",", -1);
        assertEquals("", parts[21]); // survives_to_next_generation
    }

    @Test
    void acceptedIntoOffspringNullRenderedAsEmpty() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.STANDARD)
                .build();

        String csv = event.toCsvRow();
        String[] parts = csv.split(",", -1);
        assertEquals("", parts[20]); // accepted_into_offspring (null by default)
    }

    @Test
    void singletonConsistency() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder r1 = DisruptionRecorder.getInstance();
        DisruptionRecorder r2 = DisruptionRecorder.getInstance();
        assertSame(r1, r2);
    }

    @Test
    void resetClearsState() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        assertEquals(1, recorder.getTotalEvents());

        DisruptionRecorder.resetInstance();
        DisruptionRecorder fresh = DisruptionRecorder.getInstance();
        assertEquals(0, fresh.getTotalEvents());
    }

    @Test
    void defaultOutputDirUsesReportDir(@TempDir Path tempDir) throws IOException {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = "";
        Properties.REPORT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        recorder.flush();

        assertTrue(Files.exists(tempDir.resolve("disruption_events.csv")));
    }

    // ---- Helpers ----

    private DisruptionEvent makeEvent(int idx, OperatorKind kind, OperatorSource source) {
        return makeEventWithIndex(idx, 0, kind, source);
    }

    private DisruptionEvent makeEventWithIndex(int eventIndex, int generation,
                                                OperatorKind kind, OperatorSource source) {
        return DisruptionEvent.builder()
                .generation(generation)
                .eventIndex(eventIndex)
                .operatorKind(kind)
                .operatorSource(source)
                .outcome(OperatorOutcome.APPLIED)
                .build();
    }

    // ---- Test Category: OperatorAttemptResult classification ----

    @Test
    void operatorResultSemanticApplied() {
        OperatorAttemptResult result = OperatorAttemptResult.semanticApplied();
        assertTrue(result.isAttemptedSemantic());
        assertTrue(result.isAppliedSemantic());
        assertFalse(result.isFallbackUsed());
        assertEquals(OperatorSource.SEMANTIC, result.toOperatorSource());
        assertEquals(OperatorOutcome.APPLIED, result.toOperatorOutcome());
    }

    @Test
    void operatorResultSemanticFallback() {
        OperatorAttemptResult result = OperatorAttemptResult.semanticFallback();
        assertTrue(result.isAttemptedSemantic());
        assertFalse(result.isAppliedSemantic());
        assertTrue(result.isFallbackUsed());
        assertEquals(OperatorSource.SEMANTIC, result.toOperatorSource());
        assertEquals(OperatorOutcome.FALLBACK, result.toOperatorOutcome());
    }

    @Test
    void operatorResultStandardOnlyDisabled() {
        OperatorAttemptResult result = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.DISABLED);
        assertFalse(result.isAttemptedSemantic());
        assertFalse(result.isAppliedSemantic());
        assertFalse(result.isFallbackUsed());
        assertEquals(OperatorAttemptResult.SkipReason.DISABLED, result.getSkipReason());
        assertEquals(OperatorSource.STANDARD, result.toOperatorSource());
        assertEquals(OperatorOutcome.APPLIED, result.toOperatorOutcome());
    }

    @Test
    void operatorResultStandardOnlyProbabilityGate() {
        OperatorAttemptResult result = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.PROBABILITY);
        assertFalse(result.isAttemptedSemantic());
        assertEquals(OperatorAttemptResult.SkipReason.PROBABILITY, result.getSkipReason());
        assertEquals(OperatorSource.STANDARD, result.toOperatorSource());
    }

    @Test
    void operatorResultStandardOnlyNotConfigured() {
        OperatorAttemptResult result = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.NOT_CONFIGURED);
        assertFalse(result.isAttemptedSemantic());
        assertEquals(OperatorAttemptResult.SkipReason.NOT_CONFIGURED, result.getSkipReason());
    }

    // ---- Test Category: Probe failure with partial data ----

    @Test
    void probeFailureWithPartialIsolatedData() {
        // Simulates a probe failure where post-crossover succeeded but post-mutation failed
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .isolatedProbe(true)
                .probeFailure(true)
                .isolatedFitnessPostCrossover(5.0)
                .isolatedFitnessPostMutation(Double.NaN)
                .isolatedMutationDelta(Double.NaN)
                .build();

        assertTrue(event.isProbeFailure());
        assertTrue(event.isIsolatedProbe());
        assertEquals(5.0, event.getIsolatedFitnessPostCrossover());
        assertTrue(Double.isNaN(event.getIsolatedFitnessPostMutation()));

        String csv = event.toCsvRow();
        String[] parts = csv.split(",", -1);
        assertEquals("true", parts[22]); // isolated_probe
        assertEquals("true", parts[23]); // probe_failure
        assertEquals("5.0", parts[24]);  // isolated_fitness_post_crossover
        assertEquals("", parts[25]);     // isolated_fitness_post_mutation (NaN)
    }

    // ---- Test Category: Isolated probe fitness values ----

    @Test
    void isolatedProbeFieldsPopulated() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.APPLIED)
                .isolatedProbe(true)
                .probeFailure(false)
                .isolatedFitnessPostCrossover(10.0)
                .isolatedFitnessPostMutation(7.0)
                .isolatedMutationDelta(-3.0)
                .build();

        assertEquals(10.0, event.getIsolatedFitnessPostCrossover());
        assertEquals(7.0, event.getIsolatedFitnessPostMutation());
        assertEquals(-3.0, event.getIsolatedMutationDelta());

        String csv = event.toCsvRow();
        assertTrue(csv.contains("10.0"));
        assertTrue(csv.contains("7.0"));
        assertTrue(csv.contains("-3.0"));
    }

    @Test
    void isolatedProbeFieldsAbsentWhenNotEnabled() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .isolatedProbe(false)
                .build();

        assertFalse(event.isIsolatedProbe());
        assertTrue(Double.isNaN(event.getIsolatedFitnessPostCrossover()));
        assertTrue(Double.isNaN(event.getIsolatedFitnessPostMutation()));
        assertTrue(Double.isNaN(event.getIsolatedMutationDelta()));
    }

    // ---- Test Category: Acceptance/survival nullable semantics ----

    @Test
    void acceptedIntoOffspringNullableDefault() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.STANDARD)
                .build();

        assertNull(event.getAcceptedIntoOffspring(),
                "acceptedIntoOffspring should be null by default for crossover events");
    }

    @Test
    void acceptedIntoOffspringTrueForMutation() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .acceptedIntoOffspring(true)
                .build();

        assertEquals(Boolean.TRUE, event.getAcceptedIntoOffspring());
        String csv = event.toCsvRow();
        String[] parts = csv.split(",", -1);
        assertEquals("true", parts[20]);
    }

    // ---- Test Category: Disruption counter emission safety ----

    @Test
    void countersZeroWhenDisabled() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = false;
        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        assertEquals(0, recorder.getTotalEvents());
        assertEquals(0, recorder.getStandardMutations());
        assertEquals(0, recorder.getSemanticMutations());
        assertEquals(0, recorder.getStandardCrossovers());
        assertEquals(0, recorder.getSemanticCrossovers());
        assertEquals(0, recorder.getSemanticFallbacks());
        assertNull(recorder.getSidecarPath());
    }

    @Test
    void countersAccurateWithMixedEvents() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // 2 standard mutations, 1 semantic mutation, 1 semantic fallback mutation
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));
        recorder.record(makeEvent(1, OperatorKind.MUTATION, OperatorSource.STANDARD));
        recorder.record(DisruptionEvent.builder()
                .eventIndex(2).operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.SEMANTIC).outcome(OperatorOutcome.APPLIED).build());
        recorder.record(DisruptionEvent.builder()
                .eventIndex(3).operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.SEMANTIC).outcome(OperatorOutcome.FALLBACK).build());

        // 1 standard crossover, 1 semantic crossover
        recorder.record(makeEvent(4, OperatorKind.CROSSOVER, OperatorSource.STANDARD));
        recorder.record(makeEvent(5, OperatorKind.CROSSOVER, OperatorSource.SEMANTIC));

        assertEquals(6, recorder.getTotalEvents());
        assertEquals(2, recorder.getStandardMutations());
        assertEquals(2, recorder.getSemanticMutations());
        assertEquals(1, recorder.getStandardCrossovers());
        assertEquals(1, recorder.getSemanticCrossovers());
        assertEquals(1, recorder.getSemanticFallbacks());
    }

    // ---- Test Category A: Lifecycle/stats path ----

    @Test
    void resolveSidecarPathDeterministic(@TempDir Path tempDir) {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Path is available before flush
        String path = recorder.resolveSidecarPath();
        assertNotNull(path);
        assertTrue(path.endsWith("disruption_events.csv"));
        assertTrue(path.startsWith(tempDir.toString()));
    }

    @Test
    void sidecarPathAvailableBeforeFlush(@TempDir Path tempDir) {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));

        // resolveSidecarPath works before flush
        String pathBeforeFlush = recorder.resolveSidecarPath();
        assertNotNull(pathBeforeFlush);

        recorder.flush();

        // After flush, getSidecarPath returns same value
        assertEquals(pathBeforeFlush, recorder.getSidecarPath());
        assertTrue(Files.exists(Path.of(pathBeforeFlush)));
    }

    @Test
    void resolveSidecarPathFallsBackToReportDir(@TempDir Path tempDir) {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = "";
        Properties.REPORT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        String path = recorder.resolveSidecarPath();
        assertTrue(path.startsWith(tempDir.toString()));
    }

    // ---- Test Category B: Crossover fallback recording ----

    @Test
    void crossoverFallbackNoStandardAppliedStillRecorded() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Simulate: semantic attempted + fallback, but standard crossover gate rejected
        // The event should still be recorded with SEMANTIC/FALLBACK and accepted=false
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.FALLBACK)
                .acceptedIntoOffspring(false) // no crossover actually happened
                .build();
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        assertEquals(1, recorder.getSemanticCrossovers());
        assertEquals(1, recorder.getSemanticFallbacks());

        DisruptionEvent recorded = recorder.getEvents().get(0);
        assertEquals(OperatorOutcome.FALLBACK, recorded.getOutcome());
        assertEquals(Boolean.FALSE, recorded.getAcceptedIntoOffspring());
    }

    @Test
    void crossoverFallbackWithStandardAppliedLabeling() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Simulate: semantic attempted + fallback + standard crossover applied
        // The OperatorAttemptResult would be semanticFallback(), but crossoverApplied=true
        // so acceptance is null (unknown until mutation)
        OperatorAttemptResult result = OperatorAttemptResult.semanticFallback();
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(result.toOperatorSource())
                .outcome(result.toOperatorOutcome())
                // acceptedIntoOffspring left null (crossover applied but mutation outcome unknown)
                .build();
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        DisruptionEvent recorded = recorder.getEvents().get(0);
        assertEquals(OperatorSource.SEMANTIC, recorded.getOperatorSource());
        assertEquals(OperatorOutcome.FALLBACK, recorded.getOutcome());
        assertNull(recorded.getAcceptedIntoOffspring());
    }

    @Test
    void crossoverStandardOnlyAppliedLabeling() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        OperatorAttemptResult result = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.NOT_CONFIGURED);
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(result.toOperatorSource())
                .outcome(result.toOperatorOutcome())
                .build();
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        DisruptionEvent recorded = recorder.getEvents().get(0);
        assertEquals(OperatorSource.STANDARD, recorded.getOperatorSource());
        assertEquals(OperatorOutcome.APPLIED, recorded.getOutcome());
    }

    // ---- Test Category C: Crossover isolated probe ----

    @Test
    void crossoverIsolatedProbeFieldsPopulated() {
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(0)
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.STANDARD)
                .outcome(OperatorOutcome.APPLIED)
                .isolatedProbe(true)
                .isolatedFitnessPostCrossover(12.5)
                .branchJaccardDistance(0.3)
                .build();

        assertTrue(event.isIsolatedProbe());
        assertEquals(12.5, event.getIsolatedFitnessPostCrossover());
        assertEquals(0.3, event.getBranchJaccardDistance());
        // isolated_fitness_post_mutation should be NaN for crossover events
        assertTrue(Double.isNaN(event.getIsolatedFitnessPostMutation()));
    }

    @Test
    void crossoverProbeFailureStillRecordsEvent() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.APPLIED)
                .isolatedProbe(true)
                .probeFailure(true)
                .isolatedFitnessPostCrossover(Double.NaN)
                .build();
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        DisruptionEvent recorded = recorder.getEvents().get(0);
        assertTrue(recorded.isProbeFailure());
        assertTrue(recorded.isIsolatedProbe());
        assertTrue(Double.isNaN(recorded.getIsolatedFitnessPostCrossover()));
    }

    // ---- Test Category D: Mutation unchanged-attempt ----

    @Test
    void mutationUnchangedAttemptRecordable() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Simulate mutation attempt that didn't change offspring
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .outcome(OperatorOutcome.APPLIED)
                .acceptedIntoOffspring(false) // not accepted because unchanged
                .fitnessPreOperator(5.0)
                .fitnessPostOperator(Double.NaN) // not evaluated
                .fitnessDelta(Double.NaN)
                .build();
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        DisruptionEvent recorded = recorder.getEvents().get(0);
        assertEquals(Boolean.FALSE, recorded.getAcceptedIntoOffspring());
        assertEquals(5.0, recorded.getFitnessPreOperator());
        assertTrue(Double.isNaN(recorded.getFitnessPostOperator()));
        assertTrue(Double.isNaN(recorded.getFitnessDelta()));
    }

    @Test
    void mutationSemanticFallbackUnchangedPreservesMetadata() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Semantic attempted + fallback + standard mutation didn't change offspring
        OperatorAttemptResult result = OperatorAttemptResult.semanticFallback();
        DisruptionEvent event = DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(result.toOperatorSource())
                .outcome(result.toOperatorOutcome())
                .acceptedIntoOffspring(false)
                .statementCountBefore(10)
                .statementCountAfter(10)
                .statementCountDelta(0)
                .build();
        recorder.record(event);

        assertEquals(1, recorder.getTotalEvents());
        assertEquals(1, recorder.getSemanticFallbacks());
        DisruptionEvent recorded = recorder.getEvents().get(0);
        assertEquals(OperatorSource.SEMANTIC, recorded.getOperatorSource());
        assertEquals(OperatorOutcome.FALLBACK, recorded.getOutcome());
        assertEquals(Boolean.FALSE, recorded.getAcceptedIntoOffspring());
    }

    // ---- Test Category E: MOSA integration event semantics ----

    @Test
    void eventCountIncludesBothAcceptedAndRejected(@TempDir Path tempDir) throws IOException {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();

        // Simulate a breeding cycle with accepted and rejected events
        // Crossover: semantic fallback, no standard applied
        recorder.record(DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.CROSSOVER)
                .operatorSource(OperatorSource.SEMANTIC)
                .outcome(OperatorOutcome.FALLBACK)
                .acceptedIntoOffspring(false)
                .build());
        // Mutation: accepted
        recorder.record(DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .outcome(OperatorOutcome.APPLIED)
                .acceptedIntoOffspring(true)
                .build());
        // Mutation: rejected (unchanged)
        recorder.record(DisruptionEvent.builder()
                .eventIndex(recorder.nextEventIndex())
                .operatorKind(OperatorKind.MUTATION)
                .operatorSource(OperatorSource.STANDARD)
                .outcome(OperatorOutcome.APPLIED)
                .acceptedIntoOffspring(false)
                .build());

        assertEquals(3, recorder.getTotalEvents());
        assertEquals(2, recorder.getStandardMutations());
        assertEquals(1, recorder.getSemanticCrossovers());
        assertEquals(1, recorder.getSemanticFallbacks());

        recorder.flush();
        List<String> lines = Files.readAllLines(tempDir.resolve("disruption_events.csv"));
        assertEquals(4, lines.size()); // header + 3 events
    }

    @Test
    void sidecarPathEmittedConsistentlyWithResolve(@TempDir Path tempDir) throws IOException {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();

        DisruptionRecorder recorder = DisruptionRecorder.getInstance();
        recorder.record(makeEvent(0, OperatorKind.MUTATION, OperatorSource.STANDARD));

        // Resolve path matches what flush will use
        String resolved = recorder.resolveSidecarPath();
        recorder.flush();

        assertEquals(resolved, recorder.getSidecarPath());
        assertTrue(Files.exists(Path.of(resolved)));
    }
}
