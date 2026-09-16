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
import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Per-run memo of the rendered static prompt sections — the SUT context and
 * the test-cluster dependency summary — so {@link PromptBuilder} can render
 * each of them once per run and reuse the result across every prompt built
 * in that run, instead of recomputing (and re-shipping into every user
 * message) the same expensive text.
 *
 * <p>This class does not own any prompt text: {@link PromptBuilder} still
 * builds the literal section strings and drives the expensive calls
 * ({@code SutContextProviderFactory}, {@code TestClusterSummarizer}). It only
 * caches what {@code PromptBuilder} already rendered, keyed per entry kind,
 * so every string that reaches the model still lives in exactly one place.
 */
public final class StaticPromptContext {

    /**
     * Header of the dependency-summary section. Shared between this class'
     * {@link #containsDependencyContext(String)} check and
     * {@link PromptBuilder}'s rendering of the same section, so the two never
     * drift apart.
     */
    public static final String DEPENDENCY_SECTION_HEADER =
            "Available dependency types (standard Java library classes may also be used freely):";

    private static final ConcurrentMap<String, SutEntry> SUT_ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, DependencyEntry> DEPENDENCY_ENTRIES = new ConcurrentHashMap<>();

    private StaticPromptContext() {
    }

    /**
     * Returns the memoised SUT-context entry for {@code (className, includeReminders)},
     * rendering it via {@code supplier} on first use. Only a {@link SutEntry#isStable() stable}
     * render is memoised: a fallback (e.g. the decompiler timed out on a cold JVM) or a
     * cluster-dependent {@code SIGNATURE_ONLY} render is returned for this prompt but rendered
     * again for the next one, mirroring what {@code SutContextProviderFactory} itself caches.
     */
    public static SutEntry sutEntry(String className, boolean includeReminders, Supplier<SutEntry> supplier) {
        String key = (className == null ? "" : className) + "|" + includeReminders;
        SutEntry entry = SUT_ENTRIES.get(key);
        if (entry != null) {
            return entry;
        }
        entry = supplier.get();
        if (entry.isStable()) {
            SutEntry raced = SUT_ENTRIES.putIfAbsent(key, entry);
            if (raced != null) {
                return raced;
            }
        }
        return entry;
    }

    /**
     * Returns the memoised dependency-summary entry for {@code className},
     * rendering it via {@code supplier} on first use.
     */
    public static DependencyEntry dependencyEntry(String className, Supplier<DependencyEntry> supplier) {
        String key = className == null ? "" : className;
        return DEPENDENCY_ENTRIES.computeIfAbsent(key, k -> supplier.get());
    }

    /** Clears both memos after an EvoSuite run completes, so the next run re-renders. */
    public static void resetForRunCompletion() {
        SUT_ENTRIES.clear();
        DEPENDENCY_ENTRIES.clear();
    }

    /** Clears both memos for test isolation. */
    public static void resetInstanceForTesting() {
        resetForRunCompletion();
    }

    /** True if {@code systemMessage} already carries the dependency-summary section. */
    public static boolean containsDependencyContext(String systemMessage) {
        return systemMessage != null && systemMessage.contains(DEPENDENCY_SECTION_HEADER);
    }

