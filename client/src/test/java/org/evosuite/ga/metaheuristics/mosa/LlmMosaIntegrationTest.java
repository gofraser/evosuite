package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.search.AsyncLlmTestProducer;
import org.evosuite.llm.search.LlmInjectionAdapter;
import org.evosuite.llm.search.StagnationDetector;
import org.evosuite.llm.search.TestChromosomeInjectionAdapter;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmMosaIntegrationTest {

    @Test
    void mosaStagnationInjectionPathAddsChromosomeToPopulation() {
        // Tests that the base-class maybeInjectOnStagnationByCoverage still
        // works via the adapter when called directly (backward compat)
        StagnationAwareMOSA mosa = new StagnationAwareMOSA(() -> new TestChromosome());
        mosa.seedPopulation();
        mosa.setDetector(new AlwaysInjectingStagnationDetector());
        mosa.setAdapter(new TestChromosomeInjectionAdapter());

        int before = mosa.getPopulation().size();
        mosa.triggerStagnationInjection();

        assertTrue(mosa.getPopulation().size() > before,
                "MOSA stagnation integration path should inject generated tests into population");
    }

    @Test
    void mosaInEvolveStagnationInjectionAddsToUnion() {
        // Tests that in-evolve stagnation injection via ExternalCandidateSource
        // adds candidates to the union so they participate in ranking/distillation
        InEvolveTestMOSA mosa = new InEvolveTestMOSA(() -> new TestChromosome());
        mosa.seedPopulation();
        // Register stagnation as an external candidate source
        mosa.externalCandidateSources.add(() -> {
            AlwaysInjectingStagnationDetector det = new AlwaysInjectingStagnationDetector();
            return det.requestHelp(mosa.getUncoveredGoals(), new ArrayList<>(mosa.getPopulation()));
        });

        mosa.evolve();

        assertTrue(mosa.lastUnionSize > mosa.getPopulation().size(),
                "union should include external-source-injected candidates (union="
                        + mosa.lastUnionSize + ", pop=" + mosa.getPopulation().size() + ")");
    }

    @Test
    void externalCandidateSourcesAreDrainedIntoUnion() {
        // Tests that collectExternalCandidates integrates candidates from
        // multiple registered sources into the union
        InEvolveTestMOSA mosa = new InEvolveTestMOSA(() -> new TestChromosome());
        mosa.seedPopulation();

        // Register two external sources
        mosa.externalCandidateSources.add(() -> Collections.singletonList(new TestChromosome()));
        mosa.externalCandidateSources.add(() -> {
            List<TestChromosome> batch = new ArrayList<>();
            batch.add(new TestChromosome());
            batch.add(new TestChromosome());
            return batch;
        });

        mosa.evolve();

        // 2 parent + 0 offspring + 3 external = 5
        assertEquals(5, mosa.lastUnionSize,
                "union should include all candidates from all external sources");
    }

    @Test
    void externalCandidateSourceFailureDoesNotBreakEvolve() {
        // Ensures that a failing external source doesn't crash evolution
        InEvolveTestMOSA mosa = new InEvolveTestMOSA(() -> new TestChromosome());
        mosa.seedPopulation();

        // A failing source followed by a working one
        mosa.externalCandidateSources.add(() -> { throw new RuntimeException("boom"); });
        mosa.externalCandidateSources.add(() -> Collections.singletonList(new TestChromosome()));

        assertDoesNotThrow(mosa::evolve);
        // 2 parent + 0 offspring + 1 from working source = 3
        assertEquals(3, mosa.lastUnionSize,
                "union should include candidates from working source despite earlier failure");
    }

    @Test
    void mosaShutsDownLlmAssistanceWhenGenerationThrows() {
        ThrowingMOSA mosa = new ThrowingMOSA(() -> new TestChromosome());

        assertThrows(RuntimeException.class, mosa::generateSolution);
        assertTrue(mosa.fakeProducer.stopped, "shutdown should stop async producer in finally");
    }

    @Test
    void dynaMosaShutsDownLlmAssistanceWhenGenerationThrows() {
        ThrowingDynaMOSA mosa = new ThrowingDynaMOSA(() -> new TestChromosome());

        assertThrows(RuntimeException.class, mosa::generateSolution);
        assertTrue(mosa.fakeProducer.stopped, "shutdown should stop async producer in finally");
    }

    @Test
    void dynaMosaLsImprovedTestsAreStagedForNextGeneration() {
        org.evosuite.Properties.LOCAL_SEARCH_RATE = 1;
        org.evosuite.Properties.LOCAL_SEARCH_PROBABILITY = 1.0;

        int[] calcFitnessCount = {0};
        LsPersistenceDynaMOSA harness = new LsPersistenceDynaMOSA(calcFitnessCount);
        new org.evosuite.ga.metaheuristics.mosa.MOSATestSuiteAdapter(harness);

        // Seed population
        harness.seedPopulation(new TestChromosome(), new TestChromosome());

        // Create suite with a pre-existing archive snapshot
        org.evosuite.testsuite.TestSuiteChromosome suite =
                new org.evosuite.testsuite.TestSuiteChromosome();
        suite.addTest(new TestChromosome()); // pre-LS snapshot — NOT staged

        // Simulate LS producing new tests
        TestChromosome lsTest1 = new TestChromosome();
        TestChromosome lsTest2 = new TestChromosome();
        harness.lsOutputTests = java.util.Arrays.asList(lsTest1, lsTest2);

        // Apply LS — only LS-produced delta should be staged
        harness.applyLocalSearch(suite);
        assertTrue(calcFitnessCount[0] > 0,
                "DynaMOSA LS re-evaluation should have called calculateFitness");

        // Now evolve — should drain pending LS tests into union
        harness.evolve();

        assertTrue(harness.lastUnion.contains(lsTest1),
                "DynaMOSA: LS-produced test 1 should be in next generation's union");
        assertTrue(harness.lastUnion.contains(lsTest2),
                "DynaMOSA: LS-produced test 2 should be in next generation's union");
        assertEquals(4, harness.lastUnion.size(),
                "Union should contain population (2) + LS-produced delta (2)");
    }

    /**
     * Exercises DynaMOSA's <em>real</em> {@code evolve()} (including
     * ranking, crowding distance, and population rebuild) to verify that
     * LS-produced tests staged by {@code applyLocalSearch(suite)} are
     * drained into the union via the production
     * {@code collectExternalCandidates} call inside {@code evolve()}.
     * <p>
     * Only {@code breedNextGeneration()}, {@code calculateFitness()},
     * and the no-arg {@code applyLocalSearch()} are overridden —
     * the {@code evolve()} method and {@code collectExternalCandidates}
     * run unmodified production code.
     */
    @Test
    void dynaMosaRealEvolveDrawnsLsTestsViaCollectExternalCandidates() {
        org.evosuite.Properties.LOCAL_SEARCH_RATE = 1;
        org.evosuite.Properties.LOCAL_SEARCH_PROBABILITY = 1.0;
        org.evosuite.Properties.POPULATION = 4;

        RealEvolveDynaMOSA harness = new RealEvolveDynaMOSA();
        new MOSATestSuiteAdapter(harness);

        // Add a minimal goal so the ranking preference front is non-empty
        harness.goalsManager.getCurrentGoals().add(new TestFitnessFunction() {
            @Override public double getFitness(TestChromosome individual,
                    org.evosuite.testcase.execution.ExecutionResult result) { return 1.0; }
            @Override public int compareTo(TestFitnessFunction o) { return 0; }
            @Override public boolean isMaximizationFunction() { return false; }
            @Override public String getTargetClass() { return "Test"; }
            @Override public String getTargetMethod() { return "goal"; }
            @Override public int hashCode() { return 1; }
            @Override public boolean equals(Object o) { return this == o; }
        });

        // Seed population with 2 tests
        TestChromosome pop1 = new TestChromosome();
        TestChromosome pop2 = new TestChromosome();
        harness.seedPopulation(pop1, pop2);

        // Create suite with a pre-existing archive snapshot
        org.evosuite.testsuite.TestSuiteChromosome suite =
                new org.evosuite.testsuite.TestSuiteChromosome();
        suite.addTest(new TestChromosome()); // pre-LS snapshot — NOT staged

        // Simulate LS producing 2 new tests
        TestChromosome lsTest1 = new TestChromosome();
        TestChromosome lsTest2 = new TestChromosome();
        harness.lsOutputTests = java.util.Arrays.asList(lsTest1, lsTest2);

        // Stage LS-produced tests via the real AbstractMOSA.applyLocalSearch(suite)
        harness.applyLocalSearch(suite);

        // Run DynaMOSA's REAL evolve() — rankings, crowding distance, population rebuild.
        // collectExternalCandidates is called from within evolve() at production path.
        harness.evolve();

        // After real ranking/selection, LS tests should survive in the population
        // (with POPULATION=4, 2 pop + 2 LS tests all fit).
        assertTrue(harness.getPopulation().contains(lsTest1)
                        || harness.getPopulation().contains(lsTest2),
                "At least one LS-produced test should survive ranking into population "
                        + "(pop size=" + harness.getPopulation().size() + ")");
        assertTrue(harness.externalCandidatesDrained > 0,
                "collectExternalCandidates must have drained LS tests into union");
    }

    private static class StagnationAwareMOSA extends MOSA {
        private static final long serialVersionUID = 1L;

        private StagnationAwareMOSA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        private void seedPopulation() {
            population.clear();
            population.add(new TestChromosome());
        }

        private void setDetector(StagnationDetector detector) {
            this.stagnationDetector = detector;
        }

        private void setAdapter(LlmInjectionAdapter<TestChromosome> adapter) {
            this.llmInjectionAdapter = adapter;
        }

        private void triggerStagnationInjection() {
            maybeInjectOnStagnationByCoverage(0, Collections.emptyList());
        }

        @Override
        protected void evolve() {
            currentIteration++;
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // no-op for this focused integration test
        }
    }

    /**
     * MOSA subclass that exposes union size to verify in-evolve injection.
     */
    private static class InEvolveTestMOSA extends MOSA {
        private static final long serialVersionUID = 1L;
        int lastUnionSize;

        private InEvolveTestMOSA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        private void seedPopulation() {
            population.clear();
            population.add(new TestChromosome());
            population.add(new TestChromosome());
            // Set up uncovered goals so MOSA's evolve doesn't NPE
            for (TestChromosome tc : population) {
                this.calculateFitnessNoOp(tc);
            }
        }

        private void setDetector(StagnationDetector detector) {
            this.stagnationDetector = detector;
        }

        @Override
        protected void evolve() {
            // Reproduce MOSA's evolve() union logic with collectExternalCandidates
            List<TestChromosome> offspringPopulation = new ArrayList<>();
            List<TestChromosome> union = new ArrayList<>(this.population);
            union.addAll(offspringPopulation);

            // Use the unified external candidate collection
            collectExternalCandidates(union);

            lastUnionSize = union.size();
            currentIteration++;
        }

        private void calculateFitnessNoOp(TestChromosome c) {
            // no-op
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // no-op for this focused integration test
        }
    }

    private static class ThrowingMOSA extends MOSA {
        private static final long serialVersionUID = 1L;
        private final FakeAsyncProducer fakeProducer = new FakeAsyncProducer();

        private ThrowingMOSA(ChromosomeFactory<TestChromosome> factory) {
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
        protected void initializeLlmAssistance(java.util.function.Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                               boolean maximizationObjective) {
            this.asyncProducer = fakeProducer;
            throw new RuntimeException("forced failure");
        }
    }

    private static class ThrowingDynaMOSA extends DynaMOSA {
        private static final long serialVersionUID = 1L;
        private final FakeAsyncProducer fakeProducer = new FakeAsyncProducer();

        private ThrowingDynaMOSA(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        @Override
        protected void initializeLlmAssistance(java.util.function.Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                               boolean maximizationObjective) {
            this.asyncProducer = fakeProducer;
            throw new RuntimeException("forced failure");
        }
    }

    private static class AlwaysInjectingStagnationDetector extends StagnationDetector {
        private AlwaysInjectingStagnationDetector() {
            super(false);
        }

        @Override
        public boolean checkStagnation(int currentCoveredGoals) {
            return true;
        }

        @Override
        public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                                List<TestChromosome> currentPopulation) {
            return Collections.singletonList(new TestChromosome());
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

    /**
     * DynaMOSA subclass that exposes {@code applyLocalSearch} and provides
     * a minimal {@code evolve()} capturing the union for persistence verification.
     * Overrides the no-arg {@code applyLocalSearch()} to skip actual test-level LS
     * and inject simulated LS output tests.
     */
    private static class LsPersistenceDynaMOSA extends DynaMOSA {
        private static final long serialVersionUID = 1L;
        final int[] calcFitnessCount;
        List<TestChromosome> lastUnion;
        /** Tests to add to the suite during the adapter LS, simulating LS output. */
        List<TestChromosome> lsOutputTests = java.util.Collections.emptyList();
        private org.evosuite.testsuite.TestSuiteChromosome activeSuite;

        LsPersistenceDynaMOSA(int[] counter) {
            super(TestChromosome::new);
            this.calcFitnessCount = counter;
        }

        void seedPopulation(TestChromosome... tests) {
            population.clear();
            java.util.Collections.addAll(population, tests);
        }

        @Override
        protected void evolve() {
            List<TestChromosome> union = new ArrayList<>(this.population);
            collectExternalCandidates(union);
            lastUnion = union;
            currentIteration++;
        }

        /**
         * Skip actual LS on population members; trigger the scheduling flag
         * and inject simulated LS output tests into the active suite.
         */
        @Override
        protected void applyLocalSearch() {
            shouldApplyLocalSearch();
            if (activeSuite != null) {
                for (TestChromosome tc : lsOutputTests) {
                    activeSuite.addTest(tc);
                }
            }
        }

        @Override
        protected void calculateFitness(TestChromosome tc) {
            calcFitnessCount[0]++;
        }

        @Override
        public void applyLocalSearch(org.evosuite.testsuite.TestSuiteChromosome suite) {
            this.activeSuite = suite;
            try {
                super.applyLocalSearch(suite);
            } finally {
                this.activeSuite = null;
            }
        }
    }

    /**
     * DynaMOSA subclass that runs the <em>real</em> {@code evolve()} method
     * (ranking, crowding distance, population rebuild). Only
     * {@code breedNextGeneration()}, {@code calculateFitness()}, and the
     * no-arg {@code applyLocalSearch()} are overridden.
     * {@code collectExternalCandidates} is spied on (counts drained items)
     * but delegates to the real implementation.
     */
    private static class RealEvolveDynaMOSA extends DynaMOSA {
        private static final long serialVersionUID = 1L;
        int externalCandidatesDrained;
        List<TestChromosome> lsOutputTests = java.util.Collections.emptyList();
        private org.evosuite.testsuite.TestSuiteChromosome activeSuite;

        RealEvolveDynaMOSA() {
            super(TestChromosome::new);
            // Set up goalsManager with empty goals so the real evolve() can run.
            this.goalsManager = new org.evosuite.ga.metaheuristics.mosa.structural
                    .MultiCriteriaManager(java.util.Collections.emptyList());
        }

        void seedPopulation(TestChromosome... tests) {
            population.clear();
            java.util.Collections.addAll(population, tests);
        }

        // Real evolve() is inherited — NOT overridden.

        @Override
        protected List<TestChromosome> breedNextGeneration() {
            return java.util.Collections.emptyList();
        }

        @Override
        protected void calculateFitness(TestChromosome tc) {
            // no-op: we don't have real instrumentation
        }

        @Override
        protected void applyLocalSearch() {
            shouldApplyLocalSearch();
            if (activeSuite != null) {
                for (TestChromosome tc : lsOutputTests) {
                    activeSuite.addTest(tc);
                }
            }
        }

        @Override
        protected void collectExternalCandidates(List<TestChromosome> union) {
            int before = union.size();
            super.collectExternalCandidates(union);
            externalCandidatesDrained = union.size() - before;
        }

        @Override
        public void applyLocalSearch(org.evosuite.testsuite.TestSuiteChromosome suite) {
            this.activeSuite = suite;
            try {
                super.applyLocalSearch(suite);
            } finally {
                this.activeSuite = null;
            }
        }
    }
}
