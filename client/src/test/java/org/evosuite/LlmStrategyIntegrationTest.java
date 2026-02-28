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
package org.evosuite;

import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.llm.*;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.strategy.LlmStrategy;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LlmStrategyIntegrationTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";
    private static final String TWO_TESTS_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTestA() {\n" +
                    "  }\n" +
                    "  @Test\n" +
                    "  public void generatedTestB() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    private final Properties.Strategy originalStrategy = Properties.STRATEGY;

    @AfterEach
    void restoreProperties() {
        Properties.STRATEGY = originalStrategy;
    }

    @Test
    void helperSelectsLlmStrategy() {
        Properties.STRATEGY = Properties.Strategy.LLMSTRATEGY;
        TestGenerationStrategy strategy = TestSuiteGeneratorHelper.getTestGenerationStrategy();
        assertInstanceOf(LlmStrategy.class, strategy);
    }

    @Test
    void baselineStrategyBuildsSuiteFromLlmSeeds() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.SEEDING, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        LlmSeededPopulationFactory seededFactory = new LlmSeededPopulationFactory(
                new RandomLengthTestFactory(),
                service,
                Collections::emptyList,
                Runnable::run);

        LlmStrategy strategy = new LlmStrategy() {
            @Override
            protected boolean canGenerateTestsForSUT() {
                return true;
            }

            @Override
            protected List<TestSuiteFitnessFunction> getFitnessFunctions() {
                return Collections.emptyList();
            }

            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                return Collections.emptyList();
            }

            @Override
            protected LlmSeededPopulationFactory createSeededFactory() {
                return seededFactory;
            }

            @Override
            protected void sendExecutionStatistics() {
                // no-op for focused test isolation
            }
        };

        try {
            TestSuiteChromosome suite = strategy.generateTests();
            assertEquals(1, suite.size());
        } finally {
            service.close();
        }
    }

    @Test
    void baselineStrategyKeepsAllReturnedSeeds() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.SEEDING, TWO_TESTS_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        LlmSeededPopulationFactory seededFactory = new LlmSeededPopulationFactory(
                new RandomLengthTestFactory(),
                service,
                Collections::emptyList,
                Runnable::run);

        LlmStrategy strategy = new LlmStrategy() {
            @Override
            protected boolean canGenerateTestsForSUT() {
                return true;
            }

            @Override
            protected List<TestSuiteFitnessFunction> getFitnessFunctions() {
                return Collections.emptyList();
            }

            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                return Collections.emptyList();
            }

            @Override
            protected LlmSeededPopulationFactory createSeededFactory() {
                return seededFactory;
            }

            @Override
            protected void sendExecutionStatistics() {
                // no-op for focused test isolation
            }
        };

        try {
            TestSuiteChromosome suite = strategy.generateTests();
            assertEquals(2, suite.size());
        } finally {
            service.close();
        }
    }

    private static LlmService createService(LlmService.ChatLanguageModel model, int budget) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "baseline-integration");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
