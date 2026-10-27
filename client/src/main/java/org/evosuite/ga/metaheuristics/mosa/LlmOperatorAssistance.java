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
import org.evosuite.llm.LlmService;
import org.evosuite.llm.search.AttributedCandidate;
import org.evosuite.llm.search.LlmSemanticOperator;
import org.evosuite.llm.search.OperatorAttemptResult;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.SyncOperatorHook;
import org.evosuite.llm.search.operator.LlmOperatorExecutor;
import org.evosuite.llm.search.operator.OperatorAttemptLog;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.OperatorTargetSelector;
import org.evosuite.llm.search.operator.SyncTargetPlan;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The LLM semantic operators of a MOSA search: the SYNC breeding-loop hooks, the ASYNC background
 * executor and the target selector both share. The breeding loop calls {@link #tryCrossover},
 * {@link #tryMutate} and the record methods; with the hooks off they report a standard-only
 * attempt and record nothing.
 *
 * <p>Not thread-safe: every call comes from the search thread.
 */
final class LlmOperatorAssistance {

    private static final Logger logger = LoggerFactory.getLogger(LlmOperatorAssistance.class);

    /**
     * SYNC breeding-loop mutation hook; created by {@link #start} when {@link
     * Properties#LLM_OPERATOR_MODE} is {@code SYNC}. Tests may assign it directly.
     */
    SyncOperatorHook mutation;

    /** SYNC breeding-loop crossover hook; created alongside {@link #mutation}. */
    SyncOperatorHook crossover;

    /**
     * Selector supplying this generation's operator targets. Shared by the SYNC breeding-loop
     * hooks ({@link #mutation}, {@link #crossover}) and the ASYNC {@link #executor}. Set by
     * {@link #start} whenever {@link Properties#LLM_OPERATOR_MODE} is not {@code OFF}; tests may
     * assign it directly.
     */
    OperatorTargetSelector targetSelector;

    /**
     * Background executor driving the LLM semantic operators in {@code ASYNC} mode; created by
     * {@link #start} and stopped by {@link #shutdown}. {@code null} in {@code OFF}/{@code SYNC}
     * mode.
     */
    LlmOperatorExecutor executor;

    /**
     * Creates the operator machinery a non-{@code OFF} {@link Properties#LLM_OPERATOR_MODE} asks
     * for: {@code SYNC} creates the breeding-loop hooks, {@code ASYNC} the background executor;
     * both share the target selector and {@code memory}, so a (parent, goal) pair that just went
     * out is not re-targeted by the next generation, the seed factory or the stagnation prompt.
     *
     * @param memory the search's repeated-injection memory
     * @param remainingSearchSeconds the search budget left, so the blocking SYNC calls do not
     *                               outlast the search
     * @param executorFactory builds the ASYNC executor
     */
    void start(RepeatedInjectionMemory memory, LongSupplier remainingSearchSeconds,
               Supplier<LlmOperatorExecutor> executorFactory) {
        targetSelector = new OperatorTargetSelector(memory);
        if (Properties.LLM_OPERATOR_MODE == Properties.LlmOperatorMode.SYNC) {
            // The breeding loop blocks on these calls, so they must not outlast the search.
            LlmSemanticOperator semanticMutation = LlmSemanticOperator.mutation(LlmService.getInstance());
            semanticMutation.setRemainingSearchSeconds(remainingSearchSeconds);
            LlmSemanticOperator semanticCrossover = LlmSemanticOperator.crossover(LlmService.getInstance());
            semanticCrossover.setRemainingSearchSeconds(remainingSearchSeconds);
            mutation = SyncOperatorHook.mutation(semanticMutation, memory);
            crossover = SyncOperatorHook.crossover(semanticCrossover, memory);
            return;
        }
        executor = executorFactory.get();
        LoggingUtils.getEvoLogger().info(
                "* LLM operators: ASYNC executor, {} calls/generation, batch {}, in-flight cap {}",
                Properties.LLM_OPERATOR_CALLS_PER_GENERATION, Properties.LLM_OPERATOR_BATCH_SIZE,
                Properties.LLM_OPERATOR_MAX_INFLIGHT);
    }

    /**
     * Selector targets for this generation's SYNC hooks; empty when the hooks are off or
     * neither could place a call (budget spent, service down), since selection is the
     * expensive part and every hook would skip its target anyway.
     */
    SyncTargetPlan selectSyncTargets(List<TestChromosome> population,
                                     Supplier<Set<TestFitnessFunction>> currentGoals, int generation) {
        if (targetSelector == null || (mutation == null && crossover == null)) {
            return SyncTargetPlan.EMPTY;
        }
        if (!(mutation != null && mutation.canCall()) && !(crossover != null && crossover.canCall())) {
            return SyncTargetPlan.EMPTY;
        }
        return new SyncTargetPlan(targetSelector.select(population, currentGoals.get(),
                population.size(), generation, Properties.LLM_OPERATOR_CROSSOVER),
                generation);
    }

    /**
     * Lets the SYNC crossover hook replace {@code offspring} with a semantic child of {@code
     * parent}; a standard-only result when the hook is off, a fallback when it failed.
     */
    OperatorAttemptResult tryCrossover(TestChromosome offspring, TestChromosome parent, SyncTargetPlan targets) {
        OperatorAttemptResult result = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.NOT_CONFIGURED);
        if (crossover != null) {
            try {
                result = crossover.tryApply(offspring, parent, targets);
            } catch (Exception e) {
                logger.debug("LLM crossover error; falling back to standard", e);
                result = OperatorAttemptResult.semanticFallback();
            }
        }
        return result;
    }

    /** Records the isolated target distance of an applied semantic crossover. */
    void recordCrossoverOutcome(OperatorAttemptResult result, double semanticTargetDistance) {
        crossover.recordOutcome(result, semanticTargetDistance);
    }

    /**
     * Lets the SYNC mutation hook mutate {@code offspring}; a standard-only result when the hook
     * is off, a fallback when it failed.
     */
    OperatorAttemptResult tryMutate(TestChromosome offspring, TestChromosome parent, SyncTargetPlan targets) {
        OperatorAttemptResult result = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.NOT_CONFIGURED);
        if (mutation != null) {
            try {
                result = mutation.tryApply(offspring, parent, targets);
            } catch (Exception e) {
                logger.debug("LLM mutation error; falling back to standard", e);
                result = OperatorAttemptResult.semanticFallback();
            }
        }
        return result;
    }

    /**
     * Records how a mutation attempt ended: {@code evaluated} is the evaluated offspring, or
     * {@code null} when the offspring was discarded.
     */
    void recordMutationOutcome(OperatorAttemptResult result, TestChromosome evaluated) {
        if (mutation != null) {
            mutation.recordOutcome(result, evaluated);
        }
    }

    /**
     * Marks the mutation and crossover attempts that produced {@code offspring} as admitted to
     * the offspring population.
     */
    void recordAdmitted(OperatorAttemptResult mutationResult, OperatorAttemptResult crossoverResult,
                        TestChromosome offspring) {
        if (mutation != null) {
            mutation.recordAdmitted(mutationResult, offspring);
        }
        if (crossover != null) {
            crossover.recordAdmitted(crossoverResult, offspring);
        }
    }

    /**
     * Single drive step for the ASYNC operator executor: first drains the candidates produced from
     * a batch this method submitted on an earlier generation, then — capacity and the
     * calls-per-generation budget permitting — selects and submits this generation's targets for a
     * future drain.
     *
     * <p>The selector sees {@code population} as it stands right now, i.e. the parent
     * population: this generation's offspring are already bred but still only in the union
     * being assembled by the caller. The GA evaluates the candidates returned here immediately
     * afterwards, in {@link AbstractMOSA#collectExternalCandidates}, which then closes each one's
     * ticket with its coverage gain.
     */
    List<AttributedCandidate> driveExecutor(LlmOperatorExecutor executor, List<TestChromosome> population,
                                            Supplier<Set<TestFitnessFunction>> currentGoals, int generation) {
        executor.tickGeneration();
        List<AttributedCandidate> ready = executor.drain();
        int callsPerGeneration = Properties.LLM_OPERATOR_CALLS_PER_GENERATION;
        if (callsPerGeneration > 0 && executor.hasCapacity() && targetSelector != null) {
            try {
                // Oversample so the executor's rank-order batch shaping has enough same-kind
                // targets to fill callsPerGeneration full batches even when the ranked list
                // alternates kinds.
                int k = callsPerGeneration * Properties.LLM_OPERATOR_BATCH_SIZE;
                List<OperatorTarget> ranked = targetSelector.select(population, currentGoals.get(), k,
                        generation, Properties.LLM_OPERATOR_CROSSOVER);
                executor.submit(ranked, callsPerGeneration, generation);
            } catch (RuntimeException e) {
                logger.debug("LLM operator target selection/submission failed; skipping this generation", e);
            }
        }
        return ready;
    }

    /** Stops the {@link #executor}, releasing its still-open attempts. Idempotent. */
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * Emit LLM operator statistics to the client node: the four SYNC-hook counters (unchanged),
     * plus the {@code LLM_Operator_*} per-target counters kept by {@link OperatorAttemptLog} and
     * the ASYNC-only counters kept by {@link #executor} — always published, zero when the
     * corresponding hook/executor/log has nothing to report.
     *
     * @param staleTargetTotal the operator candidates whose target goals were all covered when
     *                         they arrived
     */
    void emitStats(ClientNodeLocal<?> clientNode, long staleTargetTotal) {
        if (mutation != null) {
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Mutations,
                    mutation.getAppliedCount());
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Mutation_Fallbacks,
                    mutation.getFallbackCount());
        }
        if (crossover != null) {
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Crossovers,
                    crossover.getAppliedCount());
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Crossover_Fallbacks,
                    crossover.getFallbackCount());
        }

        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Targets_Selected, log.getAttempts());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Targets_Mutation,
                log.getAttempts(OperatorTarget.Kind.MUTATION));
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Targets_Crossover,
                log.getAttempts(OperatorTarget.Kind.CROSSOVER));
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Dropped_Budget,
                executor == null ? 0L : executor.getDroppedBudget());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Dropped_InFlight,
                executor == null ? 0L : executor.getDroppedInFlight());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Dropped_Time,
                executor == null ? 0L : executor.getDroppedTime());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Failed,
                executor == null ? 0L : executor.getFailedCalls());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_InFlight_Generations,
                executor == null ? 0L : executor.getInFlightGenerations());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Offspring_Parsed, log.getOffspringParsed());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Offspring_Admitted, log.getOffspringAdmitted());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Offspring_Survived, log.getOffspringSurvived());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Target_Hits, log.getTargetHits());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Target_Improved, log.getTargetImproved());
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Candidates_StaleTarget,
                staleTargetTotal);
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Operator_Sidecar_Path,
                OperatorAttemptLog.isSidecarEnabled() && log.getAttempts() > 0
                        ? OperatorAttemptLog.resolveSidecarPath() : "");
    }
}
