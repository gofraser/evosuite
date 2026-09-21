/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.factories;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.graphs.interprocedural.DefUseAnalyzer;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.synthesizer.DataDependencyUtil;
import org.evosuite.testcase.synthesizer.FunctionalMockSetupCoordinator;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.synthesizer.TemplateCache;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run-scoped owner for all EvoObj coordination state.
 *
 * <p>The runtime deliberately owns the mutable session, candidate selector and template cache.
 * Callers obtain these collaborators from the current {@code TestGenerationContext}; no EvoObj
 * singleton or thread-local is needed to distinguish one generation run from another.
 */
public final class ObjectRuleRuntime {

    private final ObjectRuleSession session = new ObjectRuleSession();
    private final ObjectRuleBranchSelector branchSelector = new ObjectRuleBranchSelector();
    private final TemplateCache templateCache = new TemplateCache();
    private final Map<String, Map<Branch, Set<DepVariable>>> branchInterestedVarsMap =
            new LinkedHashMap<>();
    private final Map<Integer, List<String>> recommendedClasses = new LinkedHashMap<>();
    private volatile boolean entryConstruction;
    private volatile boolean synthesisConstruction;
    private TargetContext activeTargetContext;
    private volatile Long setterAnalysisDeadlineNanos;
    private volatile boolean setterAnalysisTimedOut;
    private volatile Long synthesisAttemptDeadlineNanos;
    private volatile boolean synthesisAttemptTimedOut;
    private final AtomicLong resetEpoch = new AtomicLong();

    /**
     * Returns the run-scoped object-rule session.
     */
    public ObjectRuleSession getSession() {
        return session;
    }

    /**
     * Returns the selector used for object-rule branches in this run.
     */
    public ObjectRuleBranchSelector getBranchSelector() {
        return branchSelector;
    }

    /**
     * Returns the cache of synthesized templates for this run.
     */
    public TemplateCache getTemplateCache() {
        return templateCache;
    }

    /**
     * Returns the branch-to-variable map collected during analysis.
     */
    public Map<String, Map<Branch, Set<DepVariable>>> getBranchInterestedVarsMap() {
        return Collections.unmodifiableMap(branchInterestedVarsMap);
    }

    /** Stores the analyzed branch map for one entry method. */
    public void putBranchInterestedVars(String entrySignature,
            Map<Branch, Set<DepVariable>> branchMap) {
        branchInterestedVarsMap.put(entrySignature, branchMap);
    }

    /** Removes all analyzed branch maps. */
    public void clearBranchInterestedVars() {
        branchInterestedVarsMap.clear();
    }

    /**
     * Returns the classes recommended by the current object-rule analysis.
     */
    public Map<Integer, List<String>> getRecommendedClasses() {
        return Collections.unmodifiableMap(recommendedClasses);
    }

    /** Replaces the recommended concrete classes discovered during analysis. */
    public void setRecommendedClasses(Map<Integer, List<String>> recommendations) {
        recommendedClasses.clear();
        recommendedClasses.putAll(recommendations);
    }

    /** Adds recommendations discovered for another analyzed entry method. */
    public void mergeRecommendedClasses(Map<Integer, List<String>> recommendations) {
        recommendedClasses.putAll(recommendations);
    }

    /**
     * Returns whether entry construction is currently active.
     */
    public boolean isEntryConstruction() {
        return entryConstruction;
    }

    /**
     * Enables or disables entry construction and returns the previous value.
     */
    public boolean setEntryConstruction(boolean enabled) {
        boolean previous = entryConstruction;
        entryConstruction = enabled;
        return previous;
    }

    /**
     * Returns whether synthesis construction is currently active.
     */
    public boolean isSynthesisConstruction() {
        return synthesisConstruction;
    }

    /**
     * Enables or disables synthesis construction and returns the previous value.
     */
    public boolean setSynthesisConstruction(boolean enabled) {
        boolean previous = synthesisConstruction;
        synthesisConstruction = enabled;
        return previous;
    }

