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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The master keeps the last value it receives for a variable, and samples each timeline variable
 * whenever the client sends an individual. The zero defaults therefore belong before the search:
 * published at its end they replace the timelines and counters the search emitted. Without an LLM
 * provider the end of the run publishes only the totals of the metrics other components track.
 */
public class LlmStatisticsEndOfRunTest {

    /** Keeps every variable in order, and the last value per variable, as the master's statistics do. */
    private static final class RecordingClientNode extends DummyClientNodeImpl<TestChromosome> {
        private static final long serialVersionUID = 1L;
        final Map<RuntimeVariable, Object> lastValues = new ConcurrentHashMap<>();
        final List<RuntimeVariable> tracked = new CopyOnWriteArrayList<>();

        @Override
        public void trackOutputVariable(RuntimeVariable name, Object value) {
            tracked.add(name);
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

    @Test
    public void searchValuesSurviveTheEndOfARunWithoutProvider() {
        // Before the search: the defaults.
        LlmStatistics.initializeRuntimeVariables();
        // During and at the end of the search: what the search emits.
        ClientServices.track(RuntimeVariable.Covered_Goals_Timeline, 42);
        ClientServices.track(RuntimeVariable.Species_Count_Timeline, 5);
        ClientServices.track(RuntimeVariable.Disruption_Events_Total, 7);
        ClientServices.track(RuntimeVariable.LLM_Operator_Targets_Selected, 3);

        // End of the search and of the run (GeneticAlgorithm and TestSuiteGenerator).
        LlmStatistics.publishRunTotals();

        assertEquals(42, recorder.lastValues.get(RuntimeVariable.Covered_Goals_Timeline),
                "the end of the run replaced the covered-goals timeline value");
        assertEquals(5, recorder.lastValues.get(RuntimeVariable.Species_Count_Timeline));
        assertEquals(7, recorder.lastValues.get(RuntimeVariable.Disruption_Events_Total),
                "the end of the run replaced the disruption count with zero");
        assertEquals(3, recorder.lastValues.get(RuntimeVariable.LLM_Operator_Targets_Selected));
    }

    @Test
    public void endOfRunTracksNoTimeline() {
        LlmStatistics.publishRunTotals();

        List<String> timelines = new ArrayList<>();
        for (RuntimeVariable variable : recorder.tracked) {
            if (variable.name().endsWith("Timeline")) {
                timelines.add(variable.name());
            }
        }
        assertTrue(timelines.isEmpty(), "timelines tracked at the end of the run: " + timelines);
    }

    @Test
    public void defaultsAreInPlaceForFeaturesAnLlmRunNeverExercises() {
        // An LLM run publishes only the values of the features it used; the defaults fill the rest.
        LlmStatistics.initializeRuntimeVariables();
        new LlmStatistics().publishRuntimeVariables();

        assertEquals(0, recorder.lastValues.get(RuntimeVariable.LLM_Iterative_Parse_Failures));
        assertEquals(0, recorder.lastValues.get(RuntimeVariable.LLM_StagnationCalls));
        assertEquals(0, recorder.lastValues.get(RuntimeVariable.Species_Count_Timeline));
    }
}
