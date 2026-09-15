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
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.SearchListener;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TargetedPromptFactory;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.OperatorTargetSelector;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Probabilistic LLM test factory with non-LLM fallback.
 *
 * <p>During search, each call seeds toward one {@link OperatorTargetSelector}
 * target: the new individual starts from the population member closest to a
 * goal that is still worth targeting, rendered through the same {@link
 * TargetedPromptFactory} block used by the LLM variation operators; when the
 * selector offers no eligible target the call is not made and the non-LLM
 * fallback produces the test. Before a population exists (the initial
 * population, or search state not yet wired in), the factory uses the generic
 * goal-list prompt below instead.
 */
public class LlmTestChromosomeFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -7563751833887015047L;
    private static final Logger logger = LoggerFactory.getLogger(LlmTestChromosomeFactory.class);

    private final ChromosomeFactory<TestChromosome> fallback;
    private final LlmService llmService;
    private final TargetedPromptFactory targetedPromptFactory;
    /**
     * Non-final so the GA can inject its current uncovered-goal and population
     * suppliers after construction. The factory is built by the strategy
     * wrappers before the GA exists, so initial wiring defaults to empty and is
     * upgraded via {@link #setSearchState} once the GA initialises its LLM
     * assistance hooks.
     */
    private volatile Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier;
    private volatile Supplier<List<TestChromosome>> populationSupplier;
    private volatile OperatorTargetSelector targetSelector;
    private volatile LongSupplier generationSupplier = () -> 0L;
    /** Shared with the other LLM features; null until the GA wires it in (then no attempt bookkeeping). */
    private volatile RepeatedInjectionMemory repeatedInjectionMemory;
    /** Attempts waiting for the GA to evaluate their test; null while there is no memory. */
    private volatile SeedAttemptLedger attemptLedger;

    public LlmTestChromosomeFactory(ChromosomeFactory<TestChromosome> fallback) {
        this(fallback, LlmService.getInstance(), Collections::emptyList);
    }

    /** Creates a factory with explicit dependencies. */
    public LlmTestChromosomeFactory(ChromosomeFactory<TestChromosome> fallback,
                                    LlmService llmService,
                                    Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier) {
        this.fallback = fallback;
        this.llmService = llmService;
        this.targetedPromptFactory = new TargetedPromptFactory();
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
        this.populationSupplier = Collections::emptyList;
        this.targetSelector = new OperatorTargetSelector(null);
    }

    public ChromosomeFactory<TestChromosome> getFallbackFactory() {
        return fallback;
    }

    /**
     * Binds this factory to the live search state — typically called once
     * during GA initialisation: the goal and population suppliers feed target
     * selection, {@code generation} keeps the selector's stall history to one
     * observation per generation however often the factory is called within
     * one, and {@code memory} is the repeated-injection memory shared with the
     * other LLM features, so a goal this factory just prompted for is on
     * cooldown for them too (and vice versa).
     */
    public void setSearchState(Supplier<Collection<TestFitnessFunction>> currentGoals,
                               Supplier<List<TestChromosome>> population,
                               LongSupplier generation,
                               RepeatedInjectionMemory memory) {
        this.uncoveredGoalsSupplier = currentGoals == null ? Collections::emptyList : currentGoals;
        this.populationSupplier = population == null ? Collections::emptyList : population;
        this.generationSupplier = generation == null ? () -> 0L : generation;
        this.repeatedInjectionMemory = memory;
        this.attemptLedger = memory == null ? null : new SeedAttemptLedger(memory);
        this.targetSelector = new OperatorTargetSelector(memory);
    }

    /**
     * Listener the GA registers so that every fitness evaluation closes the
     * attempt behind a test this factory produced (see {@link SeedAttemptLedger}).
     */
    public <T extends Chromosome<T>> SearchListener<T> evaluationListener() {
        return new SearchListener<T>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void fitnessEvaluation(T individual) {
                SeedAttemptLedger ledger = attemptLedger;
                if (ledger != null) {
                    ledger.evaluated(individual, generationSupplier.getAsLong());
                }
            }

            @Override
            public void searchStarted(GeneticAlgorithm<T> algorithm) {
            }

            @Override
            public void iteration(GeneticAlgorithm<T> algorithm) {
            }

            @Override
            public void searchFinished(GeneticAlgorithm<T> algorithm) {
            }

            @Override
            public void modification(T individual) {
            }
        };
    }

    @Override
    public TestChromosome getChromosome() {
        if (!shouldUseLlm()) {
            return fallback.getChromosome();
        }
        try {
            TestChromosome llmChromosome = generateViaLlm();
            if (llmChromosome != null) {
                return llmChromosome;
            }
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM test factory budget exhausted: {}", e.getMessage());
        } catch (LlmCallFailedException e) {
            logger.debug("LLM test factory call failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.debug("LLM test factory failed: {}", e.getMessage());
        }
        return fallback.getChromosome();
    }

    private boolean shouldUseLlm() {
        if (!Properties.LLM_TEST_FACTORY || !llmService.isAvailable()) {
            return false;
        }
        if (!llmService.hasBudget()) {
            return false;
        }
        double probability = Math.max(0.0d, Math.min(1.0d, Properties.LLM_TEST_FACTORY_PROBABILITY));
        return Randomness.nextDouble() < probability;
    }

    /**
     * One LLM call, framed as one repeated-injection attempt when the prompt
     * has a target: the attempt is registered before the call and closed on
     * every exit — with no gain when the response yielded nothing, released
     * without judgement when the call never went out (budget exhausted), and
     * otherwise left open in the {@link SeedAttemptLedger} until the GA has
     * evaluated the new test — so the shared memory's cooldown and no-gain
     * counters reflect this factory's prompts like every other feature's.
     *
     * <p>Returns null, so the caller uses the non-LLM fallback, when a
     * population exists but the selector offers no eligible target: a goal on
     * cooldown or in flight must not be re-prompted through the generic
     * goal-list prompt, which only serves seeding before a population exists.
     */
    private TestChromosome generateViaLlm() {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        OperatorTarget target = null;
        PromptResult prompt;
        List<TestChromosome> population = populationSupplier.get();
        if (population == null || population.isEmpty()) {
            prompt = buildGoalListPrompt();
        } else {
            target = selectTarget(population);
            if (target == null) {
                return null;
            }
            prompt = targetedPromptFactory.buildGenerate(target);
        }
        RepeatedInjectionMemory memory = repeatedInjectionMemory;
        RepeatedInjectionMemory.PromptAttemptRegistration registration = memory == null
                ? RepeatedInjectionMemory.PromptAttemptRegistration.empty()
                : memory.registerAttempt(prompt.getRepeatedInjectionTargets(), true);
        boolean handedToLedger = false;
        try {
            String response = llmService.query(prompt, LlmFeature.TEST_FACTORY);
            RepairResult repairResult = TestRepairLoop
                    .createDefault(llmService, TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions))
                    .attemptParse(response, prompt.getMessages(), LlmFeature.TEST_FACTORY);
            TargetedResponse targeted = TargetedResponse.map(repairResult, 1);
            TestCase testCase = targeted.forTarget(1)
                    .orElseGet(() -> targeted.all().isEmpty() ? null : targeted.all().get(0));
            if (testCase == null) {
                return null;
            }
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(testCase);
            if (!registration.isEmpty() && target != null) {
                attemptLedger.open(chromosome, registration.getAttemptId(), target.getGoal(),
                        generationSupplier.getAsLong());
                handedToLedger = true;
            }
            return chromosome;
        } catch (LlmBudgetExceededException e) {
            if (!registration.isEmpty()) {
                memory.releaseUndeliveredAttempt(registration.getAttemptId());
                registration = RepeatedInjectionMemory.PromptAttemptRegistration.empty();
            }
            throw e;
        } finally {
            if (!registration.isEmpty() && !handedToLedger) {
                memory.recordAttemptOutcome(registration.getAttemptId(), 0);
            }
        }
    }

    /** The single-parent target this call seeds toward, or null when nothing is eligible. */
    private OperatorTarget selectTarget(List<TestChromosome> population) {
        Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
        if (goals == null || goals.isEmpty()) {
            return null;
        }
        List<OperatorTarget> targets = targetSelector.select(population, goals, 1, generationSupplier.getAsLong(), false);
        return targets.isEmpty() ? null : targets.get(0);
    }

    private PromptResult buildGoalListPrompt() {
        Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate one JUnit test that is likely to improve coverage."
                        + LlmAssertionPolicyResolver.instructionSuffix(false));
        if (goals != null && !goals.isEmpty()) {
            builder.withUncoveredGoals(goals);
        }
        return builder.buildWithMetadata();
    }

}
