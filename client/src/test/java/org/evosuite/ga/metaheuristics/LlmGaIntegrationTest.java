package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.search.AsyncLlmTestProducer;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmGaIntegrationTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    private boolean originalAsyncProducer;
    private int originalQueueSize;
    private int originalRefreshInterval;
    private int originalProducerDelay;
    private boolean originalOnStagnation;
    private int originalStagnationGenerations;
    private int originalStagnationTests;
    private int originalPopulation;

    @BeforeEach
    void setUp() {
        originalAsyncProducer = Properties.LLM_ASYNC_PRODUCER;
        originalQueueSize = Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE;
        originalRefreshInterval = Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL;
        originalProducerDelay = Properties.LLM_ASYNC_PRODUCER_DELAY_MS;
        originalOnStagnation = Properties.LLM_ON_STAGNATION;
        originalStagnationGenerations = Properties.LLM_STAGNATION_GENERATIONS;
        originalStagnationTests = Properties.LLM_STAGNATION_TESTS;
        originalPopulation = Properties.POPULATION;
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_ASYNC_PRODUCER = originalAsyncProducer;
        Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE = originalQueueSize;
        Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL = originalRefreshInterval;
        Properties.LLM_ASYNC_PRODUCER_DELAY_MS = originalProducerDelay;
        Properties.LLM_ON_STAGNATION = originalOnStagnation;
        Properties.LLM_STAGNATION_GENERATIONS = originalStagnationGenerations;
        Properties.LLM_STAGNATION_TESTS = originalStagnationTests;
        Properties.POPULATION = originalPopulation;
        LlmService.resetInstanceForTesting();
    }

    @Test
    void standardGaIntegratesAsyncAndStagnationTestsAndCleansProducerThread() {
        Properties.LLM_ASYNC_PRODUCER = true;
        Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE = 4;
        Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL = 1;
        Properties.LLM_ASYNC_PRODUCER_DELAY_MS = 0;
        Properties.LLM_ON_STAGNATION = true;
        Properties.LLM_STAGNATION_GENERATIONS = 1;
        Properties.LLM_STAGNATION_TESTS = 1;
        Properties.POPULATION = 10;

        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.ASYNC_PRODUCER, SIMPLE_JUNIT_RESPONSE);
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        model.enqueue(LlmFeature.ASYNC_PRODUCER, SIMPLE_JUNIT_RESPONSE);
        LlmService.setInstanceForTesting(createService(model, 4));

        int baselineThreads = countAsyncProducerThreads();

        ControlledStandardGA ga = new ControlledStandardGA(() -> new TestChromosome());
        ga.addFitnessFunction(new DummyTestFitness());
        ga.generateSolution();

        assertTrue(ga.getPopulation().size() > 1,
                "population should include LLM-injected chromosomes from async/stagnation paths");
        waitForAsyncProducerThreads(baselineThreads, 2000L);
        assertTrue(countAsyncProducerThreads() <= baselineThreads, "async producer thread should be cleaned up");
    }

    @Test
    void standardGaShutsDownLlmAssistanceWhenEvolutionThrows() {
        ThrowingStandardGA ga = new ThrowingStandardGA(() -> new TestChromosome());
        ga.addFitnessFunction(new DummyTestFitness());

        assertThrows(RuntimeException.class, ga::generateSolution);
        assertTrue(ga.fakeProducer.stopped, "shutdown should stop async producer in finally");
    }

    @Test
    void monotonicGaShutsDownLlmAssistanceWhenEvolutionThrows() {
        ThrowingMonotonicGA ga = new ThrowingMonotonicGA(() -> new TestChromosome());
        ga.addFitnessFunction(new DummyTestFitness());

        assertThrows(RuntimeException.class, ga::generateSolution);
        assertTrue(ga.fakeProducer.stopped, "shutdown should stop async producer in finally");
    }

    private static int countAsyncProducerThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != null && thread.isAlive() && thread.getName().startsWith("LLM-AsyncProducer")) {
                count++;
            }
        }
        return count;
    }

    private static void waitForAsyncProducerThreads(int expectedMax, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countAsyncProducerThreads() <= expectedMax) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
                "ga-integration");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }

    private static class ControlledStandardGA extends StandardGA<TestChromosome> {
        private static final long serialVersionUID = 1L;
        private int iterations;

        private ControlledStandardGA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        @Override
        public void initializePopulation() {
            notifySearchStarted();
            currentIteration = 0;
            population.clear();
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(new DefaultTestCase());
            population.add(chromosome);
            calculateFitnessAndSortPopulation();
            notifyIteration();
        }

        @Override
        protected void evolve() {
            currentIteration++;
            iterations++;
        }

        @Override
        public boolean isFinished() {
            return iterations >= 2;
        }
    }

    private static class ThrowingStandardGA extends StandardGA<TestChromosome> {
        private static final long serialVersionUID = 1L;
        private final FakeAsyncProducer fakeProducer = new FakeAsyncProducer();

        private ThrowingStandardGA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        @Override
        public void initializePopulation() {
            notifySearchStarted();
            currentIteration = 0;
            population.clear();
            population.add(new TestChromosome());
            notifyIteration();
        }

        @Override
        protected void initializeLlmAssistance(java.util.function.Supplier<java.util.Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                               boolean maximizationObjective) {
            this.asyncProducer = fakeProducer;
        }

        @Override
        protected void evolve() {
            throw new RuntimeException("forced failure");
        }

        @Override
        public boolean isFinished() {
            return false;
        }
    }

    private static class ThrowingMonotonicGA extends MonotonicGA<TestChromosome> {
        private static final long serialVersionUID = 1L;
        private final FakeAsyncProducer fakeProducer = new FakeAsyncProducer();

        private ThrowingMonotonicGA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        @Override
        public void initializePopulation() {
            notifySearchStarted();
            currentIteration = 0;
            population.clear();
            population.add(new TestChromosome());
            notifyIteration();
        }

        @Override
        protected void initializeLlmAssistance(java.util.function.Supplier<java.util.Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                               boolean maximizationObjective) {
            this.asyncProducer = fakeProducer;
        }

        @Override
        protected void evolve() {
            throw new RuntimeException("forced failure");
        }

        @Override
        public boolean isFinished() {
            return false;
        }
    }

    private static class FakeAsyncProducer extends AsyncLlmTestProducer {
        private boolean stopped;

        private FakeAsyncProducer() {
            super(Collections::emptyList);
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public List<TestChromosome> drainAvailable() {
            return Collections.emptyList();
        }
    }

    private static class DummyTestFitness extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            updateIndividual(individual, 1.0);
            return 1.0;
        }

        @Override
        public double getFitness(TestChromosome individual) {
            updateIndividual(individual, 1.0);
            return 1.0;
        }

        @Override
        public boolean isCovered(TestChromosome tc) {
            return false;
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return 0;
        }

        @Override
        public int hashCode() {
            return 31;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof DummyTestFitness;
        }

        @Override
        public String getTargetClass() {
            return "Dummy";
        }

        @Override
        public String getTargetMethod() {
            return "dummyMethod";
        }
    }
}
