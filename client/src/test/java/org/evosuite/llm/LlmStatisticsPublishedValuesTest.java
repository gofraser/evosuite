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
package org.evosuite.llm;

import org.evosuite.llm.search.ExtractorCandidateMetric;
import org.evosuite.llm.search.ExtractorRejectReason;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins every runtime variable that {@link LlmStatistics} publishes, with its value, so that the
 * CSV columns the experiment harness reads cannot change by accident. The expected values live in
 * {@code llm-statistics-*.txt} next to this class's resources; one line per variable, sorted by
 * name.
 *
 * <p>Set {@code -Dllm.statistics.golden.dir=<dir>} to rewrite the files instead of comparing.
 */
public class LlmStatisticsPublishedValuesTest {

    private static final String WRITE_PROPERTY = "llm.statistics.golden.dir";

    /** Keeps the last value per variable, as the master's statistics do. */
    private static final class RecordingClientNode extends DummyClientNodeImpl<TestChromosome> {
        private static final long serialVersionUID = 1L;
        final Map<String, Object> lastValues = new TreeMap<>();

        @Override
        public synchronized void trackOutputVariable(RuntimeVariable name, Object value) {
            lastValues.put(name.name(), value);
        }
    }

    private Field clientNodeField;
    private Object previousClientNode;
    private RecordingClientNode recorder;

    @BeforeEach
    public void installRecorder() throws Exception {
        clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        previousClientNode = clientNodeField.get(ClientServices.getInstance());
        recorder = new RecordingClientNode();
        clientNodeField.set(ClientServices.getInstance(), recorder);
        resetProcessState();
    }

    @AfterEach
    public void restoreClientNode() throws Exception {
        clientNodeField.set(ClientServices.getInstance(), previousClientNode);
        resetProcessState();
    }

    private static void resetProcessState() {
        LlmStatistics.resetRunCounters();
        ObjectPoolManager.getInstance().reset();
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();
    }

    @Test
    public void defaultsPublishedBeforeTheSearch() throws IOException {
        LlmStatistics.initializeRuntimeVariables();

        verify("llm-statistics-defaults.txt");
    }

    @Test
    public void emptyServicePublishesTheSameColumnsAsTheDefaults() throws IOException {
        LlmStatistics.initializeRuntimeVariables();
        Map<String, Object> defaults = new TreeMap<>(recorder.lastValues);
        recorder.lastValues.clear();

        new LlmStatistics().publishRuntimeVariables();

        verify("llm-statistics-empty-publication.txt");
        for (String name : recorder.lastValues.keySet()) {
            assertNotNull(defaults.get(name), name + " is published without a default");
        }
    }

    @Test
    public void populatedServicePublishesEveryCounter() throws IOException {
        long next = 1L;
        for (ProblemCardType type : ProblemCardType.values()) {
            for (long i = 0; i < next; i++) {
                LlmStatistics.recordDiagnosticCardsExtracted(Collections.singletonList(card(type)));
            }
            next++;
            for (long i = 0; i < next; i++) {
                LlmStatistics.recordDiagnosticCardsSelected(Collections.singletonList(card(type)));
            }
            next++;
            LlmStatistics.recordDiagnosticCandidatesPublished(Collections.singletonList(type), (int) next++);
            LlmStatistics.recordDiagnosticCandidatesAdmitted(Collections.singletonList(type), (int) next++);
            LlmStatistics.recordDiagnosticCandidatesSurvived(Collections.singletonList(type), (int) next++);
            LlmStatistics.recordDiagnosticCoverageGain(type, (int) next++);
        }
        Map<ExtractorCandidateMetric, Integer> candidates = new EnumMap<>(ExtractorCandidateMetric.class);
        for (ExtractorCandidateMetric metric : ExtractorCandidateMetric.values()) {
            candidates.put(metric, (int) next++);
        }
        LlmStatistics.recordDiagnosticExtractorCandidates(candidates);
        Map<ExtractorRejectReason, Integer> rejects = new EnumMap<>(ExtractorRejectReason.class);
        for (ExtractorRejectReason reason : ExtractorRejectReason.values()) {
            rejects.put(reason, (int) next++);
        }
        LlmStatistics.recordDiagnosticExtractorRejects(rejects);
        LlmStatistics.recordDiagnosticCoverageGainAttributionAmbiguous(
                Arrays.asList(ProblemCardType.UNREACHED_METHOD, ProblemCardType.CDG_BOTTLENECK), (int) next++);

        LlmStatistics.recordPoolEnrichmentElapsedMs(next++);
        LlmStatistics.recordInitialPopulationElapsedMs(next++);
        LlmStatistics.recordInitialPopulationCandidatesValidated(next++);
        LlmStatistics.recordInitialPopulationCandidatesQueued(next++);
        LlmStatistics.recordInitialPopulationCandidatesInjected(next++);
        LlmStatistics.recordSutConstantsAdded(next++);
        LlmStatistics.recordNonSutConstantsAdded(next++);
        LlmStatistics.recordObjectPoolSequencesAdded(next++);
        LlmStatistics.recordCastClassSuggestions(next++);
        LlmStatistics.recordCastClassesAccepted(next++);

        LlmStatistics statistics = new LlmStatistics();
        for (LlmFeature feature : LlmFeature.values()) {
            statistics.recordCall(feature, (int) next++, (int) next++, (int) next++, next++);
        }
        statistics.recordFailure(LlmFeature.SEEDING, next++);
        statistics.recordTimeout(LlmFeature.STAGNATION, next++);

        verify("llm-statistics-populated-recording.txt");
        recorder.lastValues.clear();
        statistics.publishRuntimeVariables();

        verify("llm-statistics-populated-publication.txt");
    }

    private void verify(String resource) throws IOException {
        StringBuilder actual = new StringBuilder();
        for (Map.Entry<String, Object> entry : recorder.lastValues.entrySet()) {
            actual.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        String writeDir = System.getProperty(WRITE_PROPERTY);
        if (writeDir != null) {
            Files.write(Paths.get(writeDir, resource), actual.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        assertEquals(read(resource), actual.toString());
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = LlmStatisticsPublishedValuesTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ProblemCard card(ProblemCardType type) {
        return ProblemCard.builder(type)
                .title("title")
                .evidence(Collections.singletonList("e"))
                .relatedGoals(Collections.emptyList())
                .impact(0.7)
                .blockage(0.8)
                .confidence(0.9)
                .build();
    }
}
