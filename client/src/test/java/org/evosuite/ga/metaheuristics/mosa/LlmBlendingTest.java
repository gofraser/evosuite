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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.llm.search.BlendChannel;
import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.InjectionSource;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the blend-brood path in {@link AbstractMOSA#collectExternalCandidates}:
 * goal-directed partner selection, brood admission (new coverage first, then
 * strict target-goal improvement), lineage/channel tagging, attempt
 * attribution, the per-generation evaluation budget, the blend-only ablation
 * ({@code llm_blend_keep_raw=false}), and the default-off contract.
 *
 * <p>The harness overrides {@code calculateFitness} as a counter no-op, so a
 * variant's "evaluated" fitness is whatever its clone inherited from the
 * chromosome it was cloned from — which makes outcomes fully deterministic
 * together with the no-op crossover (variants are exact clones of the raw
 * candidate and the partner).
 */
class LlmBlendingTest {

    private boolean savedEnabled;
    private int savedMutants;
    private int savedGoalCrossovers;
    private int savedTournamentCrossovers;
    private int savedMaxAdmitted;
    private int savedMaxEvals;
    private boolean savedKeepRaw;

    @BeforeEach
    void saveProperties() {
        savedEnabled = Properties.LLM_BLEND_ENABLED;
        savedMutants = Properties.LLM_BLEND_MUTANTS;
        savedGoalCrossovers = Properties.LLM_BLEND_GOAL_CROSSOVERS;
        savedTournamentCrossovers = Properties.LLM_BLEND_TOURNAMENT_CROSSOVERS;
        savedMaxAdmitted = Properties.LLM_BLEND_MAX_ADMITTED_VARIANTS;
        savedMaxEvals = Properties.LLM_BLEND_MAX_EVALS_PER_GEN;
        savedKeepRaw = Properties.LLM_BLEND_KEEP_RAW;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_BLEND_ENABLED = savedEnabled;
        Properties.LLM_BLEND_MUTANTS = savedMutants;
        Properties.LLM_BLEND_GOAL_CROSSOVERS = savedGoalCrossovers;
        Properties.LLM_BLEND_TOURNAMENT_CROSSOVERS = savedTournamentCrossovers;
        Properties.LLM_BLEND_MAX_ADMITTED_VARIANTS = savedMaxAdmitted;
        Properties.LLM_BLEND_MAX_EVALS_PER_GEN = savedMaxEvals;
        Properties.LLM_BLEND_KEEP_RAW = savedKeepRaw;
    }

    private void enableBlending() {
        Properties.LLM_BLEND_ENABLED = true;
        Properties.LLM_BLEND_MUTANTS = 0;
        Properties.LLM_BLEND_GOAL_CROSSOVERS = 2;
        Properties.LLM_BLEND_TOURNAMENT_CROSSOVERS = 0;
        Properties.LLM_BLEND_MAX_ADMITTED_VARIANTS = 2;
        Properties.LLM_BLEND_MAX_EVALS_PER_GEN = 20;
        Properties.LLM_BLEND_KEEP_RAW = true;
    }

    @Test
    void goalDirectedBlendAdmitsTargetGoalImprovingVariant() {
        enableBlending();
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal goal = new FakeGoal();
        mosa.uncovered.add(goal);

        TestChromosome partner = chromosome();
        partner.setFitness(goal, 0.2);
        TestChromosome other = chromosome();
        other.setFitness(goal, 0.8);
        mosa.seed(partner, other);

        TestChromosome raw = chromosome();
        raw.setFitness(goal, 0.5);
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_STAGNATION, raw,
                new InjectionAttemptMetadata("attempt-1", Collections.emptyList(),
                        Collections.singletonList(goal)), null));

        List<TestChromosome> union = mosa.drainIntoUnion();

        assertTrue(containsIdentity(union, raw), "raw candidate must still be admitted");
        assertEquals(BlendChannel.RAW, raw.getBlendChannel());
        assertEquals(2, mosa.lastGenBlendVariantsBredCount,
                "one crossover call yields both directions");
        assertEquals(1, mosa.lastGenBlendVariantsAdmittedCount,
                "only the partner-clone variant strictly improves on the target goal");
        assertEquals(1, mosa.lastGenBlendXoverGoalAdmittedCount);

        List<TestChromosome> variants = blendVariants(union);
        assertEquals(1, variants.size());
        TestChromosome admitted = variants.get(0);
        assertEquals(BlendChannel.XOVER_GOAL, admitted.getBlendChannel());
        assertEquals(0.2, admitted.getFitness(goal), 1e-9,
                "the admitted blend carries the goal-partner's improving fitness");
        assertEquals(raw.getInjectionLineageId(), admitted.getInjectionLineageId(),
                "variants share the raw candidate's lineage, no fresh ids");
        assertEquals(InjectionSource.LLM_STAGNATION, admitted.getInjectionSource());
        assertTrue(admitted.getDescentLineageIds().contains(raw.getInjectionLineageId()),
                "variants carry descent marks for the raw candidate's lineage");
    }

    @Test
    void newCoverageVariantAdmittedFirstAndCreditsAttempt() {
        enableBlending();
        Properties.LLM_BLEND_MAX_ADMITTED_VARIANTS = 1;
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal target = new FakeGoal();
        FakeGoal bonus = new FakeGoal();
        mosa.uncovered.add(target);
        mosa.uncovered.add(bonus);

        // The goal partner is worse on the target goal but its clone covers
        // the bonus goal — new coverage must win admission over fitness.
        TestChromosome partner = chromosome();
        partner.setFitness(target, 0.9);
        partner.setFitness(bonus, 0.0);
        TestChromosome filler = chromosome();
        filler.setFitness(target, 0.95);
        filler.setFitness(bonus, 1.0);
        mosa.seed(partner, filler);

        TestChromosome raw = chromosome();
        raw.setFitness(target, 0.5);
        raw.setFitness(bonus, 1.0);
        Map<String, Integer> reportedGains = new LinkedHashMap<>();
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_ASYNC, raw,
                new InjectionAttemptMetadata("attempt-7", Collections.emptyList(),
                        Collections.singletonList(target)), reportedGains));

        List<TestChromosome> union = mosa.drainIntoUnion();

        List<TestChromosome> variants = blendVariants(union);
        assertEquals(1, variants.size(), "admission ceiling of 1 must hold");
        assertEquals(0.0, variants.get(0).getFitness(bonus), 1e-9,
                "the variant covering a new goal is admitted before the fitness-improver");
        assertEquals(Integer.valueOf(1), reportedGains.get("attempt-7"),
                "the blend's covered goal must credit the originating attempt");
    }

    @Test
    void nonImprovingBroodIsEvaluatedButDiscarded() {
        enableBlending();
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal goal = new FakeGoal();
        mosa.uncovered.add(goal);

        TestChromosome partner = chromosome();
        partner.setFitness(goal, 0.7); // worse than the raw candidate
        mosa.seed(partner);

        TestChromosome raw = chromosome();
        raw.setFitness(goal, 0.5);
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_STAGNATION, raw,
                new InjectionAttemptMetadata("attempt-2", Collections.emptyList(),
                        Collections.singletonList(goal)), null));

        List<TestChromosome> union = mosa.drainIntoUnion();

        assertEquals(2, mosa.lastGenBlendVariantsBredCount);
        assertEquals(0, mosa.lastGenBlendVariantsAdmittedCount,
                "neither variant covers anything or improves the target goal");
        assertTrue(blendVariants(union).isEmpty());
        assertTrue(containsIdentity(union, raw));
        assertEquals(3, mosa.fitnessEvaluations,
                "raw + both variants are evaluated even though the variants are discarded");
    }

    @Test
    void keepRawFalseWithholdsRawAndAdmitsBestVariant() {
        enableBlending();
        Properties.LLM_BLEND_KEEP_RAW = false;
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal goal = new FakeGoal();
        mosa.uncovered.add(goal);

        TestChromosome partner = chromosome();
        partner.setFitness(goal, 0.7); // no variant qualifies on its own
        mosa.seed(partner);

        TestChromosome raw = chromosome();
        raw.setFitness(goal, 0.5);
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_STAGNATION, raw,
                new InjectionAttemptMetadata("attempt-3", Collections.emptyList(),
                        Collections.singletonList(goal)), null));

        List<TestChromosome> union = mosa.drainIntoUnion();

        assertFalse(containsIdentity(union, raw),
                "blend-only ablation must withhold the raw candidate");
        assertEquals(0, mosa.lastGenInjectedCandidatesAdmittedCount,
                "the raw admitted counter keeps its meaning in the ablation");
        List<TestChromosome> variants = blendVariants(union);
        assertEquals(1, variants.size(),
                "the best variant is admitted so the attempt is not lost entirely");
        assertEquals(0.5, variants.get(0).getFitness(goal), 1e-9,
                "best = lowest target-goal fitness among the brood (the raw clone)");
    }

    @Test
    void evaluationBudgetStopsBroodMidGeneration() {
        enableBlending();
        Properties.LLM_BLEND_MAX_EVALS_PER_GEN = 1;
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal goal = new FakeGoal();
        mosa.uncovered.add(goal);

        TestChromosome partner = chromosome();
        partner.setFitness(goal, 0.2); // would qualify, but is bred second
        mosa.seed(partner);

        TestChromosome raw = chromosome();
        raw.setFitness(goal, 0.5);
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_STAGNATION, raw,
                new InjectionAttemptMetadata("attempt-4", Collections.emptyList(),
                        Collections.singletonList(goal)), null));

        mosa.drainIntoUnion();

        assertEquals(2, mosa.lastGenBlendVariantsBredCount);
        assertEquals(0, mosa.lastGenBlendVariantsAdmittedCount,
                "the improving variant was never evaluated: budget cut the brood off");
        assertEquals(2, mosa.fitnessEvaluations, "raw + exactly one budgeted variant");
    }

    @Test
    void defaultOffLeavesInjectionPathUnchanged() {
        // All blend properties at defaults: LLM_BLEND_ENABLED stays false.
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal goal = new FakeGoal();
        mosa.uncovered.add(goal);
        mosa.seed(chromosome());

        TestChromosome raw = chromosome();
        raw.setFitness(goal, 0.5);
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_STAGNATION, raw,
                new InjectionAttemptMetadata("attempt-5", Collections.emptyList(),
                        Collections.singletonList(goal)), null));

        List<TestChromosome> union = mosa.drainIntoUnion();

        assertTrue(containsIdentity(union, raw));
        assertNull(raw.getBlendChannel(), "no channel tagging when blending is disabled");
        assertEquals(0, mosa.lastGenBlendVariantsBredCount);
        assertEquals(1, mosa.lastGenInjectedCandidatesAdmittedCount);
        assertTrue(blendVariants(union).isEmpty());
        assertEquals(1, mosa.fitnessEvaluations, "raw only; no brood evaluations");
    }

    @Test
    void goalPartnerExcludesLlmTaggedPopulationMembers() {
        enableBlending();
        BlendHarnessMOSA mosa = new BlendHarnessMOSA();
        FakeGoal goal = new FakeGoal();
        mosa.uncovered.add(goal);

        // Best-on-goal member is itself LLM-injected — must be skipped in
        // favor of the best untagged member.
        TestChromosome injectedChampion = chromosome();
        injectedChampion.setFitness(goal, 0.1);
        injectedChampion.setInjectionSource(InjectionSource.LLM_ASYNC);
        TestChromosome cleanPartner = chromosome();
        cleanPartner.setFitness(goal, 0.3);
        mosa.seed(injectedChampion, cleanPartner);

        // Distinct test body: the LLM-tagged champion seeds the dedup set, so
        // an empty-bodied raw candidate would collide with its signature.
        TestChromosome raw = chromosomeWithInt(42);
        raw.setFitness(goal, 0.5);
        mosa.externalCandidateSources.add(sourceOf(InjectionSource.LLM_STAGNATION, raw,
                new InjectionAttemptMetadata("attempt-6", Collections.emptyList(),
                        Collections.singletonList(goal)), null));

        List<TestChromosome> union = mosa.drainIntoUnion();

        List<TestChromosome> variants = blendVariants(union);
        assertEquals(1, variants.size());
        assertEquals(0.3, variants.get(0).getFitness(goal), 1e-9,
                "the partner must be the best clean member, not the injected champion");
    }

    // ---------------------------------------------------------------- helpers

    private static TestChromosome chromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    private static TestChromosome chromosomeWithInt(int value) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(
                test, value));
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(test);
        return tc;
    }

    private static boolean containsIdentity(List<TestChromosome> list, TestChromosome target) {
        for (TestChromosome tc : list) {
            if (tc == target) {
                return true;
            }
        }
        return false;
    }

    private static List<TestChromosome> blendVariants(List<TestChromosome> union) {
        List<TestChromosome> variants = new ArrayList<>();
        for (TestChromosome tc : union) {
            if (tc.getBlendChannel() != null && tc.getBlendChannel() != BlendChannel.RAW) {
                variants.add(tc);
            }
        }
        return variants;
    }

    private static AbstractMOSA.ExternalCandidateSource sourceOf(
            InjectionSource source, TestChromosome candidate,
            InjectionAttemptMetadata metadata, Map<String, Integer> reportedGains) {
        return new AbstractMOSA.ExternalCandidateSource() {
            private boolean drained;

            @Override
            public List<TestChromosome> drain() {
                if (drained) {
                    return Collections.emptyList();
                }
                drained = true;
                return Collections.singletonList(candidate);
            }

            @Override
            public InjectionSource injectionSource() {
                return source;
            }

            @Override
            public Map<TestChromosome, InjectionAttemptMetadata> consumeAttemptMetadata(
                    List<TestChromosome> candidates) {
                return metadata == null ? Collections.emptyMap()
                        : Collections.singletonMap(candidate, metadata);
            }

            @Override
            public void reportAttemptOutcomes(Map<String, Integer> gains) {
                if (reportedGains != null) {
                    reportedGains.putAll(gains);
                }
            }
        };
    }

    /**
     * MOSA harness: no-op crossover (blend variants are exact clones of raw
     * candidate and partner), counting no-op fitness evaluation (cached clone
     * fitness is the "evaluated" fitness), and a controllable uncovered-goal
     * set.
     */
    private static class BlendHarnessMOSA extends MOSA {
        private static final long serialVersionUID = 1L;

        final Set<TestFitnessFunction> uncovered = new LinkedHashSet<>();
        int fitnessEvaluations;

        BlendHarnessMOSA() {
            super(TestChromosome::new);
            setCrossOverFunction(new NoOpCrossOver());
        }

        void seed(TestChromosome... members) {
            population.clear();
            population.addAll(Arrays.asList(members));
        }

        List<TestChromosome> drainIntoUnion() {
            List<TestChromosome> union = new ArrayList<>(population);
            collectExternalCandidates(union);
            return union;
        }

        @Override
        protected Set<TestFitnessFunction> getUncoveredGoals() {
            return new LinkedHashSet<>(uncovered);
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            fitnessEvaluations++;
        }
    }

    private static class NoOpCrossOver extends CrossOverFunction<TestChromosome> {
        private static final long serialVersionUID = 1L;

        @Override
        public void crossOver(TestChromosome parent1, TestChromosome parent2) {
            // deterministic: offspring stay exact clones of their parents
        }
    }

    /**
     * Identity-equality goal that never executes tests: fitness is whatever
     * the chromosome has cached (1.0 when nothing is cached), and coverage is
     * fitness == 0.
     */
    private static final class FakeGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return getFitness(individual);
        }

        @Override
        public double getFitness(TestChromosome individual) {
            Double cached = individual.getFitnessValues().get(this);
            return cached != null ? cached : 1.0;
        }

        @Override
        public boolean isCovered(TestChromosome individual) {
            return getFitness(individual) == 0.0;
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return 0;
        }

        @Override
        public String getTargetClass() {
            return "FakeGoal";
        }

        @Override
        public String getTargetMethod() {
            return "fake";
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }
    }
}
