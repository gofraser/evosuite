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
package org.evosuite.llm.search;

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared per-run memory for repeated prompt-target selection.
 */
public class RepeatedInjectionMemory {

    private final Map<String, TargetState> targetStates = new LinkedHashMap<>();
    private final Map<String, PromptAttempt> attempts = new LinkedHashMap<>();
    private long selectionOrdinal = 0L;
    private long attemptSequence = 0L;
    private long suppressedRecentCount = 0L;
    private long suppressedInFlightCount = 0L;
    private long retriedChangedCount = 0L;
    private static final long DEFAULT_RECENT_COOLDOWN_CAP = 3L;
    private static final long LOW_STEERABILITY_COOLDOWN_CAP = 8L;

    public synchronized SelectionAdjustment adjustPriority(RepeatedInjectionTarget target,
                                                           double basePriority,
                                                           boolean suppressInFlight) {
        if (target == null || target.getKey().isEmpty()) {
            return SelectionAdjustment.keep(basePriority);
        }
        TargetState state = targetStates.get(target.getKey());
        if (state == null) {
            return SelectionAdjustment.keep(basePriority * 1.15);
        }
        if (suppressInFlight && state.inFlightAttempts > 0) {
            suppressedInFlightCount++;
            ClientServices.track(RuntimeVariable.LLM_Repeated_Prompt_Targets_Suppressed_InFlight,
                    suppressedInFlightCount);
            return SelectionAdjustment.suppress(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT);
        }
        boolean sameFingerprint = target.getFingerprint().equals(state.lastFingerprint);
        long age = Math.max(0L, selectionOrdinal - state.lastSelectedOrdinal);
        boolean lowSteerabilityBranchTarget = isLowSteerabilityBranchTarget(target);
        long cooldownWindow = sameFingerprint
                ? Math.min(DEFAULT_RECENT_COOLDOWN_CAP, 1L + state.consecutiveNoGainAttempts)
                : 0L;
        if (sameFingerprint && lowSteerabilityBranchTarget) {
            long extendedCooldown = Math.min(LOW_STEERABILITY_COOLDOWN_CAP,
                    2L + (2L * state.consecutiveNoGainAttempts));
            cooldownWindow = Math.max(cooldownWindow, extendedCooldown);
        }
        if (sameFingerprint && age < cooldownWindow) {
            suppressedRecentCount++;
            ClientServices.track(RuntimeVariable.LLM_Repeated_Prompt_Targets_Suppressed_Recent,
                    suppressedRecentCount);
            return SelectionAdjustment.suppress(DiagnosticCardSelector.DiscardReason.RECENT_REPEAT);
        }

        double adjusted = basePriority;
        if (!sameFingerprint && !state.lastFingerprint.isEmpty()) {
            adjusted *= 1.10;
            retriedChangedCount++;
            ClientServices.track(RuntimeVariable.LLM_Repeated_Prompt_Targets_Retried_Changed,
                    retriedChangedCount);
        }
        if (state.consecutiveNoGainAttempts > 0) {
            adjusted /= (1.0 + (0.35 * state.consecutiveNoGainAttempts));
        }
        if (lowSteerabilityBranchTarget) {
            adjusted *= 0.45;
        }
        return SelectionAdjustment.keep(adjusted);
    }

    private boolean isLowSteerabilityBranchTarget(RepeatedInjectionTarget target) {
        if (target == null || target.getDiagnosticCardType() != ProblemCardType.BRANCH_POLARITY_GAP) {
            return false;
        }
        String fingerprint = target.getFingerprint();
        return fingerprint != null && fingerprint.contains("|steer=low");
    }

    public synchronized PromptAttemptRegistration registerAttempt(List<RepeatedInjectionTarget> targets,
                                                                  boolean asyncInFlight) {
        List<RepeatedInjectionTarget> safeTargets = sanitizeTargets(targets);
        if (safeTargets.isEmpty()) {
            return PromptAttemptRegistration.empty();
        }
        selectionOrdinal++;
        String attemptId = "prompt-" + (++attemptSequence);
        attempts.put(attemptId, new PromptAttempt(safeTargets, asyncInFlight));
        for (RepeatedInjectionTarget target : safeTargets) {
            TargetState state = targetStates.computeIfAbsent(target.getKey(), ignored -> new TargetState());
            state.lastSelectedOrdinal = selectionOrdinal;
            state.lastFingerprint = target.getFingerprint();
            if (asyncInFlight) {
                state.inFlightAttempts++;
            }
        }
        return new PromptAttemptRegistration(attemptId, safeTargets);
    }

