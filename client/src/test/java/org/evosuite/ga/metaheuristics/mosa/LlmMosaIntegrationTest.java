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
}
