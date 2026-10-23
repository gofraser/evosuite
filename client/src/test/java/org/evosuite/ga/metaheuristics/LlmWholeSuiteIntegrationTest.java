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
package org.evosuite.ga.metaheuristics;

import com.examples.with.different.packagename.FlagExample1;
import org.evosuite.Properties;
import org.evosuite.Properties.LlmSuiteInjectionPolicy;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.search.AsyncLlmTestProducer;
import org.evosuite.llm.search.AttemptTicket;
import org.evosuite.llm.search.AttributedCandidate;
import org.evosuite.llm.search.DiagnosticCardSelector;
import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.llm.search.LlmInjectionAdapter;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.RepeatedInjectionTarget;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WholeSuite (StandardGA/MonotonicGA) LLM injection.
 * Verifies:
 * - suite-level adapter can be explicitly configured
 * - uncovered-goal supplier produces goals for suite populations
 * - injected tests are wrapped in TestSuiteChromosome (never raw TestChromosome)
 * - injection is not silently disabled due to empty-goal issue
 */
class LlmWholeSuiteIntegrationTest {

    private LlmSuiteInjectionPolicy originalPolicy;
    private int originalPopulation;
    private String originalTargetClass;
    private Properties.Criterion[] originalCriterion;
    private boolean originalSandbox;