    public synchronized void recordAttemptOutcome(String attemptId, int gainedGoals) {
        PromptAttempt attempt = attempts.remove(attemptId == null ? "" : attemptId.trim());
        if (attempt == null) {
            return;
        }
        for (RepeatedInjectionTarget target : attempt.targets) {
            TargetState state = targetStates.get(target.getKey());
            if (state == null) {
                continue;
            }
            if (attempt.asyncInFlight && state.inFlightAttempts > 0) {
                state.inFlightAttempts--;
            }
            if (gainedGoals > 0) {
                state.consecutiveNoGainAttempts = 0L;
            } else {
                state.consecutiveNoGainAttempts++;
            }
        }
    }

    public synchronized void clearAttempt(String attemptId) {
        PromptAttempt attempt = attempts.remove(attemptId == null ? "" : attemptId.trim());
        if (attempt == null) {
            return;
        }
        if (!attempt.asyncInFlight) {
            return;
        }
        for (RepeatedInjectionTarget target : attempt.targets) {
            TargetState state = targetStates.get(target.getKey());
            if (state != null && state.inFlightAttempts > 0) {
                state.inFlightAttempts--;
            }
        }
    }

    /**
     * Releases an attempt without touching {@code consecutiveNoGainAttempts}.
     * Use this when the prompt never reached an "outcome can be judged" state
     * (e.g., sync call was cancelled or hit the wall-clock budget) so the
     * suppression cooldown doesn't grow on what is effectively a non-delivery.
     */
    public synchronized void releaseUndeliveredAttempt(String attemptId) {
        PromptAttempt attempt = attempts.remove(attemptId == null ? "" : attemptId.trim());
        if (attempt == null) {
            return;
        }
        for (RepeatedInjectionTarget target : attempt.targets) {
            TargetState state = targetStates.get(target.getKey());
            if (state == null) {
                continue;
            }
            if (attempt.asyncInFlight && state.inFlightAttempts > 0) {
                state.inFlightAttempts--;
            }
        }
    }

    public synchronized long getSuppressedRecentCount() {
        return suppressedRecentCount;
    }

    public synchronized long getSuppressedInFlightCount() {
        return suppressedInFlightCount;
    }

    public synchronized long getRetriedChangedCount() {
        return retriedChangedCount;
    }

    private static List<RepeatedInjectionTarget> sanitizeTargets(List<RepeatedInjectionTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Collections.emptyList();
        }
        List<RepeatedInjectionTarget> safe = new ArrayList<>();
        for (RepeatedInjectionTarget target : targets) {
            if (target != null && !target.getKey().isEmpty()) {
                safe.add(target);
            }
        }
        return safe;
    }

    private static final class PromptAttempt {
        private final List<RepeatedInjectionTarget> targets;
        private final boolean asyncInFlight;

        private PromptAttempt(List<RepeatedInjectionTarget> targets, boolean asyncInFlight) {
            this.targets = targets;
            this.asyncInFlight = asyncInFlight;
        }
    }

    private static final class TargetState {
        private long lastSelectedOrdinal = -1L;
        private long consecutiveNoGainAttempts = 0L;
        private int inFlightAttempts = 0;
        private String lastFingerprint = "";
    }

    public static final class SelectionAdjustment {
        private final double priority;
        private final DiagnosticCardSelector.DiscardReason discardReason;

        private SelectionAdjustment(double priority, DiagnosticCardSelector.DiscardReason discardReason) {
            this.priority = priority;
            this.discardReason = discardReason;
        }

        public static SelectionAdjustment keep(double priority) {
            return new SelectionAdjustment(priority, null);
        }

        public static SelectionAdjustment suppress(DiagnosticCardSelector.DiscardReason discardReason) {
            return new SelectionAdjustment(Double.NEGATIVE_INFINITY, discardReason);
        }

        public double getPriority() {
            return priority;
        }

        public DiagnosticCardSelector.DiscardReason getDiscardReason() {
            return discardReason;
        }

        public boolean isSuppressed() {
            return discardReason != null;
        }
    }

    public static final class PromptAttemptRegistration {
        private static final PromptAttemptRegistration EMPTY =
                new PromptAttemptRegistration("", Collections.<RepeatedInjectionTarget>emptyList());

        private final String attemptId;
        private final List<RepeatedInjectionTarget> targets;

        private PromptAttemptRegistration(String attemptId, List<RepeatedInjectionTarget> targets) {
            this.attemptId = attemptId == null ? "" : attemptId;
            this.targets = targets == null
                    ? Collections.<RepeatedInjectionTarget>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(targets));
        }

        public static PromptAttemptRegistration empty() {
            return EMPTY;
        }

        public String getAttemptId() {
            return attemptId;
        }

        public boolean isEmpty() {
            return attemptId.isEmpty() || targets.isEmpty();
        }
    }
}
