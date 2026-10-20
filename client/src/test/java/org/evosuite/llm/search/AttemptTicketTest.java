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

import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.response.TargetedResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttemptTicketTest {

    private static final RepeatedInjectionTarget TARGET = new RepeatedInjectionTarget("goal:t", "goal:t:v1");

    @Test
    void withoutMemoryOrTargetsTheTicketIsUntracked() {
        AttemptTicket noMemory = AttemptTicket.open(null, Collections.singletonList(TARGET), true);
        AttemptTicket noTargets = AttemptTicket.open(new RepeatedInjectionMemory(),
                Collections.<RepeatedInjectionTarget>emptyList(), true);

        assertSame(AttemptTicket.untracked(), noMemory);
        assertSame(AttemptTicket.untracked(), noTargets);
        assertEquals("", noMemory.getId());
        assertFalse(noMemory.isOpen());
        noMemory.close(1);
        noMemory.release();
        assertEquals(AttemptTicket.State.OPEN, noMemory.getState(), "the untracked ticket never settles");
    }

    @Test
    void openTicketKeepsItsTargetInFlightUntilSettled() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        AttemptTicket ticket = AttemptTicket.open(memory, Collections.singletonList(TARGET), true);

        assertTrue(ticket.isOpen());
        assertTrue(memory.adjustPriority(TARGET, 1.0, true).isSuppressed());

        ticket.close(2);

        assertEquals(AttemptTicket.State.CLOSED, ticket.getState());
        assertEquals(2, ticket.getGainedGoals());
        memory.beginSelectionRound();
        assertFalse(memory.adjustPriority(TARGET, 1.0, true).isSuppressed());
    }

    @Test
    void onlyTheFirstSettlementCounts() {
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        AttemptTicket ticket = AttemptTicket.open(memory, Collections.singletonList(TARGET), true);

        ticket.release();
        ticket.close(0);
        ticket.close(3);

        assertEquals(AttemptTicket.State.RELEASED, ticket.getState());
        assertEquals(-1, ticket.getGainedGoals());
        memory.beginSelectionRound();
        assertFalse(memory.adjustPriority(TARGET, 1.0, true).isSuppressed(),
                "a released attempt must not lengthen the cooldown, whatever is reported afterwards");
    }

    @Test
    void deliveredWithoutGainLengthensTheCooldownButUndeliveredDoesNot() {
        RepeatedInjectionMemory delivered = new RepeatedInjectionMemory();
        AttemptTicket.open(delivered, Collections.singletonList(TARGET), true)
                .settleWithoutGain(TargetedResponse.Status.DELIVERED);
        delivered.beginSelectionRound();

        RepeatedInjectionMemory undelivered = new RepeatedInjectionMemory();
        AttemptTicket.open(undelivered, Collections.singletonList(TARGET), true)
                .settleWithoutGain(TargetedResponse.Status.FAILED);
        undelivered.beginSelectionRound();

        assertTrue(delivered.adjustPriority(TARGET, 1.0, true).isSuppressed());
        assertFalse(undelivered.adjustPriority(TARGET, 1.0, true).isSuppressed());
    }

    @Test
    void budgetRefusalsAndFailedCallsAreUndelivered() {
        assertTrue(AttemptTicket.isUndelivered(TargetedResponse.Status.NO_BUDGET));
        assertTrue(AttemptTicket.isUndelivered(TargetedResponse.Status.FAILED));
        assertFalse(AttemptTicket.isUndelivered(TargetedResponse.Status.DELIVERED));
        assertTrue(AttemptTicket.isUndelivered(new LlmBudgetExceededException("spent")));
        assertTrue(AttemptTicket.isUndelivered(new LlmCallFailedException("down", null, false)));
        assertFalse(AttemptTicket.isUndelivered(new IllegalStateException("parse")));
    }
}
