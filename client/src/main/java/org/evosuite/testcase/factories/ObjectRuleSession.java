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
 */
package org.evosuite.testcase.factories;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable state belonging to one EvoObj test-generation run.
 *
 * <p>The session is owned by {@link org.evosuite.TestGenerationContext}; factories and search
 * algorithms receive that same session instead of communicating through public mutable fields on
 * {@link RandomLengthTestFactory}. Package-private counters keep the hot synthesis path concise,
 * while target handoff uses explicit methods.
 */
public final class ObjectRuleSession {

    int templateInjections;
    int currentIteration;
    int templateCacheHits;
    int freshSyntheses;

    int consecutiveTargetUnreached;
    long constructionTimeAtLastTargetReachMs;
    int consecutiveUnbeneficialSyntheses;
    long constructionTimeAtLastBenefitMs;

    int firstShotSuccesses;
    int immediateCoverage;
    int reachedNotCovered;
    int workingGoalUnavailable;
    int workingGoalEvaluationFailures;
    boolean workingGoalFailureLogged;

    long templateSynthesisTimeMs;
    long synthesisTimeMs;
    long templateExecTimeMs;

    int inconsistentTemplatesDiscarded;
    private Branch workingBranch;
    private List<BranchCoverageTestFitness> workingGoals;

    public ObjectRuleSession() {
        reset();
    }

    public synchronized void reset() {

        templateInjections = 0;
        currentIteration = 0;

        templateCacheHits = 0;
        freshSyntheses = 0;
        consecutiveTargetUnreached = 0;
        constructionTimeAtLastTargetReachMs = 0;
        consecutiveUnbeneficialSyntheses = 0;
        constructionTimeAtLastBenefitMs = 0;
        firstShotSuccesses = 0;
        immediateCoverage = 0;
        reachedNotCovered = 0;
        workingGoalUnavailable = 0;
        workingGoalEvaluationFailures = 0;
        workingGoalFailureLogged = false;
        templateSynthesisTimeMs = 0;
        synthesisTimeMs = 0;
        templateExecTimeMs = 0;

        inconsistentTemplatesDiscarded = 0;
        workingBranch = null;
        workingGoals = new ArrayList<>();
    }

    public synchronized void setWorkingTarget(int iteration, Branch branch,
            List<BranchCoverageTestFitness> goals) {
        currentIteration = iteration;
        workingBranch = branch;
        workingGoals = goals == null ? new ArrayList<>() : new ArrayList<>(goals);
    }

    public synchronized void clearWorkingTarget() {
        workingBranch = null;
        workingGoals = new ArrayList<>();
    }

    public synchronized Branch getWorkingBranch() {
        return workingBranch;
    }

    public synchronized int getCurrentIteration() {
        return currentIteration;
    }

    public synchronized List<BranchCoverageTestFitness> getWorkingGoals() {
        return Collections.unmodifiableList(new ArrayList<>(workingGoals));
    }

    public synchronized boolean hasWorkingGoals() {
        return !workingGoals.isEmpty();
    }

    public synchronized int getInconsistentTemplatesDiscarded() {
        return inconsistentTemplatesDiscarded;
    }

    public synchronized void resetInconsistentTemplatesDiscarded() {
        inconsistentTemplatesDiscarded = 0;
    }
}
