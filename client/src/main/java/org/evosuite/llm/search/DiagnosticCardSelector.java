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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies root-cause-aware deduplication and soft diversity quotas on top of
 * raw diagnostic-card scores.
 */
public class DiagnosticCardSelector {

    private static final List<ProblemCardFamily> SOFT_QUOTA_ORDER =
            java.util.Arrays.asList(
                    ProblemCardFamily.STRUCTURAL,
                    ProblemCardFamily.EXECUTION,
                    ProblemCardFamily.LOCAL);

    public SelectionResult select(List<ProblemCard> extractedCards, int maxCards) {
        return select(extractedCards, maxCards, null, false);
    }

    public SelectionResult select(List<ProblemCard> extractedCards,
                                  int maxCards,
                                  RepeatedInjectionMemory repeatedInjectionMemory,
                                  boolean suppressInFlightRepeats) {
        if (extractedCards == null || extractedCards.isEmpty() || maxCards <= 0) {
            return SelectionResult.empty();
        }

        List<RankedCard> rankedCards = rank(extractedCards, repeatedInjectionMemory, suppressInFlightRepeats);
        List<ProblemCard> sorted = new ArrayList<>(rankedCards.size());
        Map<ProblemCard, DiscardReason> preDiscardReasons = new HashMap<>();
        for (RankedCard ranked : rankedCards) {
            if (ranked.discardReason != null) {
                preDiscardReasons.put(ranked.card, ranked.discardReason);
            } else {
                sorted.add(ranked.card);
            }
        }
        if (sorted.isEmpty()) {
            List<DiscardedCard> discarded = new ArrayList<>(preDiscardReasons.size());
            for (Map.Entry<ProblemCard, DiscardReason> entry : preDiscardReasons.entrySet()) {
                discarded.add(new DiscardedCard(entry.getKey(), entry.getValue()));
            }
            return new SelectionResult(Collections.<ProblemCard>emptyList(), discarded);
        }

        List<ProblemCard> selected = new ArrayList<>();
        Set<ProblemCard> selectedSet = new LinkedHashSet<>();

        for (ProblemCardFamily family : SOFT_QUOTA_ORDER) {
            if (selected.size() >= maxCards) {
                break;
            }
            ProblemCard card = bestCompatibleCard(sorted, selectedSet, selected, family);
            if (card != null) {
                selected.add(card);
                selectedSet.add(card);
            }
        }

        for (ProblemCard card : sorted) {
            if (selected.size() >= maxCards) {
                break;
            }
            if (selectedSet.contains(card)
                    || overlapsWithSelected(card, selected)
                    || shouldSuppressLowSteerabilityBranchGap(card, sorted, selected)) {
                continue;
            }
            selected.add(card);
            selectedSet.add(card);
        }

        selected.sort(Comparator.comparingInt(sorted::indexOf));

        List<DiscardedCard> discarded = new ArrayList<>();
        for (Map.Entry<ProblemCard, DiscardReason> entry : preDiscardReasons.entrySet()) {
            discarded.add(new DiscardedCard(entry.getKey(), entry.getValue()));
        }
        for (ProblemCard card : sorted) {
            if (selectedSet.contains(card)) {
                continue;
            }
            discarded.add(new DiscardedCard(card, classifyDiscard(card, sorted, selected, maxCards)));
        }

        return new SelectionResult(selected, discarded);
    }

    private List<RankedCard> rank(List<ProblemCard> extractedCards,
                                  RepeatedInjectionMemory repeatedInjectionMemory,
                                  boolean suppressInFlightRepeats) {
        List<RankedCard> ranked = new ArrayList<>(extractedCards.size());
        for (ProblemCard card : extractedCards) {
            if (card == null) {
                continue;
            }
            double priority = card.getPriority();
            DiscardReason discardReason = null;
            if (repeatedInjectionMemory != null) {
                RepeatedInjectionMemory.SelectionAdjustment adjustment =
                        repeatedInjectionMemory.adjustPriority(card.toRepeatedInjectionTarget(),
                                priority, suppressInFlightRepeats);
                if (adjustment != null) {
                    priority = adjustment.getPriority();
                    discardReason = adjustment.getDiscardReason();
                }
            }
            ranked.add(new RankedCard(card, priority, discardReason));
        }
        ranked.sort(Comparator.comparingDouble(RankedCard::effectivePriority).reversed()
                .thenComparingInt(RankedCard::typeOrdinal)
                .thenComparing(RankedCard::tieBreakKey));
        return ranked;
    }

