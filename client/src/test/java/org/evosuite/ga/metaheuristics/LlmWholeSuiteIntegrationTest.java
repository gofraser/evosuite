package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.Properties.LlmSuiteInjectionPolicy;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.llm.search.AsyncLlmTestProducer;
import org.evosuite.llm.search.LlmInjectionAdapter;
import org.evosuite.llm.search.StagnationDetector;
import org.evosuite.llm.search.TestSuiteChromosomeInjectionAdapter;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WholeSuite (StandardGA/MonotonicGA) LLM injection.
 * Verifies:
 * - suite-level adapter is auto-detected
 * - uncovered-goal supplier produces goals for suite populations
 * - injected tests are wrapped in TestSuiteChromosome (never raw TestChromosome)
 * - injection is not silently disabled due to empty-goal issue
 */
class LlmWholeSuiteIntegrationTest {

    private LlmSuiteInjectionPolicy originalPolicy;
    private int originalPopulation;

    @BeforeEach
    void setUp() {
        originalPolicy = Properties.LLM_SUITE_INJECTION_POLICY;
        originalPopulation = Properties.POPULATION;
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_SUITE_INJECTION_POLICY = originalPolicy;
        Properties.POPULATION = originalPopulation;
    }

    @Test
    void autoDetectsTestSuiteChromosomeAdapter() {
        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();

        LlmInjectionAdapter<?> adapter = ga.createDefaultInjectionAdapter();

        assertNotNull(adapter, "should auto-detect adapter for suite population");
        assertTrue(adapter instanceof TestSuiteChromosomeInjectionAdapter,
                "should be TestSuiteChromosomeInjectionAdapter, got: " + adapter.getClass().getName());
    }

    @Test
    void suiteGoalSupplierUsesTestLevelGoalsFromCriteria() {
        // In a real run, getUncoveredGoalsForSuiteChromosomes() derives goals from
        // FitnessFunctions.getFitnessFactory(criterion).getCoverageGoals().
        // In unit test context without instrumentation, this produces empty goals
        // (no class loaded). Verify the mechanism: when allTestLevelGoals is empty,
        // the supplier returns empty; when pre-populated, it returns uncovered goals.
        SuiteGAWithGoals ga = new SuiteGAWithGoals(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();

        // Without pre-population, goals are empty (no instrumented class)
        Collection<TestFitnessFunction> empty = ga.getUncoveredGoalsForSuiteChromosomes();
        // This is expected in test context — verify it doesn't crash
        assertNotNull(empty);

        // Pre-populate with known goals to test the filtering mechanism
        ga.preloadTestLevelGoals(new DummyTestFitness(), new DummyTestFitness());
        Collection<TestFitnessFunction> goals = ga.getUncoveredGoalsForSuiteChromosomes();
        assertEquals(2, goals.size(),
                "should return pre-loaded test-level goals when none are covered");
    }

    @Test
    void testChromosomeGoalSupplierReturnsEmptyForSuitePopulation() {
        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();

        Collection<TestFitnessFunction> goals = ga.getUncoveredGoalsForTestChromosomes();

        assertTrue(goals.isEmpty(),
                "getUncoveredGoalsForTestChromosomes should return empty for suite population "
                        + "(this is the bug the suite supplier fixes)");
    }

    @Test
    void stagnationInjectionProducesTestSuiteChromosomes() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.NEW_SUITE;
        Properties.POPULATION = 10;

        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();
        ga.setAdapter(new TestSuiteChromosomeInjectionAdapter());
        ga.setDetector(new AlwaysInjectingStagnationDetector());

        int before = ga.getPopulation().size();
        ga.triggerStagnationInjection();

        assertTrue(ga.getPopulation().size() > before,
                "stagnation injection should add to population");
        for (Object member : ga.getPopulation()) {
            assertTrue(member instanceof TestSuiteChromosome,
                    "all population members must be TestSuiteChromosome, got: "
                            + member.getClass().getName());
        }
    }

    @Test
    void asyncInjectionProducesTestSuiteChromosomes() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.NEW_SUITE;
        Properties.POPULATION = 10;

        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();
        ga.setAdapter(new TestSuiteChromosomeInjectionAdapter());
        ga.setAsyncProducer(new PreloadedAsyncProducer());

        int before = ga.getPopulation().size();
        ga.triggerAsyncIntegration();

