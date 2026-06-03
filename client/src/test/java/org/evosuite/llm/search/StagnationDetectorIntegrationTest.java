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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StagnationDetectorIntegrationTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";
    private static final String MULTI_BLOCK_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void firstGeneratedTest() {\n" +
                    "    int x = 1;\n" +
                    "  }\n" +
                    "}\n" +
                    "```\n" +
                    "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void secondGeneratedTest() {\n" +
                    "    int y = 2;\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    @Test
    void triggersAndRequestsHelpAfterStagnation() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("uncovered-goal");

        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        try {
            assertFalse(detector.checkStagnation(1.0));
            // Advance past the 1s threshold; same fitness → no improvement → fires.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            assertTrue(detector.checkStagnation(1.0));

            List<TestChromosome> help = detector.requestHelp(
                    Collections.singleton(goal),
                    Collections.singletonList(new TestChromosome()));
            assertFalse(help.isEmpty(), "stagnation detector should inject at least one chromosome");
        } finally {
            service.close();
        }
    }

    @Test
    void returnsAllParsedTestsEvenWhenLlmOverProduces() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, MULTI_BLOCK_RESPONSE);
        LlmService service = createService(model, 2);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("uncovered-goal");

        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        try {
            List<TestChromosome> help = detector.requestHelp(
                    Collections.singleton(goal),
                    Collections.singletonList(new TestChromosome()));

            assertEquals(2, help.size(),
                    "stagnation detector should keep all tests parsed from all code blocks");
        } finally {
            service.close();
        }
    }

    @Test
    void diagnosticPromptRequestsExplorationMatrixWithoutNumericBudget() throws Exception {
        MockChatLanguageModel model = new MockChatLanguageModel();
        LlmService service = createService(model, 2);
        StagnationDetector detector = new StagnationDetector(service, false, 5, 1, System::nanoTime);
        try {
            Method method = StagnationDetector.class
                    .getDeclaredMethod("buildDiagnosticInstruction", int.class, int.class, int.class);
            method.setAccessible(true);
            String prompt = (String) method.invoke(detector, 18, 17, 3);

            assertTrue(prompt.contains("Generate the smallest useful exploration matrix needed to cover distinct regimes."));
            assertTrue(prompt.contains("Prefer a few short variants that each change exactly one axis."));
            assertTrue(prompt.contains("Stop when additional tests would only duplicate an already-covered regime."));
            assertFalse(prompt.contains("Return up to "));
            assertFalse(prompt.contains("Generate 3 JUnit tests"));
            assertFalse(prompt.contains("Generate 5 JUnit tests"));
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
                "stagnation-integration");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
