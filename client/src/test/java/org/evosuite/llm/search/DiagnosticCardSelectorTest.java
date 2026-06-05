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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticCardSelectorTest {

    @Test
    void selectorPrefersFamilyDiversityBeforeSecondSameFamilyCard() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();

        ProblemCard unreached = card(ProblemCardType.UNREACHED_METHOD, 0.92,
                "com.example.Foo", "com.example.Foo.work");
        ProblemCard exception = card(ProblemCardType.EXCEPTION_BARRIER, 0.88,
                "com.example.Bar.fail", "com.example.Bar.fail");
        ProblemCard local = card(ProblemCardType.BRANCH_POLARITY_GAP, 0.35,
                "branch:42:true", "branch:42:true");
        ProblemCard structural = card(ProblemCardType.UNINSTANTIABLE_TYPE, 0.30,
                "com.example.Factory", "acquisition:com.example.Factory");

        DiagnosticCardSelector.SelectionResult result = selector.select(
                Arrays.asList(unreached, exception, local, structural), 3);

        List<ProblemCard> selected = result.getSelectedCards();
        assertEquals(3, selected.size());
        assertTrue(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.UNREACHED_METHOD));
        assertTrue(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.BRANCH_POLARITY_GAP));
        assertTrue(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.UNINSTANTIABLE_TYPE));
        assertFalse(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.EXCEPTION_BARRIER));
        assertEquals(1L, result.countDiscardReason(DiagnosticCardSelector.DiscardReason.FAMILY_DIVERSITY));
    }

    @Test
    void selectorDropsUnreachedMethodsCoveredBySelectedStructuralBarrier() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();

        ProblemCard structural = card(ProblemCardType.UNINSTANTIABLE_TYPE, 0.45,
                "com.example.Foo", "acquisition:com.example.Foo");
        ProblemCard unreachedOne = card(ProblemCardType.UNREACHED_METHOD, 0.95,
                "com.example.Foo", "com.example.Foo.alpha");
        ProblemCard unreachedTwo = card(ProblemCardType.UNREACHED_METHOD, 0.85,
                "com.example.Foo", "com.example.Foo.beta");
        ProblemCard exception = card(ProblemCardType.EXCEPTION_BARRIER, 0.30,
                "com.example.Bar.gamma", "com.example.Bar.gamma");
        ProblemCard local = card(ProblemCardType.CDG_BOTTLENECK, 0.25,
                "branch:99:false", "branch:99:false");

        DiagnosticCardSelector.SelectionResult result = selector.select(
                Arrays.asList(unreachedOne, unreachedTwo, structural, exception, local), 3);

        List<ProblemCard> selected = result.getSelectedCards();
        assertEquals(3, selected.size());
        assertTrue(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.UNINSTANTIABLE_TYPE));
        assertTrue(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.EXCEPTION_BARRIER));
        assertTrue(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.CDG_BOTTLENECK));
        assertFalse(selected.stream().anyMatch(c -> c.getType() == ProblemCardType.UNREACHED_METHOD));
        assertEquals(2L, result.countDiscardReason(DiagnosticCardSelector.DiscardReason.ROOT_CAUSE_OVERLAP));
    }

    @Test
    void selectorDropsUnreachedMethodCoveredByIndirectInvocationBarrier() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();

        ProblemCard indirect = card(ProblemCardType.INDIRECT_REACHABILITY_BARRIER, 0.84,
                "com.example.Target", "indirect-invocation:com.example.Target");
        ProblemCard unreached = card(ProblemCardType.UNREACHED_METHOD, 0.80,
                "com.example.Target", "com.example.Target.work");
        ProblemCard local = card(ProblemCardType.STATE_DIVERSIFICATION_GAP, 0.40,
                "com.example.Target.work", "diversification:com.example.Target.work");

        DiagnosticCardSelector.SelectionResult result = selector.select(
                Arrays.asList(unreached, indirect, local), 2);

        assertEquals(2, result.getSelectedCards().size());
        assertTrue(result.getSelectedCards().stream()
                .anyMatch(card -> card.getType() == ProblemCardType.INDIRECT_REACHABILITY_BARRIER));
        assertTrue(result.getSelectedCards().stream()
                .anyMatch(card -> card.getType() == ProblemCardType.STATE_DIVERSIFICATION_GAP));
        assertFalse(result.getSelectedCards().stream()
                .anyMatch(card -> card.getType() == ProblemCardType.UNREACHED_METHOD));
        assertEquals(1L, result.countDiscardReason(DiagnosticCardSelector.DiscardReason.ROOT_CAUSE_OVERLAP));
    }

    @Test
    void selectorSuppressesRecentExactRepeatUntilEvidenceChanges() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();

        ProblemCard original = card(ProblemCardType.EXCEPTION_BARRIER, 0.90,
                "com.example.Foo.work", "com.example.Foo.work", "attempts=2");
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                memory.registerAttempt(Collections.singletonList(original.toRepeatedInjectionTarget()), false);
        memory.recordAttemptOutcome(registration.getAttemptId(), 0);

        DiagnosticCardSelector.SelectionResult repeated = selector.select(
                Collections.singletonList(original), 1, memory, false);
        assertTrue(repeated.getSelectedCards().isEmpty());
        assertEquals(1L, repeated.countDiscardReason(DiagnosticCardSelector.DiscardReason.RECENT_REPEAT));

        ProblemCard changed = card(ProblemCardType.EXCEPTION_BARRIER, 0.90,
                "com.example.Foo.work", "com.example.Foo.work", "attempts=4");
        DiagnosticCardSelector.SelectionResult retried = selector.select(
                Collections.singletonList(changed), 1, memory, false);
        assertEquals(1, retried.getSelectedCards().size());
    }

    @Test
    void selectorDropsStructuralBarrierCoveredByAlreadySelectedUnreachedMethod() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();

        ProblemCard structuralOther = card(ProblemCardType.UNINSTANTIABLE_TYPE, 0.95,
                "com.example.A", "acquisition:com.example.A");
        ProblemCard unreached = card(ProblemCardType.UNREACHED_METHOD, 0.90,
                "com.example.B", "com.example.B.work");
        ProblemCard structuralOverlap = card(ProblemCardType.STATE_SETUP_BARRIER, 0.70,
                "com.example.B", "setup:com.example.B");
        ProblemCard localPrimary = card(ProblemCardType.BRANCH_POLARITY_GAP, 0.60,
                "branch:7:true", "branch:7:true");
        ProblemCard localBackup = card(ProblemCardType.CDG_BOTTLENECK, 0.50,
                "branch:9:false", "branch:9:false");

        DiagnosticCardSelector.SelectionResult result = selector.select(
                Arrays.asList(structuralOther, unreached, structuralOverlap, localPrimary, localBackup), 4);

        List<ProblemCard> selected = result.getSelectedCards();
        assertEquals(4, selected.size());
        assertTrue(selected.stream().anyMatch(c -> c == structuralOther));
        assertTrue(selected.stream().anyMatch(c -> c == unreached));
        assertTrue(selected.stream().anyMatch(c -> c == localPrimary));
        assertTrue(selected.stream().anyMatch(c -> c == localBackup));
        assertFalse(selected.stream().anyMatch(c -> c == structuralOverlap),
                "Symmetric dedup must drop a STRUCTURAL card sharing rootCauseKey with a selected UNREACHED_METHOD");
        assertEquals(1L, result.countDiscardReason(DiagnosticCardSelector.DiscardReason.ROOT_CAUSE_OVERLAP));
    }

    @Test
    void selectorRanksEqualPriorityCardsDeterministicallyByTypeThenScope() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();

        ProblemCard scopeA = card(ProblemCardType.BRANCH_POLARITY_GAP, 0.30,
                "branch:a:true", "branch:a:true");
        ProblemCard scopeB = card(ProblemCardType.BRANCH_POLARITY_GAP, 0.30,
                "branch:b:true", "branch:b:true");
        ProblemCard scopeC = card(ProblemCardType.BRANCH_POLARITY_GAP, 0.30,
                "branch:c:true", "branch:c:true");

        DiagnosticCardSelector.SelectionResult first = selector.select(
                Arrays.asList(scopeC, scopeA, scopeB), 3);
        DiagnosticCardSelector.SelectionResult shuffled = selector.select(
                Arrays.asList(scopeB, scopeC, scopeA), 3);

        List<ProblemCard> firstSelected = first.getSelectedCards();
        List<ProblemCard> shuffledSelected = shuffled.getSelectedCards();
        assertEquals(3, firstSelected.size());
        assertEquals(3, shuffledSelected.size());
        // Same priority and same type ordinal → tie-break by (scopeKey, rootCauseKey)
        // lexicographically; the order must not depend on input ordering.
        assertEquals(scopeA, firstSelected.get(0));
        assertEquals(scopeB, firstSelected.get(1));
        assertEquals(scopeC, firstSelected.get(2));
        assertEquals(firstSelected, shuffledSelected);
    }

    @Test
    void selectorSuppressesLowSteerabilityBranchGapWhenBlockerCardsExist() {
        DiagnosticCardSelector selector = new DiagnosticCardSelector();

        ProblemCard structural = card(ProblemCardType.UNINSTANTIABLE_TYPE, 0.52,
                "com.example.Target", "acquisition:com.example.Target");
        ProblemCard execution = card(ProblemCardType.EXCEPTION_BARRIER, 0.44,
                "com.example.Target.work", "com.example.Target.work");
        ProblemCard lowSteerBranch = card(ProblemCardType.BRANCH_POLARITY_GAP, 0.10,
                "branch:77:true", "branch:77:true", "branch:77:true|steer=low");

        DiagnosticCardSelector.SelectionResult result = selector.select(
                Arrays.asList(structural, execution, lowSteerBranch), 3);

        assertEquals(2, result.getSelectedCards().size());
        assertFalse(result.getSelectedCards().stream()
                        .anyMatch(card -> card.getType() == ProblemCardType.BRANCH_POLARITY_GAP),
                "Low-steerability branch-gap cards should not consume quota when stronger blockers exist");
        assertEquals(1L, result.countDiscardReason(DiagnosticCardSelector.DiscardReason.LOW_STEERABILITY));
    }

    private static ProblemCard card(ProblemCardType type, double priority, String rootCauseKey, String scopeKey) {
        return card(type, priority, rootCauseKey, scopeKey, "fingerprint");
    }

    private static ProblemCard card(ProblemCardType type,
                                    double priority,
                                    String rootCauseKey,
                                    String scopeKey,
                                    String fingerprint) {
        return ProblemCard.builder(type)
                .title(type.name())
                .evidence(Collections.singletonList("evidence"))
                .relatedGoals(Collections.emptyList())
                .impact(priority)
                .blockage(1.0)
                .confidence(1.0)
                .rootCauseKey(rootCauseKey)
                .scopeKey(scopeKey)
                .selectionFingerprint(fingerprint)
                .build();
    }
}