    private ProblemCard bestCompatibleCard(List<ProblemCard> candidates,
                                           Set<ProblemCard> selectedSet,
                                           List<ProblemCard> selected,
                                           ProblemCardFamily family) {
        for (ProblemCard card : candidates) {
            if (card == null || selectedSet.contains(card) || card.getFamily() != family) {
                continue;
            }
            if (shouldSuppressLowSteerabilityBranchGap(card, candidates, selected)) {
                continue;
            }
            if (!overlapsWithSelected(card, selected)) {
                return card;
            }
        }
        return null;
    }

    private DiscardReason classifyDiscard(ProblemCard candidate,
                                          List<ProblemCard> ranked,
                                          List<ProblemCard> selected,
                                          int maxCards) {
        if (shouldSuppressLowSteerabilityBranchGap(candidate, ranked, selected)) {
            return DiscardReason.LOW_STEERABILITY;
        }
        if (overlapsWithSelected(candidate, selected)) {
            return DiscardReason.ROOT_CAUSE_OVERLAP;
        }
        int higherPrioritySelectedOtherFamily = 0;
        for (ProblemCard card : selected) {
            if (card == null || card.getFamily() == candidate.getFamily()) {
                continue;
            }
            if (card.getPriority() <= candidate.getPriority()) {
                higherPrioritySelectedOtherFamily++;
            }
        }
        if (higherPrioritySelectedOtherFamily < maxCards && wasDisplacedByFamilyQuota(candidate, ranked, selected)) {
            return DiscardReason.FAMILY_DIVERSITY;
        }
        return DiscardReason.SCORE_CUTOFF;
    }

