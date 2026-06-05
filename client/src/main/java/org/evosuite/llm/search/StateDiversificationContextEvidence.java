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

/**
 * Dominant-context evidence used by {@link ProblemCardType#STATE_DIVERSIFICATION_GAP}
 * to expose which invocation-context bucket has accumulated the most successes
 * for a method whose target is otherwise reached but in a single regime.
 */
final class StateDiversificationContextEvidence {
    final String contextKey;
    final String contextLabel;
    final int successes;

    StateDiversificationContextEvidence(String contextKey, String contextLabel, int successes) {
        this.contextKey = contextKey == null ? "" : contextKey;
        this.contextLabel = contextLabel == null || contextLabel.isEmpty()
                ? InvocationContextClassifier.DEFAULT_CONTEXT.getLabel()
                : contextLabel;
        this.successes = successes;
    }

    double successShare(int totalSuccesses) {
        if (totalSuccesses <= 0) {
            return 0.0;
        }
        return ((double) successes) / (double) totalSuccesses;
    }

    boolean isStrongerThan(StateDiversificationContextEvidence other) {
        if (other == null) {
            return true;
        }
        int successCompare = Integer.compare(successes, other.successes);
        if (successCompare != 0) {
            return successCompare > 0;
        }
        return contextKey.compareTo(other.contextKey) < 0;
    }
}