    /**
     * Snapshot of the class names the test cluster currently has generators
     * for. Used to seed a {@link DependencyEntry} at render time and, later,
     * to detect growth (classes added by cluster expansion) against that
     * snapshot. This mirrors the same {@code getGeneratorsByType()} seam
     * {@code TestClusterSummarizer} itself walks, so a test double only needs
     * to stub that one method to control both the summary and the growth
     * check — no bespoke hook required.
     */
    static Set<String> clusterClassNames(TestCluster cluster) {
        if (cluster == null) {
            return Collections.emptySet();
        }
        Map<GenericClass<?>, ?> generatorsByType = cluster.getGeneratorsByType();
        if (generatorsByType == null || generatorsByType.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new TreeSet<>();
        for (GenericClass<?> type : generatorsByType.keySet()) {
            if (type != null && type.getClassName() != null) {
                names.add(type.getClassName());
            }
        }
        return names;
    }

    /** Memoised SUT-context section: the rendered text plus the metadata {@link PromptResult} reports. */
    public static final class SutEntry {
        private final String sectionText;
        private final List<String> reminderTexts;
        private final Properties.LlmSutContextMode modeUsed;
        private final boolean unavailable;
        private final boolean truncated;
        private final boolean commentsStripped;
        private final boolean selectivelyTruncated;
        private final boolean stable;

        public SutEntry(String sectionText, List<String> reminderTexts,
                        Properties.LlmSutContextMode modeUsed, boolean unavailable,
                        boolean truncated, boolean commentsStripped, boolean selectivelyTruncated) {
            this(sectionText, reminderTexts, modeUsed, unavailable, truncated, commentsStripped,
                    selectivelyTruncated, true);
        }

        public SutEntry(String sectionText, List<String> reminderTexts,
                        Properties.LlmSutContextMode modeUsed, boolean unavailable,
                        boolean truncated, boolean commentsStripped, boolean selectivelyTruncated,
                        boolean stable) {
            this.sectionText = sectionText;
            // Defensive copy: reminderTexts is caller-supplied and this entry is
            // memoised and shared across every prompt (and thread) in the run.
            this.reminderTexts = reminderTexts == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(reminderTexts));
            this.modeUsed = modeUsed;
            this.unavailable = unavailable;
            this.truncated = truncated;
            this.commentsStripped = commentsStripped;
            this.selectivelyTruncated = selectivelyTruncated;
            this.stable = stable;
        }

        /**
         * True if this render is class-intrinsic and may be reused for the rest of the run;
         * false for a fallback or cluster-dependent render that should be redone per prompt.
         */
        public boolean isStable() {
            return stable;
        }

        /** The rendered "CLASS UNDER TEST (SUT): ..." block, or {@code null} if no context was available. */
        public String getSectionText() {
            return sectionText;
        }

        /** Reminder strings rendered alongside the SUT block (empty if reminders were not requested). */
        public List<String> getReminderTexts() {
            return reminderTexts;
        }

        public Properties.LlmSutContextMode getModeUsed() {
            return modeUsed;
        }

        public boolean isUnavailable() {
            return unavailable;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public boolean isCommentsStripped() {
            return commentsStripped;
        }

        public boolean isSelectivelyTruncated() {
            return selectivelyTruncated;
        }
    }

    /**
     * Memoised dependency-summary section: the rendered text, its telemetry,
     * and the cluster class-name snapshot it was rendered against (used to
     * detect later cluster growth).
     */
    public static final class DependencyEntry {
        private final String sectionText;
        private final boolean truncated;
        private final int totalCharsBeforeTruncation;
        private final String budgetMode;
        private final PromptResult.DependencySummaryMetadata metadata;
        private final Set<String> clusterClassNamesAtRender;

        public DependencyEntry(String sectionText, boolean truncated, int totalCharsBeforeTruncation,
                               String budgetMode, PromptResult.DependencySummaryMetadata metadata,
                               Set<String> clusterClassNamesAtRender) {
            this.sectionText = sectionText;
            this.truncated = truncated;
            this.totalCharsBeforeTruncation = totalCharsBeforeTruncation;
            this.budgetMode = budgetMode;
            this.metadata = metadata == null ? PromptResult.DependencySummaryMetadata.empty() : metadata;
            // Defensive copy: this snapshot is memoised and read back later (from
            // other prompts/threads) to detect cluster growth against it.
            this.clusterClassNamesAtRender = clusterClassNamesAtRender == null
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new TreeSet<>(clusterClassNamesAtRender));
        }

        /** The rendered "Available dependency types..." block, or {@code null} if the summary was empty. */
        public String getSectionText() {
            return sectionText;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public int getTotalCharsBeforeTruncation() {
            return totalCharsBeforeTruncation;
        }

        public String getBudgetMode() {
            return budgetMode;
        }

        public PromptResult.DependencySummaryMetadata getMetadata() {
            return metadata;
        }

        /** Class names the cluster had generators for at render time. */
        public Set<String> getClusterClassNamesAtRender() {
            return clusterClassNamesAtRender;
        }
    }
}
