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
package org.evosuite.llm.search.operator;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.BranchOperandHint;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.GoalId;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row of {@link OperatorAttemptLog}: the lifecycle of a single operator
 * target, from the moment it is prompted through evaluation, admission, and
 * a later survival scan. Holds the CSV rendering and the statement-multiset
 * diff between the parent test and any offspring; {@link OperatorAttemptLog}
 * owns the registry, singleton, and aggregate counters.
 */
final class OperatorAttemptRecord {

    static final String CSV_HEADER =
            "generation,attempt_id,mode,kind,batch_size,parent_hash,partner_hash,goal_id,card_type,"
                    + "hint_present,distance_before,distance_after,target_hit,parse_status,repair_turns,"
                    + "statements_kept,statements_added,statements_removed,input_tokens,cached_input_tokens,"
                    + "output_tokens,latency_ms,admitted,admitted_generation,survived_plus1,survived_plus5";

    private final String attemptId;
    private final int generation;
    private final String mode;
    private final int batchSize;
    private final OperatorTarget.Kind kind;
    private final int parentHash;
    private final Integer partnerHash;
    private final String goalId;
    private final String cardType;
    private final boolean hintPresent;
    private final double distanceBefore;
    private final List<String> parentStatementCodes;

    private String parseStatus = "";
    private Integer repairTurns;
    private Integer statementsKept;
    private Integer statementsAdded;
    private Integer statementsRemoved;
    private Integer inputTokens;
    private Integer cachedInputTokens;
    private Integer outputTokens;
    private Long latencyMs;

    private Double distanceAfter;
    private Boolean targetHit;

    private boolean admitted;
    private Integer admittedGeneration;
    private TestChromosome admittedOffspring;
    private Boolean survivedPlus1;
    private Boolean survivedPlus5;

    OperatorAttemptRecord(String attemptId, int generation, OperatorTarget target, String mode, int batchSize) {
        this.attemptId = attemptId;
        this.generation = generation;
        this.mode = mode;
        this.batchSize = batchSize;
        this.kind = target.getKind();
        TestChromosome parent = target.getParent();
        this.parentHash = System.identityHashCode(parent);
        this.partnerHash = target.getPartner().map(System::identityHashCode).orElse(null);
        this.goalId = GoalId.of(target.getGoal());
        this.cardType = target.getCard().map(ProblemCard::getType).map(Enum::name).orElse("");
        this.hintPresent = hintPresent(target, parent);
        this.distanceBefore = target.getDistance();
        this.parentStatementCodes = statementCodes(parent == null ? null : parent.getTestCase());
    }

