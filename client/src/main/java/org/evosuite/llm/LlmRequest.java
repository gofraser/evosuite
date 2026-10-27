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
package org.evosuite.llm;

import org.evosuite.llm.prompt.PromptResult;

import java.util.Collections;
import java.util.List;

/**
 * One call to {@link LlmService#query(LlmRequest)}: the messages, the feature
 * the call is charged to, and what the trace records about it.
 *
 * <p>The prompt context metadata (SUT context mode, truncation flags,
 * dependency summary) comes from the {@link PromptResult} the request was
 * built from, if any. A request is immutable; the {@code with} methods return
 * a modified copy.
 */
public final class LlmRequest {

    /** {@link #getDeadlineNanos()} of a request without a deadline. */
    public static final long NO_DEADLINE = PhaseDeadline.NONE;

    private final List<LlmMessage> messages;
    private final LlmFeature feature;
    private final PromptResult context;
    private final int repairAttempt;
    private final boolean expansionAttempted;
    private final List<String> expandedClasses;
    private final long deadlineNanos;
    private final PostProcessingAttribution attribution;

    /** The test a post-processing call is about, as the trace records it. */
    public static final class PostProcessingAttribution {
        /** The attribution of a call that is not about one post-processed test. */
        static final PostProcessingAttribution NONE = new PostProcessingAttribution("", "", -1);

        private final String minimizationStatus;
        private final String minimizationStopCause;
        private final int testIndex;

        private PostProcessingAttribution(String minimizationStatus, String minimizationStopCause,
                                          int testIndex) {
            this.minimizationStatus = minimizationStatus == null ? "" : minimizationStatus;
            this.minimizationStopCause = minimizationStopCause == null ? "" : minimizationStopCause;
            this.testIndex = testIndex;
        }

        public String getMinimizationStatus() {
            return minimizationStatus;
        }

        public String getMinimizationStopCause() {
            return minimizationStopCause;
        }

        public int getTestIndex() {
            return testIndex;
        }
    }

    private LlmRequest(List<LlmMessage> messages, LlmFeature feature, PromptResult context,
                       int repairAttempt, boolean expansionAttempted, List<String> expandedClasses,
                       long deadlineNanos, PostProcessingAttribution attribution) {
        this.messages = messages;
        this.feature = feature;
        this.context = context;
        this.repairAttempt = repairAttempt;
        this.expansionAttempted = expansionAttempted;
        this.expandedClasses = expandedClasses;
        this.deadlineNanos = deadlineNanos;
        this.attribution = attribution;
    }

    /** A first-turn request for {@code prompt}'s messages, carrying its context metadata. */
    public static LlmRequest of(PromptResult prompt, LlmFeature feature) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        return new LlmRequest(prompt.getMessages(), feature, prompt, 1, false,
                Collections.<String>emptyList(), NO_DEADLINE, PostProcessingAttribution.NONE);
    }

    /** A first-turn request for {@code messages}, without prompt context metadata. */
    public static LlmRequest of(List<LlmMessage> messages, LlmFeature feature) {
        return new LlmRequest(messages == null ? Collections.<LlmMessage>emptyList() : messages,
                feature, null, 1, false, Collections.<String>emptyList(), NO_DEADLINE, PostProcessingAttribution.NONE);
    }

    /**
     * This request bounded by an absolute {@link System#nanoTime()} deadline;
     * a value of zero or below, or {@link #NO_DEADLINE}, means no bound.
     */
    public LlmRequest withDeadlineNanos(long deadlineNanos) {
        return new LlmRequest(messages, feature, context, repairAttempt, expansionAttempted,
                expandedClasses, deadlineNanos > 0 ? deadlineNanos : NO_DEADLINE, attribution);
    }

    /**
     * This request traced with the context metadata of {@code prompt}, or
     * without any when it is null. The messages stay as they are, so a later
     * turn of a conversation can name the prompt the conversation started from.
     */
    public LlmRequest withContext(PromptResult prompt) {
        return new LlmRequest(messages, feature, prompt, repairAttempt, expansionAttempted,
                expandedClasses, deadlineNanos, attribution);
    }

    /** This request numbered as conversation turn {@code repairAttempt} (1 = the initial request). */
    public LlmRequest withRepairAttempt(int repairAttempt) {
        return new LlmRequest(messages, feature, context, repairAttempt, expansionAttempted,
                expandedClasses, deadlineNanos, attribution);
    }

    /** This request recording whether cluster expansion ran before it and which classes it added. */
    public LlmRequest withExpansion(boolean attempted, List<String> classes) {
        return new LlmRequest(messages, feature, context, repairAttempt, attempted,
                classes == null ? Collections.<String>emptyList() : classes,
                deadlineNanos, attribution);
    }

    /**
     * This request attributed in the trace to post-processing of one test: its index in the suite and how its
     * minimization ended.
     */
    public LlmRequest withPostProcessingAttribution(String minimizationStatus, String minimizationStopCause,
                                                    int testIndex) {
        return new LlmRequest(messages, feature, context, repairAttempt, expansionAttempted,
                expandedClasses, deadlineNanos,
                new PostProcessingAttribution(minimizationStatus, minimizationStopCause, testIndex));
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    public LlmFeature getFeature() {
        return feature;
    }

    /** The prompt whose context metadata the trace records, or null when there is none. */
    public PromptResult getContext() {
        return context;
    }

    public int getRepairAttempt() {
        return repairAttempt;
    }

    public boolean isExpansionAttempted() {
        return expansionAttempted;
    }

    public List<String> getExpandedClasses() {
        return expandedClasses;
    }

    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    public PostProcessingAttribution getPostProcessingAttribution() {
        return attribution;
    }
}
