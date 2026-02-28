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
package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class LlmSeededPopulationFactoryIntegrationTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    @Test
    void seedsAreConsumedBeforeFallbackFactory() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.SEEDING, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 4);

        TestChromosome fallbackChromosome = new TestChromosome();
        fallbackChromosome.setTestCase(new DefaultTestCase());
        ChromosomeFactory<TestChromosome> fallback = () -> fallbackChromosome;

        try {
            LlmSeededPopulationFactory factory = new LlmSeededPopulationFactory(
                    fallback,
                    service,
                    Collections::emptyList,
                    Runnable::run);

            TestChromosome first = factory.getChromosome();
            TestChromosome second = factory.getChromosome();

            assertNotSame(fallbackChromosome, first, "first chromosome should come from LLM seeding");
            assertSame(fallbackChromosome, second, "second chromosome should use fallback after seeds are consumed");
        } finally {
            service.close();
        }
    }

    @Test
    void awaitAndGetChromosomeDoNotDoubleMergeSeed() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.SEEDING, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 4);

        TestChromosome fallbackChromosome = new TestChromosome();
        fallbackChromosome.setTestCase(new DefaultTestCase());
        ChromosomeFactory<TestChromosome> fallback = () -> fallbackChromosome;

        try {
            LlmSeededPopulationFactory factory = new LlmSeededPopulationFactory(
                    fallback,
                    service,
                    Collections::emptyList,
                    Runnable::run);

            assertEquals(1, factory.awaitAndDrainSeeds(1000L).size(),
                    "seed should be merged exactly once when awaiting");
            assertTrue(factory.awaitAndDrainSeeds(1000L).isEmpty(),
                    "draining again should not re-merge the same async seed");

            TestChromosome next = factory.getChromosome();
            assertSame(fallbackChromosome, next,
                    "getChromosome after draining should use fallback and not duplicate drained seed");
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
                "seed-integration");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
