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
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row of {@link OperatorAttemptLog}: the lifecycle of a single operator
 * target, from the moment it is prompted through evaluation, admission, and
 * a later survival scan. Holds the CSV rendering and the statement-multiset
 * diff between the parent test and any offspring; {@link OperatorAttemptLog}
 * owns the registry, singleton, retirement, and aggregate counters. Every
 * event method that can only usefully fire once per record (a response, an
 * admission, a survivor scan, a hit or improvement) reports back whether it
 * actually changed anything, so the log can maintain its counters in O(1)
 * instead of re-scanning rows.
 */
final class OperatorAttemptRecord {

    private static final Logger logger = LoggerFactory.getLogger(OperatorAttemptRecord.class);

    static final String CSV_HEADER =
            "generation,attempt_id,mode,kind,batch_size,parent_hash,partner_hash,goal_id,card_type,"
                    + "hint_present,distance_before,distance_after,target_hit,parse_status,repair_turns,"
                    + "statements_kept,statements_added,statements_removed,input_tokens,cached_input_tokens,"
                    + "output_tokens,latency_ms,admitted,admitted_generation,survived_plus1,survived_plus5";

    /** {@link #recordEvaluated} return flags: a newly-counted hit / distance improvement. */
    static final int EVAL_NEW_HIT = 1;
    static final int EVAL_NEW_IMPROVED = 2;

    /** {@link #applySurvivorScan} return flags: what this scan resolved. */
    static final int SCAN_PLUS1_SURVIVED = 1;
    static final int SCAN_PLUS5_RESOLVED_EARLY = 2;
    static final int SCAN_FINAL = 4;

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

    /** Released once the attempt closes (diff computed or not needed) — never used again after that. */
    private List<String> parentStatementCodes;

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
    private boolean countedHit;
    private boolean countedImproved;

    private boolean admitted;
    private Integer admittedGeneration;
    private TestChromosome admittedOffspring;
    private Boolean survivedPlus1;
    private Boolean survivedPlus5;

    /** Consecutive {@link OperatorAttemptLog#recordSurvivors} scans found PARSED-but-unadmitted; retired at 2. */
    private int unadmittedParsedScanCount;

    OperatorAttemptRecord(String attemptId, int generation, OperatorTarget target, String mode, int batchSize) {
        this.attemptId = attemptId;
        this.generation = generation;
        this.mode = mode;
        this.batchSize = batchSize;
        this.kind = target.getKind();
        TestChromosome parent = target.getParent();
        this.parentHash = System.identityHashCode(parent);
        this.partnerHash = target.getPartner().map(System::identityHashCode).orElse(null);
        this.goalId = goalId(target.getGoal());
        this.cardType = cardType(target);
        this.hintPresent = hintPresent(target, parent);
        this.distanceBefore = target.getDistance();
        this.parentStatementCodes = statementCodes(parent == null ? null : parent.getTestCase());
    }

    /** Best-effort: an id is only ever used to group/report rows, never worth losing the row for. */
    private static String goalId(TestFitnessFunction goal) {
        try {
            return GoalId.of(goal);
        } catch (RuntimeException e) {
            logger.debug("GoalId.of failed for operator attempt log: {}", e.getMessage());
            return "";
        }
    }

    /** Best-effort, same reasoning as {@link #goalId}. */
    private static String cardType(OperatorTarget target) {
        try {
            return target.getCard().map(ProblemCard::getType).map(Enum::name).orElse("");
        } catch (RuntimeException e) {
            logger.debug("card type extraction failed for operator attempt log: {}", e.getMessage());
            return "";
        }
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

    /**
     * Records what the call produced, unless a status was already recorded (first status wins —
     * mirrors {@link #recordDropped}). Returns whether this call actually recorded it, so {@link
     * OperatorAttemptLog} counts the outcome and retires a terminal row only once.
     */
    boolean recordResponse(TargetedResponse.Status status, boolean parseSucceeded, boolean targetMatched,
                        int repairTurns, TargetedResponse.CallUsage usage, TestCase offspring) {
        if (!parseStatus.isEmpty()) {
            return false;
        }
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
            List<String> before = parentStatementCodes == null ? Collections.emptyList() : parentStatementCodes;
            int[] diff = diffStatementCodes(before, statementCodes(offspring));
            this.statementsKept = diff[0];
            this.statementsAdded = diff[1];
            this.statementsRemoved = diff[2];
        }
        this.parentStatementCodes = null;
        return true;
    }

    /**
     * Marks this attempt released without a response, unless a response was already recorded: a
     * parsed candidate stays open until the GA reports its outcome, and {@code shutdown()} marks
     * every still-open attempt undelivered — including already-parsed ones. Returns whether this
     * call actually recorded it.
     */
    boolean recordUndelivered() {
        if (!parseStatus.isEmpty()) {
            return false;
        }
        this.parseStatus = "UNDELIVERED";
        this.parentStatementCodes = null;
        return true;
    }