    @BeforeEach
    void setUp() {
        originalPolicy = Properties.LLM_SUITE_INJECTION_POLICY;
        originalPopulation = Properties.POPULATION;
        originalTargetClass = Properties.TARGET_CLASS;
        originalCriterion = Properties.CRITERION;
        originalSandbox = Properties.SANDBOX;
        Properties.SANDBOX = false;
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.TARGET_CLASS = FlagExample1.class.getName();
        Properties.resetTargetClass();
        assertNotNull(Properties.getTargetClassAndDontInitialise(),
                "the integration-test fixture should be loadable");
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.BRANCH};
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_SUITE_INJECTION_POLICY = originalPolicy;
        Properties.POPULATION = originalPopulation;
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CRITERION = originalCriterion;
        Properties.SANDBOX = originalSandbox;
        Properties.resetTargetClass();
        ClassPathHandler.resetSingleton();
    }

    @Test
    void acceptsExplicitTestSuiteChromosomeAdapter() {
        SuiteGA ga = new SuiteGA(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.setAdapter(new TestSuiteChromosomeInjectionAdapter());

        LlmInjectionAdapter<?> adapter = ga.getLlmInjectionAdapter();

        assertNotNull(adapter, "should expose explicitly configured adapter");
        assertTrue(adapter instanceof TestSuiteChromosomeInjectionAdapter,
                "should be TestSuiteChromosomeInjectionAdapter, got: " + adapter.getClass().getName());
    }

    @Test
    void suiteGoalSupplierUsesTestLevelGoalsFromCriteria() {
        // In a real run, getUncoveredGoalsForSuiteChromosomes() derives goals from
        // FitnessFunctions.getFitnessFactory(criterion).getCoverageGoals().
        // This fixture is loaded but not analyzed, so no call graph-backed goals
        // are available. Verify the mechanism: when allTestLevelGoals is empty,
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
    void asyncInjectionSettlesItsAttemptWithTheCoverageItGains() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.NEW_SUITE;
        Properties.POPULATION = 10;
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal-a", "fp");
        AttemptTicket ticket = AttemptTicket.open(memory, Collections.singletonList(target), true);
        assertTrue(isInFlight(memory, target), "the open attempt keeps its goal in flight");

        SuiteGAWithGoals ga = new SuiteGAWithGoals(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();
        ga.preloadTestLevelGoals(new CoveredTestFitness("covered-1"), new CoveredTestFitness("covered-2"),
                new DummyTestFitness());
        ga.setAdapter(new TestSuiteChromosomeInjectionAdapter());
        ga.setAsyncProducer(new PreloadedAsyncProducer(ticket, 2));

        ga.triggerAsyncIntegration();

        assertEquals(AttemptTicket.State.CLOSED, ticket.getState(),
                "the drained attempt must be settled once its tests are injected");
        assertEquals(2, ticket.getGainedGoals(),
                "both candidates cover the same two goals, which are credited once");
        assertFalse(isInFlight(memory, target), "the settled attempt no longer keeps its goal in flight");
    }

    @Test
    void asyncInjectionClosesAnAttemptWithoutGainWhenItCoversNothingNew() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.NEW_SUITE;
        Properties.POPULATION = 10;
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal-a", "fp");
        AttemptTicket ticket = AttemptTicket.open(memory, Collections.singletonList(target), true);

        SuiteGAWithGoals ga = new SuiteGAWithGoals(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();
        ga.preloadTestLevelGoals(new DummyTestFitness());
        ga.setAdapter(new TestSuiteChromosomeInjectionAdapter());
        ga.setAsyncProducer(new PreloadedAsyncProducer(ticket, 1));

        ga.triggerAsyncIntegration();

        assertEquals(AttemptTicket.State.CLOSED, ticket.getState());
        assertEquals(0, ticket.getGainedGoals());
        assertFalse(isInFlight(memory, target), "the settled attempt no longer keeps its goal in flight");
    }

    @Test
    void asyncInjectionReleasesAnAttemptWhoseTestsAreDropped() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal-a", "fp");
        AttemptTicket ticket = AttemptTicket.open(memory, Collections.singletonList(target), true);

        SuiteGAWithGoals ga = new SuiteGAWithGoals(() -> newSuite());
        ga.addFitnessFunction(new DummySuiteFitness());
        ga.seedPopulation();
        ga.preloadTestLevelGoals(new CoveredTestFitness("covered-1"));
        ga.setAsyncProducer(new PreloadedAsyncProducer(ticket, 1));

        ga.triggerAsyncIntegration();

        assertEquals(AttemptTicket.State.RELEASED, ticket.getState(),
                "without an injection adapter the tests never enter the search, so there is no outcome");
        assertFalse(isInFlight(memory, target), "the released attempt no longer keeps its goal in flight");
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
    void standardGaKeepsAnInjectedSuiteThatBeatsAFullPopulation() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.NEW_SUITE;
        Properties.POPULATION = 3;
        StandardGA<TestSuiteChromosome> ga = newEvaluatedStandardGa();
        AlwaysInjectingStagnationDetector detector = new AlwaysInjectingStagnationDetector(2);
        ga.stagnationDetector = detector;

        ga.maybeInjectOnStagnationByFitness(ga.getBestIndividual().getFitness(),
                Collections.singletonList(new DummyTestFitness()));

        assertEquals(3, ga.getPopulation().size(), "the population stays at its limit");
        TestSuiteChromosome injected = suiteHolding(ga.getPopulation(), detector.lastTests.get(0));
        assertNotNull(injected, "the suite of two LLM tests is fitter than any one-test suite, so it must survive");
        assertEquals(8.0, injected.getFitness(), "the injected suite is evaluated before it competes");
        assertSame(injected, ga.getBestIndividual());
    }

    @Test
    void standardGaKeepsTheSuiteOfDrainedAsyncTestsWhenItBeatsAFullPopulation() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.NEW_SUITE;
        Properties.POPULATION = 3;
        StandardGA<TestSuiteChromosome> ga = newEvaluatedStandardGa();
        PreloadedAsyncProducer producer = new PreloadedAsyncProducer(null, 2);
        ga.asyncProducer = producer;

        ga.integrateAsyncTestsIntoPopulation();

        assertEquals(3, ga.getPopulation().size(), "the population stays at its limit");
        TestSuiteChromosome injected = suiteHolding(ga.getPopulation(), producer.lastTests.get(0));
        assertNotNull(injected, "the suite of two drained tests is fitter than any one-test suite, so it must survive");
        assertEquals(8.0, injected.getFitness());
    }

    @Test
    void standardGaReevaluatesTheSuiteItMergesLlmTestsInto() {
        Properties.LLM_SUITE_INJECTION_POLICY = LlmSuiteInjectionPolicy.MERGE_INTO_EXISTING;
        Properties.POPULATION = 3;
        StandardGA<TestSuiteChromosome> ga = newEvaluatedStandardGa();
        AlwaysInjectingStagnationDetector detector = new AlwaysInjectingStagnationDetector(2);
        ga.stagnationDetector = detector;

        ga.maybeInjectOnStagnationByFitness(ga.getBestIndividual().getFitness(),
                Collections.singletonList(new DummyTestFitness()));

        assertEquals(3, ga.getPopulation().size());
        TestSuiteChromosome merged = suiteHolding(ga.getPopulation(), detector.lastTests.get(0));
        assertNotNull(merged);
        assertEquals(3, merged.size(), "the two LLM tests join the suite's own test");
        assertEquals(7.0, merged.getFitness(),
                "selection must see the fitness of the merged suite, not the one it had before");
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

    private static boolean isInFlight(RepeatedInjectionMemory memory, RepeatedInjectionTarget target) {
        return memory.adjustPriority(target, 1.0, true).getDiscardReason()
                == DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT;
    }

    /** A real StandardGA whose full initial population of one-test suites has been evaluated. */
    private static StandardGA<TestSuiteChromosome> newEvaluatedStandardGa() {
        StandardGA<TestSuiteChromosome> ga = new StandardGA<>(LlmWholeSuiteIntegrationTest::newSuite);
        ga.addFitnessFunction(new SizeRewardingSuiteFitness());
        ga.setLlmInjectionAdapter(new TestSuiteChromosomeInjectionAdapter());
        ga.initializePopulation();
        assertEquals(Properties.POPULATION, ga.getPopulation().size());
        assertEquals(9.0, ga.getBestIndividual().getFitness());
        return ga;
    }

    private static TestSuiteChromosome suiteHolding(List<TestSuiteChromosome> population, TestChromosome test) {
        for (TestSuiteChromosome suite : population) {
            for (TestChromosome member : suite.getTestChromosomes()) {
                if (member == test) {
                    return suite;
                }
            }
        }
        return null;
    }

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

        @Override
        public void seedPopulation() {
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
        private final int tests;
        final List<TestChromosome> lastTests = new ArrayList<>();

        AlwaysInjectingStagnationDetector() {
            this(1);
        }

        AlwaysInjectingStagnationDetector(int tests) {
            super(false);
            this.tests = tests;
        }

        @Override
        public boolean checkStagnation(double currentBestFitness) {
            return true;
        }

        @Override
        public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                                List<TestChromosome> currentPopulation,
                                                int totalGoals, int coveredGoalCount,
                                                java.util.Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
            lastTests.clear();
            for (int i = 0; i < tests; i++) {
                TestChromosome tc = new TestChromosome();
                tc.setTestCase(new DefaultTestCase());
                lastTests.add(tc);
            }
            return new ArrayList<>(lastTests);
        }
    }

    private static class PreloadedAsyncProducer extends AsyncLlmTestProducer {
        private final AttemptTicket ticket;
        private final int candidates;
        final List<TestChromosome> lastTests = new ArrayList<>();

        PreloadedAsyncProducer() {
            this(null, 1);
        }

        PreloadedAsyncProducer(AttemptTicket ticket, int candidates) {
            super(Collections::emptyList, null, null, 1, 1, 0, null);
            this.ticket = ticket;
            this.candidates = candidates;
        }

        @Override
        public void start() { }

        @Override
        public void stop() { }

        @Override
        public List<AttributedCandidate> drainAvailable() {
            InjectionAttemptMetadata metadata = ticket == null ? null
                    : new InjectionAttemptMetadata(ticket, Collections.emptyList());
            List<AttributedCandidate> drained = new ArrayList<>();
            lastTests.clear();
            for (int i = 0; i < candidates; i++) {
                TestChromosome tc = new TestChromosome();
                tc.setTestCase(new DefaultTestCase());
                lastTests.add(tc);
                drained.add(new AttributedCandidate(tc, metadata));
            }
            return drained;
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

    /** Rewards larger suites: each test lowers the fitness of ten by one. */
    private static class SizeRewardingSuiteFitness extends TestSuiteFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestSuiteChromosome individual) {
            double f = 10.0 - individual.size();
            updateIndividual(individual, f);
            return f;
        }
    }

    /** A goal every test covers, told apart from others by its name. */
    private static class CoveredTestFitness extends DummyTestFitness {
        private static final long serialVersionUID = 1L;

        private final String name;

        CoveredTestFitness(String name) {
            this.name = name;
        }

        @Override
        public boolean isCovered(TestChromosome tc) {
            return true;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CoveredTestFitness && ((CoveredTestFitness) obj).name.equals(name);
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
            return obj != null && obj.getClass() == DummyTestFitness.class;
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
