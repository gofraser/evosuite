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
    void asyncInFlightAttemptsSuppressImmediateReselection() {
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

        // Advance the global selection ordinal by two picks in each memory.
        RepeatedInjectionTarget dummy = new RepeatedInjectionTarget("dummy:key", "dummy:fingerprint");
        normalMemory.registerAttempt(Collections.singletonList(dummy), false);
        normalMemory.registerAttempt(Collections.singletonList(dummy), false);
        lowSteerMemory.registerAttempt(Collections.singletonList(dummy), false);
        lowSteerMemory.registerAttempt(Collections.singletonList(dummy), false);

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
