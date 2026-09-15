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

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatedInjectionMemoryTest {

    @Test
    void inFlightAttemptsSuppressImmediateReselection() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal:foo", "goal:foo:v1");

        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(target), true);
        RepeatedInjectionMemory.SelectionAdjustment adjustment =
                memory.adjustPriority(target, 1.0, true);

        assertTrue(adjustment.isSuppressed());
        assertEquals(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT,
                adjustment.getDiscardReason());

        memory.recordAttemptOutcome(registration.getAttemptId(), 0);
    }

    @Test
    void openSyncAttemptIsInFlightUntilItsOutcomeIsRecorded() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal:sync", "goal:sync:v1");

        // A SYNC caller blocks on the LLM call but still registers the attempt as in flight,
        // so no other feature's selection can pick the same key while it is open.
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(target), true);

        RepeatedInjectionMemory.SelectionAdjustment whileOpen =
                memory.adjustPriority(target, 1.0, true);
        assertTrue(whileOpen.isSuppressed());
        assertEquals(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT,
                whileOpen.getDiscardReason());

        memory.recordAttemptOutcome(registration.getAttemptId(), 1);
        memory.beginSelectionRound();

        RepeatedInjectionMemory.SelectionAdjustment afterOutcome =
                memory.adjustPriority(target, 1.0, true);
        assertFalse(afterOutcome.isSuppressed(),
                "the in-flight gate must clear once the attempt's outcome is recorded");
    }

    @Test
    void suppressedTargetAgesWithSelectionRoundsEvenWithoutFurtherAttempts() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal:only", "goal:only:v1");

        memory.beginSelectionRound();
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(target), false);
        memory.recordAttemptOutcome(registration.getAttemptId(), 0);

        assertTrue(memory.adjustPriority(target, 1.0, false).isSuppressed(),
                "immediately after a no-gain attempt the target must be suppressed");

        // No further attempts are ever registered for this target (it is the sole remaining
        // candidate and stays suppressed), but selection still runs every generation.
        memory.beginSelectionRound();
        memory.beginSelectionRound();

        assertFalse(memory.adjustPriority(target, 1.0, false).isSuppressed(),
                "the cooldown must elapse from selection rounds alone, not just from registered attempts");
    }

    @Test
    void changedEvidenceReenablesTargetAfterNoGainAttempt() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget original = new RepeatedInjectionTarget("goal:foo", "goal:foo:v1");
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(original), false);
        memory.recordAttemptOutcome(registration.getAttemptId(), 0);

        RepeatedInjectionMemory.SelectionAdjustment same =
                memory.adjustPriority(original, 1.0, false);
        assertTrue(same.isSuppressed());

        RepeatedInjectionTarget changed = new RepeatedInjectionTarget("goal:foo", "goal:foo:v2");
        RepeatedInjectionMemory.SelectionAdjustment retried =
                memory.adjustPriority(changed, 1.0, false);
        assertFalse(retried.isSuppressed());
    }

    @Test
    void releasingUndeliveredAttemptDoesNotBumpNoGainCooldown() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal:bar", "goal:bar:v1");

        // Sync call registers and then times out without ever returning.
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(target), false);
        memory.releaseUndeliveredAttempt(registration.getAttemptId());

        // Advance the ordinal so the same-fingerprint cooldown window has elapsed.
        memory.beginSelectionRound();
        memory.beginSelectionRound();

        RepeatedInjectionMemory.SelectionAdjustment retry =
                memory.adjustPriority(target, 1.0, false);
        assertFalse(retry.isSuppressed(),
                "An undelivered attempt must not consume the no-gain cooldown for the next selection");
    }

    @Test
    void releasingUndeliveredInflightAttemptClearsInFlightCounter() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget target = new RepeatedInjectionTarget("goal:baz", "goal:baz:v1");

        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(target), true);
        memory.releaseUndeliveredAttempt(registration.getAttemptId());

        // While the in-flight gate is cleared by release, the same-fingerprint cooldown still
        // applies for one ordinal step. Advance past it so we can assert the in-flight gate is open.
        memory.beginSelectionRound();
        memory.beginSelectionRound();

        RepeatedInjectionMemory.SelectionAdjustment afterRelease =
                memory.adjustPriority(target, 1.0, true);
        assertFalse(afterRelease.isSuppressed(),
                "Releasing an undelivered async attempt must clear the in-flight gate so the target "
                        + "can be selected again once the same-fingerprint cooldown elapses");
    }

    @Test
    void lowSteerabilityBranchTargetsGetLongerRepeatCooldownAfterNoGain() {
        RepeatedInjectionMemory normalMemory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget normal = new RepeatedInjectionTarget(
                "branch:foo",
                "branch:foo|steer=normal",
                ProblemCardType.BRANCH_POLARITY_GAP);
        RepeatedInjectionMemory.PromptAttemptRegistration normalRegistration =
                normalMemory.registerAttempt(Collections.singletonList(normal), false);
        normalMemory.recordAttemptOutcome(normalRegistration.getAttemptId(), 0);

        RepeatedInjectionMemory lowSteerMemory = new RepeatedInjectionMemory();
        RepeatedInjectionTarget lowSteer = new RepeatedInjectionTarget(
                "branch:foo",
                "branch:foo|steer=low",
                ProblemCardType.BRANCH_POLARITY_GAP);
        RepeatedInjectionMemory.PromptAttemptRegistration lowRegistration =
                lowSteerMemory.registerAttempt(Collections.singletonList(lowSteer), false);
        lowSteerMemory.recordAttemptOutcome(lowRegistration.getAttemptId(), 0);

        // Advance the selection ordinal by two rounds in each memory.
        normalMemory.beginSelectionRound();
        normalMemory.beginSelectionRound();
        lowSteerMemory.beginSelectionRound();
        lowSteerMemory.beginSelectionRound();

        RepeatedInjectionMemory.SelectionAdjustment normalAfterAging =
                normalMemory.adjustPriority(normal, 1.0, false);
        RepeatedInjectionMemory.SelectionAdjustment lowSteerAfterAging =
                lowSteerMemory.adjustPriority(lowSteer, 1.0, false);

        assertFalse(normalAfterAging.isSuppressed(),
                "Normal branch targets should be selectable again after the default cooldown window");
        assertTrue(lowSteerAfterAging.isSuppressed(),
                "Low-steerability branch targets should stay suppressed for a longer cooldown after no gain");
    }
}
