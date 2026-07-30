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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.testcase.synthesizer.FunctionalMockSetupCoordinator;
import org.evosuite.testcase.synthesizer.ObjectRuleTelemetry;

/**
 * Mutable state belonging to one EvoObj test-generation run.
 *
 * <p>The session is owned by {@link org.evosuite.TestGenerationContext}; factories and search
 * algorithms receive that same session instead of communicating through public mutable fields on
 * {@link RandomLengthTestFactory}. Package-private counters keep the hot synthesis path concise,
 * while target handoff and template identifiers use explicit methods.</p>
 */
public final class ObjectRuleSession {

    int legitimizationTrials;
    int legitimizationSuccess;
    int legitimizationSkipped;
    int templateInjections;
    int currentIteration;
    int lastFreshSynthesisIteration;
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
    long legitimizationTimeMs;
    int inconsistentTemplatesDiscarded;

    private int nextTemplateId;
    private int lastProducedTemplateId;
    private Branch workingBranch;
    private List<BranchCoverageTestFitness> workingGoals;

    public ObjectRuleSession() {
        reset();
    }

    public synchronized void reset() {
        legitimizationTrials = 0;
        legitimizationSuccess = 0;
        legitimizationSkipped = 0;
        templateInjections = 0;
        currentIteration = 0;
        lastFreshSynthesisIteration = 0;
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
        legitimizationTimeMs = 0;
        inconsistentTemplatesDiscarded = 0;
        nextTemplateId = 1;
        lastProducedTemplateId = 0;
        workingBranch = null;
        workingGoals = new ArrayList<>();
        FunctionalMockSetupCoordinator.reset();
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

    public synchronized List<BranchCoverageTestFitness> getWorkingGoals() {
        return Collections.unmodifiableList(new ArrayList<>(workingGoals));
    }

    public synchronized boolean hasWorkingGoals() {
        return !workingGoals.isEmpty();
    }

    synchronized int markProducedTemplate(boolean coveredImmediately) {
        int id = nextTemplateId++;
        lastProducedTemplateId = id;
        ObjectRuleTelemetry.getInstance().recordInjection(id, currentIteration);
        if (coveredImmediately) {
            ObjectRuleTelemetry.getInstance().recordImmediateCoverage(id);
        }
        return id;
    }

    public synchronized int consumeLastProducedTemplateId() {
        int id = lastProducedTemplateId;
        lastProducedTemplateId = 0;
        return id;
    }

    public synchronized int getInconsistentTemplatesDiscarded() {
        return inconsistentTemplatesDiscarded;
    }

    public synchronized void resetInconsistentTemplatesDiscarded() {
        inconsistentTemplatesDiscarded = 0;
    }
}
