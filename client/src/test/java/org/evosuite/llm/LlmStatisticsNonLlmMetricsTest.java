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

import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Disabled("D11: LLM end-of-run defaults zero Object_Pool_Sequence_Used and LLM_Fallback_*"
            + " - see CLEANUP-PLAN.md")
    @Test
    public void endOfRunDefaultsKeepMetricsTrackedDuringTheRun() {
        // During the run: one object-pool sequence used (VariableResolver) and one snippet that
        // failed to compile (ExecutableSnippetEngine).
        ClientServices.track(RuntimeVariable.Object_Pool_Sequence_Used, 1);
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
}
