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
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.operator.OperatorAttemptLog;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.SyncTargetPlan;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared machinery behind {@link LanguageModelMutation} and
 * {@link LanguageModelCrossover}: gate on {@link Properties#LLM_OPERATOR_MODE}
 * and the subclass's probability, take a target from this generation's
 * {@link SyncTargetPlan}, register the attempt with a {@link
 * RepeatedInjectionMemory} (if any) and the {@link OperatorAttemptLog}, make
 * the one LLM call, and replace the offspring's test case on success.
 */
abstract class SyncOperatorHook {

    private static final Logger logger = LoggerFactory.getLogger(SyncOperatorHook.class);

    private final RepeatedInjectionMemory memory;
    private final AtomicInteger appliedCount = new AtomicInteger(0);
    private final AtomicInteger fallbackCount = new AtomicInteger(0);

    protected SyncOperatorHook(RepeatedInjectionMemory memory) {
        this.memory = memory;
    }

    /** The probability gate this hook applies once it has passed the mode check. */
    protected abstract double probability();

    /** Whether the underlying operator could place a call right now (service up, budget left). */
    protected abstract boolean canCall();

    /** Takes this hook's kind of target for {@code parent} from {@code plan}, or null. */
    protected abstract OperatorTarget take(SyncTargetPlan plan, TestChromosome parent);

    /** Makes the one LLM call for {@code target}, mapped back to this single target. */
    protected abstract TargetedResponse invoke(OperatorTarget target);

    /**
     * Attempts this hook's semantic operator on {@code offspring}, whose
     * parent is {@code parent}, using this generation's {@code plan}. The
     * probability gate runs before the target is taken, so a rejected parent
     * keeps its target available for a later selection this generation.
     */
    public final OperatorAttemptResult tryApply(TestChromosome offspring, TestChromosome parent,
                                                SyncTargetPlan plan) {
        if (Properties.LLM_OPERATOR_MODE != Properties.LlmOperatorMode.SYNC) {
            return OperatorAttemptResult.standardOnly(OperatorAttemptResult.SkipReason.DISABLED);
        }
        if (!canCall()) {
            // Once the budget is spent every attempt would fall back; skipping here keeps the
            // remaining generations out of the semantic-attempt statistics and the repeat memory.
            return OperatorAttemptResult.standardOnly(OperatorAttemptResult.SkipReason.NO_BUDGET);
        }
        if (Randomness.nextDouble() > probability()) {
            return OperatorAttemptResult.standardOnly(OperatorAttemptResult.SkipReason.PROBABILITY);
        }
        OperatorTarget target = take(plan, parent);
        if (target == null) {
            return OperatorAttemptResult.standardOnly(OperatorAttemptResult.SkipReason.NO_TARGET);
        }

        RepeatedInjectionMemory.PromptAttemptRegistration registration = memory == null
                ? RepeatedInjectionMemory.PromptAttemptRegistration.empty()
                : memory.registerAttempt(Collections.singletonList(target.toRepeatedInjectionTarget()), true);
        String attemptId = registration.isEmpty() ? null : registration.getAttemptId();
        OperatorAttemptLog.getInstance().open(attemptId, plan.getGeneration(), target, "SYNC", 1);

        TargetedResponse response;
        try {
            response = invoke(target);
        } catch (RuntimeException e) {
            logger.debug("LLM operator call failed unexpectedly: {}", e.getMessage());
            OperatorAttemptLog.getInstance().recordResponse(attemptId, TargetedResponse.Status.FAILED,
                    false, false, 0, TargetedResponse.CallUsage.NONE, null);
            return fallback(target, registration, TargetedResponse.Status.FAILED);
        }

        TestCase test = response.forTarget(1).orElse(null);
        OperatorAttemptLog.getInstance().recordResponse(attemptId, response.getStatus(),
                response.isParseSucceeded(), test != null, response.getRepairTurns(), response.getUsage(), test);
        if (test != null) {
            offspring.setTestCase(test);
            offspring.setChanged(true);
            appliedCount.incrementAndGet();
            // The attempt stays open; recordOutcome() closes it once the GA
            // has evaluated (or dropped) this offspring.
            return OperatorAttemptResult.semanticApplied(target, attemptId, plan.getGeneration());
        }
        return fallback(target, registration, response.getStatus());
    }

    /**
     * Falls back to the standard operator for {@code target}. A {@code NO_BUDGET}/{@code FAILED}
     * response (or a call that threw) never reached a completed turn for this target, so its
     * repeat-suppression attempt is released rather than counted as a spent, gainless turn — the
     * same treatment the asynchronous executor gives an undelivered attempt. Only a delivered
     * response that produced nothing usable for this target counts as a completed, gainless turn.
     */
    private OperatorAttemptResult fallback(OperatorTarget target,
                                           RepeatedInjectionMemory.PromptAttemptRegistration registration,
                                           TargetedResponse.Status status) {
        if (!registration.isEmpty()) {
            if (status == TargetedResponse.Status.NO_BUDGET || status == TargetedResponse.Status.FAILED) {
                memory.releaseUndeliveredAttempt(registration.getAttemptId());
            } else {
                memory.recordAttemptOutcome(registration.getAttemptId(), 0);
            }
        }
        fallbackCount.incrementAndGet();
        return OperatorAttemptResult.semanticFallback(target);
    }

    /**
     * Closes the repeated-injection attempt opened by a successful {@link
     * #tryApply}, crediting it with a gain when {@code evaluatedOffspring}'s
     * cached fitness for the target's goal is exactly 0.0. Null means the GA
     * dropped the offspring before it could be evaluated: the repeat-memory
     * outcome is still recorded (as no gain, same as before), but the attempt
     * log's evaluation is skipped since nothing was actually judged. Does not
     * record admission — see {@link #recordAdmitted}, called separately once
     * the offspring actually enters the offspring population. No-op unless
     * this result carries an open attempt id.
     */
    public final void recordOutcome(OperatorAttemptResult result, TestChromosome evaluatedOffspring) {
        if (result == null || result.getAttemptId() == null) {
            return;
        }
        if (evaluatedOffspring == null) {
            if (memory != null) {
                memory.recordAttemptOutcome(result.getAttemptId(), 0);
            }
            return;
        }
        Double value = result.getTarget() == null ? null
                : evaluatedOffspring.getFitnessValues().get(result.getTarget().getGoal());
        recordOutcome(result, value == null ? Double.NaN : value);
    }

    /**
     * Closes the attempt with a target distance the caller established itself, e.g. from an
     * isolated evaluation of the semantic result taken before later operators changed it —
     * crediting a gain when {@code targetDistance} is exactly 0.0. Records the evaluation only;
     * admission is a separate step (see {@link #recordAdmitted}) because a crossover's {@code
     * offspring1} still has to survive the mutation step and an orphan check before it actually
     * reaches the offspring population.
     */
    public final void recordOutcome(OperatorAttemptResult result, double targetDistance) {
        if (result == null || result.getAttemptId() == null) {
            return;
        }
        boolean covered = targetDistance == 0.0;
        if (memory != null) {
            memory.recordAttemptOutcome(result.getAttemptId(), covered ? 1 : 0);
        }
        OperatorAttemptLog.getInstance().recordEvaluated(result.getAttemptId(), targetDistance, covered);
    }

    /**
     * Records that {@code offspring} was actually added to the offspring population, at the point
     * the caller does so — the only moment a semantic child's survival scan can meaningfully start
     * from. No-op unless this result carries an open attempt id.
     */
    public final void recordAdmitted(OperatorAttemptResult result, TestChromosome offspring) {
        if (result == null || result.getAttemptId() == null) {
            return;
        }
        OperatorAttemptLog.getInstance().recordAdmitted(result.getAttemptId(), offspring, result.getGeneration());
    }

    public int getAppliedCount() {
        return appliedCount.get();
    }

    public int getFallbackCount() {
        return fallbackCount.get();
    }
}
