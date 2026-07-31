/* Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors. */
package org.evosuite.llm.postprocess;

/** Counts of proposals present in one decoded wire response. */
public final class PostProcessingCounts {
    private final int testNames;
    private final int variableNames;
    private final int comments;
    private final int sectionBreaks;
    private final int assertions;

    public PostProcessingCounts(int testNames, int variableNames, int comments,
                                int sectionBreaks, int assertions) {
        this.testNames = Math.max(0, testNames);
        this.variableNames = Math.max(0, variableNames);
        this.comments = Math.max(0, comments);
        this.sectionBreaks = Math.max(0, sectionBreaks);
        this.assertions = Math.max(0, assertions);
    }

    public int getTestNames() { return testNames; }
    public int getVariableNames() { return variableNames; }
    public int getComments() { return comments; }
    public int getSectionBreaks() { return sectionBreaks; }
    public int getAssertions() { return assertions; }

    public static PostProcessingCounts none() {
        return new PostProcessingCounts(0, 0, 0, 0, 0);
    }
}