    private static boolean hintPresent(OperatorTarget target, TestChromosome parent) {
        if (!Properties.LLM_BRANCH_OPERAND_HINTS) {
            return false;
        }
        try {
            String hint = BranchOperandHint.forGoal(target.getGoal(), parent);
            return hint != null && !hint.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    String getAttemptId() {
        return attemptId;
    }

    OperatorTarget.Kind getKind() {
        return kind;
    }

    void recordResponse(TargetedResponse.Status status, boolean parseSucceeded, boolean targetMatched,
                        int repairTurns, TargetedResponse.CallUsage usage, TestCase offspring) {
        this.repairTurns = repairTurns;
        TargetedResponse.CallUsage safeUsage = usage == null ? TargetedResponse.CallUsage.NONE : usage;
        this.inputTokens = safeUsage.getInputTokens();
        this.cachedInputTokens = safeUsage.getCachedInputTokens();
        this.outputTokens = safeUsage.getOutputTokens();
        this.latencyMs = safeUsage.getLatencyMs();
        if (status == TargetedResponse.Status.NO_BUDGET) {
            this.parseStatus = "NO_BUDGET";
        } else if (status == TargetedResponse.Status.FAILED) {
            this.parseStatus = "FAILED";
        } else if (!parseSucceeded) {
            this.parseStatus = "PARSE_FAILED";
        } else if (offspring != null && targetMatched) {
            this.parseStatus = "PARSED";
        } else {
            this.parseStatus = "UNMATCHED";
        }
        if (offspring != null) {
            int[] diff = diffStatementCodes(parentStatementCodes, statementCodes(offspring));
            this.statementsKept = diff[0];
            this.statementsAdded = diff[1];
            this.statementsRemoved = diff[2];
        }
    }

    /**
     * Marks this attempt as released without a response, unless a response was already recorded:
     * a parsed candidate stays open in the executor until the GA reports its outcome, and {@code
     * shutdown()} marks every still-open attempt undelivered — including ones whose response
     * already arrived and parsed successfully.
     */
    void recordUndelivered() {
        if (parseStatus.isEmpty()) {
            this.parseStatus = "UNDELIVERED";
        }
    }

    void recordEvaluated(double distanceAfter, boolean targetHit) {
        this.distanceAfter = distanceAfter;
        this.targetHit = targetHit;
    }

    void recordAdmitted(TestChromosome offspring, int generation) {
        this.admitted = true;
        this.admittedGeneration = generation;
        this.admittedOffspring = offspring;
    }

    /**
     * Resolves the survival horizons reached by the post-selection scan of {@code generation};
     * identity-based. {@code survived_plus<n>} means the offspring is in the population that
     * breeds generation {@code admitted + n}, i.e. it outlived the selection of generation
     * {@code admitted + n - 1}; {@code survived_plus1} therefore resolves at the admission
     * generation's own scan, the same moment {@code LLM_Injected_Candidates_Survived} counts.
     */
    void applySurvivorScan(Set<TestChromosome> presentByIdentity, int generation) {
        if (admittedOffspring == null || admittedGeneration == null) {
            return;
        }
        if (generation == admittedGeneration) {
            survivedPlus1 = presentByIdentity.contains(admittedOffspring);
        }
        if (generation == admittedGeneration + 4) {
            survivedPlus5 = presentByIdentity.contains(admittedOffspring);
        }
    }

    boolean isParsed() {
        return "PARSED".equals(parseStatus);
    }

    boolean isAdmitted() {
        return admitted;
    }

    boolean isSurvivedPlus1() {
        return Boolean.TRUE.equals(survivedPlus1);
    }

    boolean isTargetHit() {
        return Boolean.TRUE.equals(targetHit);
    }

    boolean isTargetImproved() {
        return distanceAfter != null && distanceAfter < distanceBefore;
    }

    String toCsvRow() {
        return generation + "," + formatCsvField(attemptId) + "," + mode + "," + kind + "," + batchSize + ","
                + parentHash + "," + orEmpty(partnerHash) + "," + formatCsvField(goalId) + ","
                + formatCsvField(cardType) + "," + bool(hintPresent) + "," + formatDouble(distanceBefore) + ","
                + (distanceAfter == null ? "" : formatDouble(distanceAfter)) + ","
                + (targetHit == null ? "" : bool(targetHit)) + "," + parseStatus + ","
                + orEmpty(repairTurns) + "," + orEmpty(statementsKept) + "," + orEmpty(statementsAdded) + ","
                + orEmpty(statementsRemoved) + "," + orEmpty(inputTokens) + "," + orEmpty(cachedInputTokens) + ","
                + orEmpty(outputTokens) + "," + orEmpty(latencyMs) + "," + bool(admitted) + ","
                + orEmpty(admittedGeneration) + "," + (survivedPlus1 == null ? "" : bool(survivedPlus1)) + ","
                + (survivedPlus5 == null ? "" : bool(survivedPlus5));
    }

    private static String bool(boolean b) {
        return b ? "1" : "0";
    }

    private static String orEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String formatDouble(double v) {
        return Double.isNaN(v) ? "" : String.valueOf(v);
    }

    /** Same escaping as {@code DisruptionEvent.formatCsvField}. */
    private static String formatCsvField(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static List<String> statementCodes(TestCase testCase) {
        List<String> codes = new ArrayList<>();
        if (testCase != null) {
            for (Statement statement : testCase) {
                codes.add(statement.getCode());
            }
        }
        return codes;
    }

    /** Multiset diff of {@code before} vs. {@code after}: {kept, added, removed}. Package-visible (testable). */
    static int[] diffStatementCodes(List<String> before, List<String> after) {
        Map<String, Integer> counts = new HashMap<>();
        for (String code : before) {
            counts.merge(code, 1, Integer::sum);
        }
        int kept = 0;
        for (String code : after) {
            Integer remaining = counts.get(code);
            if (remaining != null && remaining > 0) {
                kept++;
                counts.put(code, remaining - 1);
            }
        }
        return new int[]{kept, after.size() - kept, before.size() - kept};
    }
}
