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

import org.evosuite.Properties;
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
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the complete set of runtime variables, with their values, that a run publishes outside the
 * search itself: the defaults tracked before the search and the totals tracked at its end. It
 * drives the same entry points as {@code TestSuiteGenerator} and {@code GeneticAlgorithm} for a
 * run without a provider, with seeding only, with the semantic operators only and with both. The
 * expected sets live in {@code runtime-variables-*.txt} next to this class's resources.
 *
 * <p>Set {@code -Dllm.statistics.golden.dir=<dir>} to rewrite the files instead of comparing.
 */
public class RuntimeVariableSetCharacterisationTest {

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
    private Properties.LlmProvider previousProvider;
    private RecordingClientNode recorder;

    @BeforeEach
    public void installRecorder() throws Exception {
        clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        previousClientNode = clientNodeField.get(ClientServices.getInstance());
        recorder = new RecordingClientNode();
        clientNodeField.set(ClientServices.getInstance(), recorder);
        previousProvider = Properties.LLM_PROVIDER;
        resetProcessState();
    }

    @AfterEach
    public void restore() throws Exception {
        clientNodeField.set(ClientServices.getInstance(), previousClientNode);
        Properties.LLM_PROVIDER = previousProvider;
        resetProcessState();
    }

    private static void resetProcessState() {
        LlmStatistics.resetRunCounters();
        ObjectPoolManager.getInstance().reset();
        ObjectPoolManager.resetSequenceUsageCount();
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();
    }

    /** The calls TestSuiteGenerator makes before the search. */
    private static void publishDefaults() {
        LlmStatistics.initializeRuntimeVariables();
    }

    /** The calls GeneticAlgorithm and TestSuiteGenerator make when the search is over. */
    private static void publishEndOfRun(LlmStatistics statistics) {
        if (LlmRunFeatures.providerConfigured()) {
            statistics.publishRuntimeVariables();
        } else {
            LlmStatistics.publishRunTotals();
        }
    }

    @Test
    public void noProvider() throws IOException {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;

        publishDefaults();
        publishEndOfRun(new LlmStatistics());

        verify("runtime-variables-none.txt");
    }

    @Test
    public void seedingOnly() throws IOException {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
        publishDefaults();
        LlmStatistics statistics = new LlmStatistics();
        recordSeeding(statistics);

        publishEndOfRun(statistics);

        verify("runtime-variables-seeding.txt");
    }

    @Test
    public void operatorsOnly() throws IOException {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
        publishDefaults();
        LlmStatistics statistics = new LlmStatistics();
        recordOperators(statistics);

        publishEndOfRun(statistics);

        verify("runtime-variables-operators.txt");
    }

    @Test
    public void seedingAndOperators() throws IOException {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
        publishDefaults();
        LlmStatistics statistics = new LlmStatistics();
        recordSeeding(statistics);
        recordOperators(statistics);

        publishEndOfRun(statistics);

        verify("runtime-variables-combined.txt");
    }

    private static void recordSeeding(LlmStatistics statistics) {
        statistics.recordCall(LlmFeature.SEEDING, 11, 3, 7, 130L);
        LlmStatistics.recordInitialPopulationCandidatesValidated(5L);
        LlmStatistics.recordInitialPopulationCandidatesQueued(4L);
        LlmStatistics.recordInitialPopulationCandidatesInjected(3L);
        LlmStatistics.recordInitialPopulationElapsedMs(250L);
        LlmStatistics.recordSutConstantsAdded(6L);
    }

    private static void recordOperators(LlmStatistics statistics) {
        statistics.recordCall(LlmFeature.SEMANTIC_MUTATION, 13, 2, 9, 170L);
        statistics.recordCall(LlmFeature.SEMANTIC_CROSSOVER, 17, 1, 5, 90L);
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
        try (InputStream in = RuntimeVariableSetCharacterisationTest.class.getResourceAsStream(resource)) {
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
}
