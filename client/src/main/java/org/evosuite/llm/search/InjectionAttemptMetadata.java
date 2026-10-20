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

import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Candidate-side attribution metadata for one prompt attempt. It carries the attempt's {@link
 * AttemptTicket}, so whoever evaluates the candidate settles the attempt directly.
 */
public final class InjectionAttemptMetadata {

    private final AttemptTicket ticket;
    private final List<ProblemCardType> diagnosticCardTypes;
    private final List<TestFitnessFunction> targetGoals;

    public InjectionAttemptMetadata(AttemptTicket ticket, List<ProblemCardType> diagnosticCardTypes) {
        this(ticket, diagnosticCardTypes, null);
    }

    /**
     * Creates metadata describing one injection attempt.
     * @param ticket the attempt's ticket, null is treated as untracked
     * @param diagnosticCardTypes the types of the cards in the prompt
     * @param targetGoals the uncovered goals the prompt targeted
     */
    public InjectionAttemptMetadata(AttemptTicket ticket, List<ProblemCardType> diagnosticCardTypes,
                                    Collection<TestFitnessFunction> targetGoals) {
        this.ticket = ticket == null ? AttemptTicket.untracked() : ticket;
        this.diagnosticCardTypes = diagnosticCardTypes == null
                ? Collections.<ProblemCardType>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(diagnosticCardTypes));
        this.targetGoals = targetGoals == null || targetGoals.isEmpty()
                ? Collections.<TestFitnessFunction>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(targetGoals));
    }

    /** The attempt this candidate came from; untracked when nothing tracks it. */
    public AttemptTicket getTicket() {
        return ticket;
    }

    /** The attempt's id, {@code ""} when untracked. */
    public String getAttemptId() {
        return ticket.getId();
    }

    public List<ProblemCardType> getDiagnosticCardTypes() {
        return diagnosticCardTypes;
    }

    /**
     * Returns the uncovered goals the prompt targeted.
     *
     * @return the goals, possibly empty, never null
     */
    public List<TestFitnessFunction> getTargetGoals() {
        return targetGoals;
    }
}
