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
import org.evosuite.seeding.ObjectPool;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * At the end of a run without an LLM provider, the search and the generator call
 * {@link LlmStatistics#initializeRuntimeVariables()} to publish zero defaults. The master keeps
 * the last value it receives for a variable, so the defaults must not include metrics that other
 * components track during the run, such as how often an object-pool sequence was used.
 */
public class LlmStatisticsNonLlmMetricsTest {

    /** Keeps the last value per variable, as the master's statistics do. */
    private static final class RecordingClientNode extends DummyClientNodeImpl<TestChromosome> {
        private static final long serialVersionUID = 1L;
        final Map<RuntimeVariable, Object> lastValues = new ConcurrentHashMap<>();

        @Override
        public void trackOutputVariable(RuntimeVariable name, Object value) {
            lastValues.put(name, value);
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
    }

    @AfterEach
    public void restoreClientNode() throws Exception {
        clientNodeField.set(ClientServices.getInstance(), previousClientNode);
    }

    @AfterEach
    public void resetCounters() {
        ObjectPoolManager.getInstance().reset();
        ObjectPoolManager.resetSequenceUsageCount();
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();
    }

    @Test
    public void endOfRunDefaultsKeepMetricsTrackedDuringTheRun() throws Exception {
        // During the run: one object-pool sequence used (as VariableResolver records it) and one
        // snippet that failed to compile (ExecutableSnippetEngine's own counter).
        ObjectPoolManager.getInstance().reset();
        ObjectPoolManager.resetSequenceUsageCount();
        int used = ObjectPoolManager.incrementSequenceUsageCount();
        ClientServices.track(RuntimeVariable.Object_Pool_Sequence_Used, used);
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();
        Field compileFailures = ExecutableSnippetEngine.class.getDeclaredField("compileFailures");
        compileFailures.setAccessible(true);
        ((AtomicInteger) compileFailures.get(ExecutableSnippetEngine.INSTANCE)).incrementAndGet();
        ClientServices.track(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, 1);

        // End of a run with LLM_PROVIDER == NONE (GeneticAlgorithm and TestSuiteGenerator).
        LlmStatistics.initializeRuntimeVariables();

        assertAll(
                () -> assertEquals(1, recorder.lastValues.get(RuntimeVariable.Object_Pool_Sequence_Used),
                        "the object-pool usage count was reset to zero by the LLM defaults"),
                () -> assertEquals(1,
                        recorder.lastValues.get(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures),
                        "the snippet fallback count was reset to zero by the LLM defaults"));
    }

    @Test
    public void endOfRunDefaultsReportZeroForMetricsNeverTracked() {
        ObjectPoolManager.getInstance().reset();
        ObjectPoolManager.resetSequenceUsageCount();
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();

        LlmStatistics.initializeRuntimeVariables();

        // The statistics columns stay filled (0, not empty) when nothing happened.
        assertAll(
                () -> assertEquals(0, recorder.lastValues.get(RuntimeVariable.Object_Pool_Sequence_Used)),
                () -> assertEquals(0,
                        recorder.lastValues.get(RuntimeVariable.LLM_Fallback_Assertion_Evaluation_Failures)));
    }

    @Test
    public void publishingRunTotalsDoesNotLoadTheObjectPool() throws Exception {
        ObjectPool filePool = new ObjectPool();
        filePool.addSequence(GenericClassFactory.get(String.class), new DefaultTestCase());
        File poolFile = File.createTempFile("objectpool", ".ser");
        poolFile.deleteOnExit();
        filePool.writePool(poolFile.getAbsolutePath());

        String previousPools = Properties.OBJECT_POOLS;
        try {
            ObjectPoolManager.getInstance().reset();
            Properties.OBJECT_POOLS = "";

            // The totals are published before the target class is set up. Creating the pool
            // there would load it too early and leave it without the files configured later.
            LlmStatistics.publishRunTotals();

            Properties.OBJECT_POOLS = poolFile.getAbsolutePath();
            assertEquals(1, ObjectPoolManager.getInstance().getNumberOfSequences(),
                    "publishing the run totals created the object pool");
        } finally {
            Properties.OBJECT_POOLS = previousPools;
        }
    }

    @Test
    public void usageCountSurvivesAContextReset() {
        ObjectPoolManager.resetSequenceUsageCount();
        ObjectPoolManager.incrementSequenceUsageCount();

        // The assertion phase resets the context, which resets the pool.
        ObjectPoolManager.getInstance().reset();
        LlmStatistics.publishRunTotals();

        assertEquals(1, recorder.lastValues.get(RuntimeVariable.Object_Pool_Sequence_Used),
                "the context reset zeroed the count published at the end of the run");
    }
}
