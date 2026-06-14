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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of {@link PromptBuilder#buildWithMetadata()},
 * carrying both the chat messages and context metadata for trace recording.
 */
public final class PromptResult {

    /** Dependency-summary telemetry for trace analysis. */
    public static final class DependencySummaryMetadata {
        private static final DependencySummaryMetadata EMPTY = new DependencySummaryMetadata(
                0, 0, true, false, "none", 0, 0, 0, 0, 0, 0, 0, 0, 0);

        private final int budgetChars;
        private final int perClassSoftCapChars;
        private final boolean perClassSoftCapAuto;
        private final boolean compactSignatures;
        private final String budgetMode;
        private final int candidateClasses;
        private final int emittedClasses;
        private final int emittedTier1Classes;
        private final int emittedTier2Classes;
        private final int emittedTier3Classes;
        private final int emittedInstantiators;
        private final int emittedModifiers;
        private final int droppedByPerClassCap;
        private final int droppedByGlobalBudget;

        public DependencySummaryMetadata(int budgetChars, int perClassSoftCapChars,
                                         boolean perClassSoftCapAuto, boolean compactSignatures,
                                         String budgetMode,
                                         int candidateClasses, int emittedClasses,
                                         int emittedTier1Classes, int emittedTier2Classes, int emittedTier3Classes,
                                         int emittedInstantiators, int emittedModifiers,
                                         int droppedByPerClassCap, int droppedByGlobalBudget) {
            this.budgetChars = budgetChars;
            this.perClassSoftCapChars = perClassSoftCapChars;
            this.perClassSoftCapAuto = perClassSoftCapAuto;
            this.compactSignatures = compactSignatures;
            this.budgetMode = budgetMode == null ? "" : budgetMode;
            this.candidateClasses = candidateClasses;
            this.emittedClasses = emittedClasses;
            this.emittedTier1Classes = emittedTier1Classes;
            this.emittedTier2Classes = emittedTier2Classes;
            this.emittedTier3Classes = emittedTier3Classes;
            this.emittedInstantiators = emittedInstantiators;
            this.emittedModifiers = emittedModifiers;
            this.droppedByPerClassCap = droppedByPerClassCap;
            this.droppedByGlobalBudget = droppedByGlobalBudget;
        }

        public static DependencySummaryMetadata empty() {
            return EMPTY;
        }

        public int getBudgetChars() {
            return budgetChars;
        }

        public int getPerClassSoftCapChars() {
            return perClassSoftCapChars;
        }

        public boolean isPerClassSoftCapAuto() {
            return perClassSoftCapAuto;
        }

        public boolean isCompactSignatures() {
            return compactSignatures;
        }

        public String getBudgetMode() {
            return budgetMode;
        }

        public int getCandidateClasses() {
            return candidateClasses;
        }

        public int getEmittedClasses() {
            return emittedClasses;
        }

        public int getEmittedTier1Classes() {
            return emittedTier1Classes;
        }

        public int getEmittedTier2Classes() {
            return emittedTier2Classes;
        }

        public int getEmittedTier3Classes() {
            return emittedTier3Classes;
        }

        public int getEmittedInstantiators() {
            return emittedInstantiators;
        }

        public int getEmittedModifiers() {
            return emittedModifiers;
        }

        public int getDroppedByPerClassCap() {
            return droppedByPerClassCap;
        }

        public int getDroppedByGlobalBudget() {
            return droppedByGlobalBudget;
        }
    }

    private final List<LlmMessage> messages;
    private final Properties.LlmSutContextMode sutContextMode;
    private final boolean contextUnavailable;
    private final boolean contextTruncated;
    private final boolean contextCommentsStripped;
    private final boolean contextSelectivelyTruncated;
    private final boolean clusterSummaryTruncated;
    private final int clusterSummaryChars;
    private final DependencySummaryMetadata dependencySummaryMetadata;
    private final List<ProblemCardType> diagnosticCardTypes;
    private final List<ProblemCard> diagnosticCards;
    private final List<RepeatedInjectionTarget> repeatedInjectionTargets;

    public static final class Builder {
        private List<LlmMessage> messages = Collections.emptyList();
        private Properties.LlmSutContextMode sutContextMode;
        private boolean contextUnavailable;
        private boolean contextTruncated;
        private boolean contextCommentsStripped;
        private boolean contextSelectivelyTruncated;
        private boolean clusterSummaryTruncated;
        private int clusterSummaryChars;
        private DependencySummaryMetadata dependencySummaryMetadata = DependencySummaryMetadata.empty();
        private List<ProblemCardType> diagnosticCardTypes = Collections.emptyList();
        private List<ProblemCard> diagnosticCards = Collections.emptyList();
        private List<RepeatedInjectionTarget> repeatedInjectionTargets = Collections.emptyList();

        public Builder messages(List<LlmMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder sutContextMode(Properties.LlmSutContextMode sutContextMode) {
            this.sutContextMode = sutContextMode;
            return this;
        }

        public Builder contextUnavailable(boolean contextUnavailable) {
            this.contextUnavailable = contextUnavailable;
            return this;
        }

        public Builder contextTruncated(boolean contextTruncated) {
            this.contextTruncated = contextTruncated;
            return this;
        }

        public Builder contextCommentsStripped(boolean contextCommentsStripped) {
            this.contextCommentsStripped = contextCommentsStripped;
            return this;
        }

        public Builder contextSelectivelyTruncated(boolean contextSelectivelyTruncated) {
            this.contextSelectivelyTruncated = contextSelectivelyTruncated;
            return this;
        }

        public Builder clusterSummaryTruncated(boolean clusterSummaryTruncated) {
            this.clusterSummaryTruncated = clusterSummaryTruncated;
            return this;
        }

        public Builder clusterSummaryChars(int clusterSummaryChars) {
            this.clusterSummaryChars = clusterSummaryChars;
            return this;
        }

        public Builder dependencySummaryMetadata(DependencySummaryMetadata dependencySummaryMetadata) {
            this.dependencySummaryMetadata = dependencySummaryMetadata;
            return this;
        }

        public Builder diagnosticCardTypes(List<ProblemCardType> diagnosticCardTypes) {
            this.diagnosticCardTypes = diagnosticCardTypes;
            return this;
        }

        public Builder diagnosticCards(List<ProblemCard> diagnosticCards) {
            this.diagnosticCards = diagnosticCards;
            return this;
        }

        public Builder repeatedInjectionTargets(List<RepeatedInjectionTarget> repeatedInjectionTargets) {
            this.repeatedInjectionTargets = repeatedInjectionTargets;
            return this;
        }

        public PromptResult build() {
            return new PromptResult(this);
        }
    }

    private PromptResult(Builder builder) {
        this.messages = builder.messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.sutContextMode = builder.sutContextMode;
        this.contextUnavailable = builder.contextUnavailable;
        this.contextTruncated = builder.contextTruncated;
        this.contextCommentsStripped = builder.contextCommentsStripped;
        this.contextSelectivelyTruncated = builder.contextSelectivelyTruncated;
        this.clusterSummaryTruncated = builder.clusterSummaryTruncated;
        this.clusterSummaryChars = builder.clusterSummaryChars;
        this.dependencySummaryMetadata = builder.dependencySummaryMetadata == null
                ? DependencySummaryMetadata.empty() : builder.dependencySummaryMetadata;
        this.diagnosticCardTypes = builder.diagnosticCardTypes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.diagnosticCardTypes));
        this.diagnosticCards = builder.diagnosticCards == null
                ? Collections.<ProblemCard>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.diagnosticCards));
        this.repeatedInjectionTargets = builder.repeatedInjectionTargets == null
                ? Collections.<RepeatedInjectionTarget>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.repeatedInjectionTargets));
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    /**
     * Context mode actually used (may differ from configured mode after fallback),
     * or null if withSutContext was not called.
     */
    public Properties.LlmSutContextMode getSutContextMode() {
        return sutContextMode;
    }

    /** True if context extraction failed and no fallback was available. */
    public boolean isContextUnavailable() {
        return contextUnavailable;
    }

    /**
     * True if the SUT context was truncated to fit within {@code LLM_CONTEXT_MAX_CHARS}.
     */
    public boolean isContextTruncated() {
        return contextTruncated;
    }

    /** True if comments were stripped from the SUT context to fit within the char budget. */
    public boolean isContextCommentsStripped() {
        return contextCommentsStripped;
    }

    /** True if selective method truncation was applied to the SUT context. */
    public boolean isContextSelectivelyTruncated() {
        return contextSelectivelyTruncated;
    }

    /** True if the cluster dependency summary was truncated to fit within the char budget. */
    public boolean isClusterSummaryTruncated() {
        return clusterSummaryTruncated;
    }

    /** Total characters of the cluster summary before truncation (0 if not computed). */
    public int getClusterSummaryChars() {
        return clusterSummaryChars;
    }

    public DependencySummaryMetadata getDependencySummaryMetadata() {
        return dependencySummaryMetadata;
    }

    /**
     * Ordered diagnostic card types selected for this prompt, if any.
     * Empty for non-diagnostic stagnation prompts.
     */
    public List<ProblemCardType> getDiagnosticCardTypes() {
        return diagnosticCardTypes;
    }

    /**
     * The full selected problem cards for this prompt (with related goals),
     * if any. Empty for non-diagnostic prompts. Used by
     * {@code ProblemCardLogRecorder} to log card instances at the attempt
     * registration point.
     */
    public List<ProblemCard> getDiagnosticCards() {
        return diagnosticCards;
    }

    public List<RepeatedInjectionTarget> getRepeatedInjectionTargets() {
        return repeatedInjectionTargets;
    }

    public Builder toBuilder() {
        return new Builder()
                .messages(messages)
                .sutContextMode(sutContextMode)
                .contextUnavailable(contextUnavailable)
                .contextTruncated(contextTruncated)
                .contextCommentsStripped(contextCommentsStripped)
                .contextSelectivelyTruncated(contextSelectivelyTruncated)
                .clusterSummaryTruncated(clusterSummaryTruncated)
                .clusterSummaryChars(clusterSummaryChars)
                .dependencySummaryMetadata(dependencySummaryMetadata)
                .diagnosticCardTypes(diagnosticCardTypes)
                .diagnosticCards(diagnosticCards)
                .repeatedInjectionTargets(repeatedInjectionTargets);
    }
}
