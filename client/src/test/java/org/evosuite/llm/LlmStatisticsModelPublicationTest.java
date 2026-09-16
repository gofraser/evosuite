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
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link LlmStatistics#publishRuntimeVariables()} publishes
 * {@link RuntimeVariable#LLM_Operator_Model} alongside {@link RuntimeVariable#LLM_Model}:
 * the operator model when {@link LlmConfiguration#hasSeparateOperatorModel()}, otherwise
 * the same value as the primary model; and that
 * {@link LlmStatistics#initializeRuntimeVariables()} publishes it as an empty default.
 */
class LlmStatisticsModelPublicationTest {

    private ClientNodeImpl<?> previousClientNode;

    @AfterEach
    void restoreClientNode() throws ReflectiveOperationException {
        if (previousClientNode == null) {
            return;
        }
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        field.set(ClientServices.getInstance(), previousClientNode);
        previousClientNode = null;
    }

    private CapturingClientNode installCapturingClientNode() throws ReflectiveOperationException {
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        previousClientNode = (ClientNodeImpl<?>) field.get(ClientServices.getInstance());
        CapturingClientNode node = new CapturingClientNode();
        field.set(ClientServices.getInstance(), node);
        return node;
    }

    @Test
    void publishesSeparateOperatorModelWhenConfigured() throws ReflectiveOperationException {
        CapturingClientNode node = installCapturingClientNode();
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI, "primary-model", "operator-model", "", "",
                0.7, 1024, 60, 2, 250, false, Paths.get("target/llm-test-traces"), "model-pub-test");
        LlmStatistics statistics = new LlmStatistics();
        statistics.setConfiguration(configuration);

        statistics.publishRuntimeVariables();

        assertEquals("primary-model", node.value(RuntimeVariable.LLM_Model));
        assertEquals("operator-model", node.value(RuntimeVariable.LLM_Operator_Model));
    }

    @Test
    void publishesPrimaryModelAsOperatorModelWhenNoneConfigured() throws ReflectiveOperationException {
        CapturingClientNode node = installCapturingClientNode();
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI, "primary-model", "", "", "",
                0.7, 1024, 60, 2, 250, false, Paths.get("target/llm-test-traces"), "model-pub-test-2");
        LlmStatistics statistics = new LlmStatistics();
        statistics.setConfiguration(configuration);

        statistics.publishRuntimeVariables();

        assertEquals("primary-model", node.value(RuntimeVariable.LLM_Model));
        assertEquals("primary-model", node.value(RuntimeVariable.LLM_Operator_Model));
    }

    @Test
    void initializeRuntimeVariablesPublishesEmptyOperatorModel() throws ReflectiveOperationException {
        CapturingClientNode node = installCapturingClientNode();

        LlmStatistics.initializeRuntimeVariables();

        assertEquals("", node.value(RuntimeVariable.LLM_Model));
        assertEquals("", node.value(RuntimeVariable.LLM_Operator_Model));
    }

    private static final class CapturingClientNode extends DummyClientNodeImpl {
        private final Map<RuntimeVariable, Object> values = new LinkedHashMap<>();

        @Override
        public void trackOutputVariable(RuntimeVariable name, Object value) {
            values.put(name, value);
        }

        private Object value(RuntimeVariable variable) {
            return values.get(variable);
        }
    }
}