    /**
     * Marks this attempt as DROPPED, unless a status was already recorded: the call was
     * delivered — its tokens and latency were spent — but the run ended (e.g. {@code shutdown()})
     * before the response could be parsed or matched to a target. Stores the usage exactly like
     * {@link #recordResponse} does, so token accounting still reflects the spent call. Returns
     * whether this call actually recorded it.
     */
    boolean recordDropped(TargetedResponse.CallUsage usage) {
        if (!parseStatus.isEmpty()) {
            return false;
        }
        TargetedResponse.CallUsage safeUsage = usage == null ? TargetedResponse.CallUsage.NONE : usage;
        this.inputTokens = safeUsage.getInputTokens();
        this.cachedInputTokens = safeUsage.getCachedInputTokens();
        this.outputTokens = safeUsage.getOutputTokens();
        this.latencyMs = safeUsage.getLatencyMs();
        this.parseStatus = "DROPPED";
        this.parentStatementCodes = null;
        return true;
    }

    /**
     * Records the post-evaluation distance/hit and returns which of {@link #EVAL_NEW_HIT} /
     * {@link #EVAL_NEW_IMPROVED} are newly true this call, so {@link OperatorAttemptLog} counts
     * each at most once per record even if evaluation is (unexpectedly) reported twice.
     */
    int recordEvaluated(double distanceAfter, boolean targetHit) {
        this.distanceAfter = distanceAfter;
        this.targetHit = targetHit;
        int flags = 0;
        if (targetHit && !countedHit) {
            countedHit = true;
            flags |= EVAL_NEW_HIT;
        }
        if (isTargetImproved() && !countedImproved) {
            countedImproved = true;
            flags |= EVAL_NEW_IMPROVED;
        }
        return flags;
    }

    /** Returns whether this attempt was newly admitted (idempotent: a record admits at most once). */
    boolean recordAdmitted(TestChromosome offspring, int generation) {
        if (admitted) {
            return false;
        }
        this.admitted = true;
        this.admittedGeneration = generation;
        this.admittedOffspring = offspring;
        return true;
    }

    /**
     * Resolves the survival horizons reached by the post-selection scan of {@code generation};
     * identity-based. {@code survived_plus<n>} means the offspring is in the population that
     * breeds generation {@code admitted + n}, i.e. it outlived the selection of generation
     * {@code admitted + n - 1}; {@code survived_plus1} therefore resolves at the admission
     * generation's own scan, the same moment {@code LLM_Injected_Candidates_Survived} counts.
     *
     * <p>Returns a combination of {@link #SCAN_PLUS1_SURVIVED} (set when {@code survived_plus1}
     * resolves true this call), {@link #SCAN_PLUS5_RESOLVED_EARLY} (the offspring was already gone
     * at plus1 — an identity-based population membership cannot reappear once lost, so plus5 is
     * known to be false without waiting for that generation's scan; the caller must then drop this
     * record from its plus-5 pending scan), and {@link #SCAN_FINAL} (both survival horizons are
     * now resolved, i.e. {@code survived_plus5} is set — the caller may retire this record).
     */
    int applySurvivorScan(Set<TestChromosome> presentByIdentity, int generation) {
        if (admittedOffspring == null || admittedGeneration == null) {
            return 0;
        }
        int flags = 0;
        if (generation == admittedGeneration) {
            boolean present = presentByIdentity.contains(admittedOffspring);
            survivedPlus1 = present;
            if (present) {
                flags |= SCAN_PLUS1_SURVIVED;
            } else {
                survivedPlus5 = false;
                admittedOffspring = null;
                flags |= SCAN_PLUS5_RESOLVED_EARLY | SCAN_FINAL;
            }
        }
        if (generation == admittedGeneration + 4) {
            survivedPlus5 = presentByIdentity.contains(admittedOffspring);
            admittedOffspring = null;
            flags |= SCAN_FINAL;
        }
        return flags;
    }

    /** Whether the admitted offspring reference is still held; package-visible, for tests only. */
    boolean hasAdmittedOffspringReference() {
        return admittedOffspring != null;
    }

    /** Whether {@link #parentStatementCodes} is still held; package-visible, for tests only. */
    boolean hasParentStatementCodesForTesting() {
        return parentStatementCodes != null;
    }

    /**
     * Bumps and returns the count of consecutive {@link OperatorAttemptLog#recordSurvivors} scans
     * that have found this record {@code PARSED} but unadmitted (retirement rule (c): the log
     * retires it once this reaches 2). The caller is expected to only call this while that
     * condition holds.
     */
    int bumpUnadmittedParsedScanCount() {
        return ++unadmittedParsedScanCount;
    }

    boolean isParsed() {
        return "PARSED".equals(parseStatus);
    }

    /** Whether this record's outcome is a terminal, non-admittable one (retirement rule (a)). */
    boolean isCaseATerminal() {
        switch (parseStatus) {
            case "NO_BUDGET":
            case "FAILED":
            case "PARSE_FAILED":
            case "UNMATCHED":
            case "UNDELIVERED":
            case "DROPPED":
                return true;
            default:
                return false;
        }
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

    /**
     * Best-effort: a single statement whose {@code getCode()} throws (e.g. an unresolved generic)
     * must not lose the whole diff, so a failure here yields an empty list rather than escaping.
     */
    private static List<String> statementCodes(TestCase testCase) {
        List<String> codes = new ArrayList<>();
        if (testCase != null) {
            try {
                for (Statement statement : testCase) {
                    codes.add(statement.getCode());
                }
            } catch (RuntimeException e) {
                logger.debug("statement code extraction failed for operator attempt log: {}", e.getMessage());
                return Collections.emptyList();
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
