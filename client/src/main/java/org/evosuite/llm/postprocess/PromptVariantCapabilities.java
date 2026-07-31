/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable capability description for a post-processing prompt variant.
 * Isolated variants enable one treatment over P2; P12 composes all treatments.
 */
public final class PromptVariantCapabilities {

    private final boolean canonicalCandidates;
    private final boolean actionRoles;
    private final boolean relationalOpportunities;
    private final boolean stabilityLabels;
    private final boolean compactObservedCalls;
    private final boolean literalDiscipline;
    private final boolean assertableTypesOnly;
    private final boolean exceptionAdjacentPlacements;

    private PromptVariantCapabilities(boolean canonicalCandidates, boolean actionRoles,
                                      boolean relationalOpportunities, boolean stabilityLabels,
                                      boolean compactObservedCalls, boolean literalDiscipline,
                                      boolean assertableTypesOnly, boolean exceptionAdjacentPlacements) {
        this.canonicalCandidates = canonicalCandidates;
        this.actionRoles = actionRoles;
        this.relationalOpportunities = relationalOpportunities;
        this.stabilityLabels = stabilityLabels;
        this.compactObservedCalls = compactObservedCalls;
        this.literalDiscipline = literalDiscipline;
        this.assertableTypesOnly = assertableTypesOnly;
        this.exceptionAdjacentPlacements = exceptionAdjacentPlacements;
    }

    public static PromptVariantCapabilities forVariant(Properties.LlmPostProcessingPromptVariant variant) {
        Properties.LlmPostProcessingPromptVariant effective = variant == null
                ? Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION : variant;
        if (effective == Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2) {
            return new PromptVariantCapabilities(true, true, true, true, true, true, true, true);
        }
        return new PromptVariantCapabilities(
                effective == Properties.LlmPostProcessingPromptVariant.P4_CANONICAL_CANDIDATES,
                effective == Properties.LlmPostProcessingPromptVariant.P5_ACTION_RANKED_CANDIDATES,
                effective == Properties.LlmPostProcessingPromptVariant.P6_RELATIONAL_OPPORTUNITIES,
                effective == Properties.LlmPostProcessingPromptVariant.P7_STABILITY_LABELS,
                effective == Properties.LlmPostProcessingPromptVariant.P8_COMPACT_OBSERVED_CALLS,
                effective == Properties.LlmPostProcessingPromptVariant.P9_LITERAL_DISCIPLINE,
                effective == Properties.LlmPostProcessingPromptVariant.P10_ASSERTABLE_TYPES_ONLY,
                effective == Properties.LlmPostProcessingPromptVariant.P11_EXCEPTION_ADJACENT_ASSERTIONS);
    }

    public boolean hasCanonicalCandidates() {
        return canonicalCandidates;
    }

    public boolean hasActionRoles() {
        return actionRoles;
    }

    public boolean hasRelationalOpportunities() {
        return relationalOpportunities;
    }

    public boolean hasStabilityLabels() {
        return stabilityLabels;
    }

    public boolean hasCompactObservedCalls() {
        return compactObservedCalls;
    }

    public boolean hasLiteralDiscipline() {
        return literalDiscipline;
    }

    public boolean hasAssertableTypesOnly() {
        return assertableTypesOnly;
    }

    public boolean hasExceptionAdjacentPlacements() {
        return exceptionAdjacentPlacements;
    }

    /** Stable names written to traces and manifests. */
    public List<String> enabledNames() {
        List<String> result = new ArrayList<>();
        add(result, canonicalCandidates, "canonicalCandidates");
        add(result, actionRoles, "actionRoles");
        add(result, relationalOpportunities, "relationalOpportunities");
        add(result, stabilityLabels, "stabilityLabels");
        add(result, compactObservedCalls, "compactObservedCalls");
        add(result, literalDiscipline, "literalDiscipline");
        add(result, assertableTypesOnly, "assertableTypesOnly");
        add(result, exceptionAdjacentPlacements, "exceptionAdjacentPlacements");
        return Collections.unmodifiableList(result);
    }

    private static void add(List<String> names, boolean enabled, String name) {
        if (enabled) {
            names.add(name);
        }
    }
}
