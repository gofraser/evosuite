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
package org.evosuite.llm.postprocess;

/** Counts of proposals present in one decoded wire response. */
public final class PostProcessingCounts {
    private final int testNames;
    private final int variableNames;
    private final int comments;
    private final int sectionBreaks;
    private final int assertions;

    /**
     * Creates the counts; negative values are treated as zero.
     *
     * @param testNames the number of test-name proposals
     * @param variableNames the number of variable-name proposals
     * @param comments the number of comment proposals
     * @param sectionBreaks the number of section-break proposals
     * @param assertions the number of assertion proposals
     */
    public PostProcessingCounts(int testNames, int variableNames, int comments,
                                int sectionBreaks, int assertions) {
        this.testNames = Math.max(0, testNames);
        this.variableNames = Math.max(0, variableNames);
        this.comments = Math.max(0, comments);
        this.sectionBreaks = Math.max(0, sectionBreaks);
        this.assertions = Math.max(0, assertions);
    }

    public int getTestNames() {
        return testNames;
    }

    public int getVariableNames() {
        return variableNames;
    }

    public int getComments() {
        return comments;
    }

    public int getSectionBreaks() {
        return sectionBreaks;
    }

    public int getAssertions() {
        return assertions;
    }

    public static PostProcessingCounts none() {
        return new PostProcessingCounts(0, 0, 0, 0, 0);
    }
}