    private boolean wasDisplacedByFamilyQuota(ProblemCard candidate,
                                              List<ProblemCard> ranked,
                                              List<ProblemCard> selected) {
        int candidateIndex = ranked.indexOf(candidate);
        if (candidateIndex < 0 || candidateIndex >= selected.size()) {
            return false;
        }
        for (ProblemCard selectedCard : selected) {
            if (selectedCard == null || selectedCard.getFamily() == candidate.getFamily()) {
                continue;
            }
            int selectedIndex = ranked.indexOf(selectedCard);
            if (selectedIndex > candidateIndex) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSuppressLowSteerabilityBranchGap(ProblemCard candidate,
                                                           List<ProblemCard> ranked,
                                                           List<ProblemCard> selected) {
        if (!isLowSteerabilityBranchGap(candidate)) {
            return false;
        }
        if (selected != null) {
            for (ProblemCard card : selected) {
                if (card != null && card.getFamily() != ProblemCardFamily.LOCAL) {
                    return true;
                }
            }
        }
        if (ranked == null) {
            return false;
        }
        for (ProblemCard card : ranked) {
            if (card == null || card == candidate || card.getFamily() == ProblemCardFamily.LOCAL) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isLowSteerabilityBranchGap(ProblemCard card) {
        return card != null
                && card.getType() == ProblemCardType.BRANCH_POLARITY_GAP
                && card.getSelectionFingerprint() != null
                && card.getSelectionFingerprint().contains("|steer=low");
    }

    private boolean overlapsWithSelected(ProblemCard candidate, List<ProblemCard> selected) {
        if (candidate == null || selected == null || selected.isEmpty()) {
            return false;
        }
        for (ProblemCard selectedCard : selected) {
            if (selectedCard == null) {
                continue;
            }
            if (sameNonEmptyKey(candidate.getScopeKey(), selectedCard.getScopeKey())) {
                return true;
            }
            if (sameNonEmptyKey(candidate.getRootCauseKey(), selectedCard.getRootCauseKey())) {
                if (candidate.getFamily() == selectedCard.getFamily()) {
                    return true;
                }
                if (unreachedMethodSubsumedByStructural(candidate, selectedCard)
                        || unreachedMethodSubsumedByStructural(selectedCard, candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean unreachedMethodSubsumedByStructural(ProblemCard unreachedCandidate,
                                                        ProblemCard structuralCandidate) {
        if (unreachedCandidate == null || structuralCandidate == null) {
            return false;
        }
        if (unreachedCandidate.getType() != ProblemCardType.UNREACHED_METHOD
                || structuralCandidate.getFamily() != ProblemCardFamily.STRUCTURAL) {
            return false;
        }
        return true;
    }

    private boolean sameNonEmptyKey(String left, String right) {
        return left != null && !left.isEmpty() && left.equals(right);
    }

    public enum DiscardReason {
        ROOT_CAUSE_OVERLAP,
        FAMILY_DIVERSITY,
        RECENT_REPEAT,
        IN_FLIGHT_REPEAT,
        LOW_STEERABILITY,
        SCORE_CUTOFF
    }

    private static final class RankedCard {
        private final ProblemCard card;
        private final double effectivePriority;
        private final DiscardReason discardReason;

        private RankedCard(ProblemCard card, double effectivePriority, DiscardReason discardReason) {
            this.card = card;
            this.effectivePriority = effectivePriority;
            this.discardReason = discardReason;
        }

        private double effectivePriority() {
            return effectivePriority;
        }

        private int typeOrdinal() {
            return card == null || card.getType() == null
                    ? Integer.MAX_VALUE
                    : card.getType().ordinal();
        }

        private String tieBreakKey() {
            if (card == null) {
                return "";
            }
            String scope = card.getScopeKey() == null ? "" : card.getScopeKey();
            String root = card.getRootCauseKey() == null ? "" : card.getRootCauseKey();
            return scope + "\0" + root;
        }
    }

    public static final class DiscardedCard {
        private final ProblemCard card;
        private final DiscardReason reason;

        public DiscardedCard(ProblemCard card, DiscardReason reason) {
            this.card = card;
            this.reason = reason == null ? DiscardReason.SCORE_CUTOFF : reason;
        }

        public ProblemCard getCard() {
            return card;
        }

        public DiscardReason getReason() {
            return reason;
        }
    }

    public static final class SelectionResult {
        private final List<ProblemCard> selectedCards;
        private final List<DiscardedCard> discardedCards;

        public SelectionResult(List<ProblemCard> selectedCards, List<DiscardedCard> discardedCards) {
            this.selectedCards = selectedCards == null
                    ? Collections.<ProblemCard>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(selectedCards));
            this.discardedCards = discardedCards == null
                    ? Collections.<DiscardedCard>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(discardedCards));
        }

        public List<ProblemCard> getSelectedCards() {
            return selectedCards;
        }

        public List<DiscardedCard> getDiscardedCards() {
            return discardedCards;
        }

        public long countDiscardReason(DiscardReason reason) {
            if (reason == null || discardedCards.isEmpty()) {
                return 0L;
            }
            long total = 0L;
            for (DiscardedCard discarded : discardedCards) {
                if (discarded != null && reason == discarded.getReason()) {
                    total++;
                }
            }
            return total;
        }

        public String summarizeDiscardReasons() {
            if (discardedCards.isEmpty()) {
                return "{}";
            }
            EnumMap<DiscardReason, Integer> counts = new EnumMap<>(DiscardReason.class);
            for (DiscardedCard discarded : discardedCards) {
                if (discarded == null || discarded.getReason() == null) {
                    continue;
                }
                counts.put(discarded.getReason(), counts.getOrDefault(discarded.getReason(), 0) + 1);
            }
            return counts.toString();
        }

        private static SelectionResult empty() {
            return new SelectionResult(Collections.<ProblemCard>emptyList(),
                    Collections.<DiscardedCard>emptyList());
        }
    }
}
