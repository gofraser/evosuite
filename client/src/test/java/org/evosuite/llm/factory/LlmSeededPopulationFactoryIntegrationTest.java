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
package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    private static final String JUNIT_RESPONSE_WITH_STATEMENT =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "    String value = \"llm-seed\";\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    @BeforeEach
    void resetMetrics() {
        LlmStatistics.resetSeedingCounters();
        LlmStatistics.resetEnrichmentElapsedMs();
    }

    @AfterEach
    void clearMetrics() {
        LlmStatistics.resetSeedingCounters();
        LlmStatistics.resetEnrichmentElapsedMs();
    }

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
    void initialPopulationSeedStatementsAreMarkedAsParsedFromLlm() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.SEEDING, JUNIT_RESPONSE_WITH_STATEMENT);
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

            TestChromosome seed = factory.getChromosome();

            assertNotSame(fallbackChromosome, seed, "chromosome should come from LLM seeding");
            assertTrue(seed.getTestCase().size() > 0, "seed should contain parsed statements");
            for (int i = 0; i < seed.getTestCase().size(); i++) {
                assertTrue(seed.getTestCase().getStatement(i).isParsedFromLlm(),
                        "initial population seed statement " + i + " should carry LLM provenance");
            }
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
            assertEquals(1L, LlmStatistics.getInitialPopulationCandidatesValidated());
            assertEquals(1L, LlmStatistics.getInitialPopulationCandidatesQueued());
            assertTrue(factory.awaitAndDrainSeeds(1000L).isEmpty(),
                    "draining again should not re-merge the same async seed");

            TestChromosome next = factory.getChromosome();
            assertSame(fallbackChromosome, next,
                    "getChromosome after draining should use fallback and not duplicate drained seed");
        } finally {
            service.close();
        }
    }

    @Test
    void afterTimeoutSubsequentCallsFallBackToFallbackFactoryWithoutThrowing() throws Exception {
        // A model that blocks forever — the factory should time out cleanly and
        // subsequent calls must NOT propagate CancellationException from the
        // cancelled future, nor re-log "LLM seeding failed".
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        LlmService.ChatLanguageModel blockingModel = (messages, feature) -> {
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LlmService.LlmResponse.fromText("");
        };
        LlmService service = createService(blockingModel, 4);

        TestChromosome fallbackChromosome = new TestChromosome();
        fallbackChromosome.setTestCase(new DefaultTestCase());
        ChromosomeFactory<TestChromosome> fallback = () -> fallbackChromosome;

        try {
            // Use a real async executor so the blocking model runs off the test thread.
            LlmSeededPopulationFactory factory = new LlmSeededPopulationFactory(
                    fallback,
                    service,
                    Collections::emptyList,
                    java.util.concurrent.ForkJoinPool.commonPool());

            // First await with a tiny timeout — should time out and cancel.
            assertTrue(factory.awaitAndDrainSeeds(50L).isEmpty(),
                    "first call should time out and yield no seeds");

            // Subsequent calls must not throw and must fall back cleanly.
            for (int i = 0; i < 3; i++) {
                TestChromosome chromosome = factory.getChromosome();
                assertSame(fallbackChromosome, chromosome,
                        "post-timeout calls should return the fallback chromosome");
            }
            // A subsequent await must also be a no-op (not throw).
            assertTrue(factory.awaitAndDrainSeeds(10L).isEmpty(),
                    "re-await after timeout must not throw");
        } finally {
            released.countDown();
            service.close();
        }
    }

    @Test
    void timeoutRetainsValidatedPartialSeedsFromEarlierRepairTurn() throws Exception {
        // brokenTest instantiates the abstract SUT via an anonymous body that
        // does NOT implement the required abstract method. The SUT-aware
        // parser cannot synthesize the body and substitutes a typed null,
        // which it demotes to an ERROR-severity diagnostic
        // (ANONYMOUS_ABSTRACT_TYPED_NULL_FALLBACK). That lands the test in
        // `droppedAtParse`, which triggers the repair turn this test verifies.
        // We avoid java.* abstract classes here so the in-memory parse-compile
        // probe doesn't collide with sealed JDK module packages (java.base).
        String partialResponse =
                "```java\n" +
                        "import org.junit.Test;\n" +
                        "import org.junit.runner.Runner;\n" +
                        "public class GeneratedLlmTest {\n" +
                        "  @Test\n" +
                        "  public void keptTest() {\n" +
                        "    String marker = \"keptTest-marker\";\n" +
                        "  }\n" +
                        "  @Test\n" +
                        "  public void brokenTest() {\n" +
                        "    Runner r = new Runner() {\n" +
                        "    };\n" +
                        "    r.testCount();\n" +
                        "  }\n" +
                        "}\n" +
                        "```";
        java.util.concurrent.CountDownLatch repairRequested = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            if (calls.incrementAndGet() == 1) {
                return LlmService.LlmResponse.fromText(partialResponse);
            }
            repairRequested.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LlmService.LlmResponse.fromText("");
        };
        LlmService service = createService(model, 4);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

        TestChromosome fallbackChromosome = new TestChromosome();
        fallbackChromosome.setTestCase(new DefaultTestCase());
        ChromosomeFactory<TestChromosome> fallback = () -> fallbackChromosome;

        String previousTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = "org.junit.runner.Runner";

            LlmSeededPopulationFactory factory = new LlmSeededPopulationFactory(
                    fallback,
                    service,
                    Collections::emptyList,
                    executor);

            assertTrue(repairRequested.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "repair query should be reached after the valid test is retained");
            java.util.List<TestChromosome> seeds = factory.awaitAndDrainSeeds(50L);

            assertEquals(1, seeds.size(),
                    "orchestrator timeout should retain the already validated partial seed");
            assertEquals(0L, LlmStatistics.getInitialPopulationCandidatesValidated(),
                    "a timed-out final repair has no final validated result");
            assertEquals(1L, LlmStatistics.getInitialPopulationCandidatesQueued(),
                    "the retained partial seed is still queued");
            assertNotSame(fallbackChromosome, seeds.get(0));
            assertTrue(seeds.get(0).getTestCase().toCode().contains("keptTest-marker"),
                    "Retained partial seed should be the kept test, identified by its marker literal");
        } finally {
            Properties.TARGET_CLASS = previousTargetClass;
            released.countDown();
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    void recoverableLinkageErrorsDuringSeedingFallBackToDefaultFactory() {
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            throw new VerifyError("simulated frame verification failure");
        };
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

            TestChromosome produced = factory.getChromosome();
            assertSame(fallbackChromosome, produced,
                    "Recoverable linkage errors should not abort factory use");
            assertTrue(factory.awaitAndDrainSeeds(1000L).isEmpty(),
                    "No seeds should be produced after recoverable linkage error");
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