        assertTrue(ga.getPopulation().size() > before,
                "async integration should add to population");
        for (Object member : ga.getPopulation()) {
            assertTrue(member instanceof TestSuiteChromosome,
                    "all population members must be TestSuiteChromosome, got: "
                            + member.getClass().getName());
        }
    }

    @Test
    void mergePolicyMergesIntoExistingSuite() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.MERGE_INTO_EXISTING;
        Properties.POPULATION = 10;

        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();
        ga.setAdapter(new TestSuiteChromosomeInjectionAdapter());
        ga.setDetector(new AlwaysInjectingStagnationDetector());

        int popBefore = ga.getPopulation().size();
        ga.triggerStagnationInjection();

        assertEquals(popBefore, ga.getPopulation().size(),
                "MERGE_INTO_EXISTING should not change population size");
    }

    @Test
    void populationExtractsTestChromosomesFromSuites() {
        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();

        List<TestChromosome> tests = ga.getPopulationAsTestChromosomes();

        assertFalse(tests.isEmpty(),
                "getPopulationAsTestChromosomes should extract tests from suites");
        for (TestChromosome tc : tests) {
            assertNotNull(tc, "extracted test should not be null");
        }
    }

    // --- Test infrastructure ---

    private static TestSuiteChromosome newSuite() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(new DefaultTestCase());
        return suite;
    }

    /**
     * Minimal StandardGA subclass for testing WholeSuite LLM integration
     * without running actual evolution.
     */
    private static class SuiteGA extends StandardGA<TestSuiteChromosome> {
        private static final long serialVersionUID = 1L;

        SuiteGA(ChromosomeFactory<TestSuiteChromosome> factory) {
            super(factory);
        }

        void seedPopulation() {
            population.clear();
            population.add(newSuite());
            population.add(newSuite());
        }

        @SuppressWarnings("unchecked")
        void setAdapter(LlmInjectionAdapter<TestSuiteChromosome> adapter) {
            this.llmInjectionAdapter = (LlmInjectionAdapter) adapter;
        }

        void setDetector(StagnationDetector detector) {
            this.stagnationDetector = detector;
        }

        void setAsyncProducer(AsyncLlmTestProducer producer) {
            this.asyncProducer = producer;
        }

        void triggerStagnationInjection() {
            List<TestFitnessFunction> goals = new ArrayList<>(getUncoveredGoalsForSuiteChromosomes());
            if (goals.isEmpty()) {
                goals.add(new DummyTestFitness());
            }
            maybeInjectOnStagnationByFitness(0.0, goals);
        }

        void triggerAsyncIntegration() {
            integrateAsyncTestsIntoPopulation();
        }

        @Override
        public void initializePopulation() {
            seedPopulation();
        }

        @Override
        protected void evolve() {
            currentIteration++;
        }

        @Override
        public boolean isFinished() {
            return currentIteration >= 1;
        }
    }

    /**
     * SuiteGA subclass that allows pre-loading test-level goals for verifying
     * the uncovered-goals supplier mechanism without real class instrumentation.
     */
    private static class SuiteGAWithGoals extends SuiteGA {
        private static final long serialVersionUID = 1L;

        SuiteGAWithGoals(ChromosomeFactory<TestSuiteChromosome> factory) {
            super(factory);
        }

        void preloadTestLevelGoals(TestFitnessFunction... goals) {
            this.allTestLevelGoals = new ArrayList<>();
            Collections.addAll(this.allTestLevelGoals, goals);
        }
    }

    private static class AlwaysInjectingStagnationDetector extends StagnationDetector {
        AlwaysInjectingStagnationDetector() {
            super(false);
        }

        @Override
        public boolean checkStagnation(double currentBestFitness) {
            return true;
        }

        @Override
        public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                                List<TestChromosome> currentPopulation) {
            TestChromosome tc = new TestChromosome();
            tc.setTestCase(new DefaultTestCase());
            return Collections.singletonList(tc);
        }
    }

    private static class PreloadedAsyncProducer extends AsyncLlmTestProducer {
        PreloadedAsyncProducer() {
            super(Collections::emptyList);
        }

        @Override
        public void start() { }

        @Override
        public void stop() { }

        @Override
        public List<TestChromosome> drainAvailable() {
            TestChromosome tc = new TestChromosome();
            tc.setTestCase(new DefaultTestCase());
            return Collections.singletonList(tc);
        }
    }

    private static class DummySuiteFitness extends TestSuiteFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestSuiteChromosome individual) {
            double f = 1.0;
            updateIndividual(individual, f);
            return f;
        }
    }

    private static class DummyTestFitness extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return 1.0;
        }

        @Override
        public double getFitness(TestChromosome individual) {
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
            return 42;
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
