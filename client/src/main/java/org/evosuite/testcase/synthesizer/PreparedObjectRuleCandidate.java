/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.synthesizer;

import org.evosuite.coverage.branch.Branch;

/**
 * Immutable analysis result reused by entry construction and template synthesis.
 */
public final class PreparedObjectRuleCandidate {

    private final Branch branch;
    private final TargetContext targetContext;
    private final PartialGraph partialGraph;

    /**
     * Creates a prepared candidate from its analyzed branch, target, and graph.
     */
    public PreparedObjectRuleCandidate(
            Branch branch, TargetContext targetContext, PartialGraph partialGraph) {
        this.branch = branch;
        this.targetContext = targetContext;
        this.partialGraph = partialGraph;
    }

    public Branch getBranch() {
        return branch;
    }

    public TargetContext getTargetContext() {
        return targetContext;
    }

    public PartialGraph getPartialGraph() {
        return partialGraph;
    }
}