    /**
     * Returns the target currently being synthesized, if any.
     */
    public TargetContext getActiveTargetContext() {
        return activeTargetContext;
    }

    /**
     * Sets the target currently being synthesized.
     */
    public void setActiveTargetContext(TargetContext context) {
        activeTargetContext = context;
    }

    /**
     * Monotonically increasing lifecycle token used by collaborators which outlive a context
     * reset but still hold per-run negative caches.
     */
    public long getResetEpoch() {
        return resetEpoch.get();
    }

    /**
     * Starts a setter-analysis window, using zero for an unlimited window.
     */
    public void beginSetterAnalysis(long deadlineNanos) {
        setterAnalysisDeadlineNanos = deadlineNanos > 0 ? deadlineNanos : null;
        setterAnalysisTimedOut = false;
    }

    /**
     * Returns whether the current setter-analysis window has expired.
     */
    public boolean setterAnalysisTimedOut() {
        if (!setterAnalysisTimedOut
                && setterAnalysisDeadlineNanos != null
                && System.nanoTime() >= setterAnalysisDeadlineNanos) {
            setterAnalysisTimedOut = true;
        }
        return setterAnalysisTimedOut;
    }

    /**
     * Ends the current setter-analysis window.
     */
    public void endSetterAnalysis() {
        setterAnalysisDeadlineNanos = null;
        setterAnalysisTimedOut = false;
    }

    /**
     * Starts the wall-clock budget for one whole-class EvoObj synthesis attempt (one call to
     * {@code ObjectRuleTemplateSynthesizer.synthesize}), using zero for an unlimited window. This
     * is a coarser, outer budget than {@link #beginSetterAnalysis(long)}: the per-entry-method
     * setter-analysis window bounds a single {@code analyzeFieldSetter} entry point, but a whole
     * synthesis attempt can call it, and the {@code UsedReferenceSearcher} paths that call
     * {@code analyzeFieldSetter} directly without opening a setter-analysis window at all, many
     * times over. {@link #setterAnalysisTimedOut()} and this method are both consulted by
     * {@code DataDependencyUtil}'s recursion guards so every entry point is bounded even when it
     * never called {@link #beginSetterAnalysis(long)} itself.
     */
    public void beginSynthesisAttempt(long deadlineNanos) {
        synthesisAttemptDeadlineNanos = deadlineNanos > 0 ? deadlineNanos : null;
        synthesisAttemptTimedOut = false;
    }

    /**
     * Returns whether the current whole-class synthesis attempt has exceeded its wall-clock
     * budget.
     */
    public boolean synthesisAttemptTimedOut() {
        if (!synthesisAttemptTimedOut
                && synthesisAttemptDeadlineNanos != null
                && System.nanoTime() >= synthesisAttemptDeadlineNanos) {
            synthesisAttemptTimedOut = true;
        }
        return synthesisAttemptTimedOut;
    }

    /**
     * Ends the current whole-class synthesis attempt window.
     */
    public void endSynthesisAttempt() {
        synthesisAttemptDeadlineNanos = null;
        synthesisAttemptTimedOut = false;
    }

    /**
     * Clears all run-scoped object-rule state.
     */
    public void reset() {
        resetEpoch.incrementAndGet();
        session.reset();
        entryConstruction = false;
        synthesisConstruction = false;
        activeTargetContext = null;
        endSetterAnalysis();
        endSynthesisAttempt();
        branchSelector.reset();
        templateCache.reset();
        clearBranchInterestedVars();
        recommendedClasses.clear();
        FBranchDefUseAnalyzer.analyzedMethods.clear();
        DefUseAnalyzer.resetSingleton();
        org.evosuite.utils.MethodUtil.resetCache();
        FunctionalMockSetupCoordinator.reset();
        DataDependencyUtil.resetSetterAnalysisCache();
        ObjectRuleTemplateSynthesizer.resetSynthesisTimeoutWarnings();
    }
}
