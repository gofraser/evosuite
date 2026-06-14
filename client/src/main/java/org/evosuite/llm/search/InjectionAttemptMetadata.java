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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.evosuite.testcase.TestFitnessFunction;

/**
 * Candidate-side attribution metadata for one prompt attempt.
 */
public final class InjectionAttemptMetadata {

    private final String attemptId;
    private final List<ProblemCardType> diagnosticCardTypes;
    private final List<TestFitnessFunction> targetGoals;

    public InjectionAttemptMetadata(String attemptId, List<ProblemCardType> diagnosticCardTypes) {
        this(attemptId, diagnosticCardTypes, null);
    }

    public InjectionAttemptMetadata(String attemptId, List<ProblemCardType> diagnosticCardTypes,
                                    Collection<TestFitnessFunction> targetGoals) {
        this.attemptId = attemptId == null ? "" : attemptId.trim();
        this.diagnosticCardTypes = diagnosticCardTypes == null
                ? Collections.<ProblemCardType>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(diagnosticCardTypes));
        this.targetGoals = targetGoals == null || targetGoals.isEmpty()
                ? Collections.<TestFitnessFunction>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(targetGoals));
    }

    public String getAttemptId() {
        return attemptId;
    }

    public List<ProblemCardType> getDiagnosticCardTypes() {
        return diagnosticCardTypes;
    }

    /** @return the uncovered goals the prompt targeted (possibly empty, never null). */
    public List<TestFitnessFunction> getTargetGoals() {
        return targetGoals;
    }
}
