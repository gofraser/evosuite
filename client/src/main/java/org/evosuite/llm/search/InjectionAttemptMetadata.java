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
import java.util.Collections;
import java.util.List;

/**
 * Candidate-side attribution metadata for one prompt attempt.
 */
public final class InjectionAttemptMetadata {

    private final String attemptId;
    private final List<ProblemCardType> diagnosticCardTypes;

    public InjectionAttemptMetadata(String attemptId, List<ProblemCardType> diagnosticCardTypes) {
        this.attemptId = attemptId == null ? "" : attemptId.trim();
        this.diagnosticCardTypes = diagnosticCardTypes == null
                ? Collections.<ProblemCardType>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(diagnosticCardTypes));
    }

    public String getAttemptId() {
        return attemptId;
    }

    public List<ProblemCardType> getDiagnosticCardTypes() {
        return diagnosticCardTypes;
    }
}
