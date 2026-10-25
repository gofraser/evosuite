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
package org.evosuite.llm.response;

import org.evosuite.llm.prompt.PromptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * State of one {@link TestRepairLoop#attemptParse} call: what earlier repair
 * turns have already told the model, and which escalations they have spent.
 * It also decides when the conversation stops making progress and the loop
 * should give up rather than ask again.
 *
 * <p>One instance lives exactly as long as one call, so the loop itself holds
 * only configuration and can be shared between threads.
 */
final class RepairConversation {

    /** Logs under the loop's name: these messages describe its decisions. */
    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);

    /** Pattern to normalize line numbers for fuzzy error comparison. */
    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile(
            "(?<=\\(line |line |Line )\\d+|(?<=position )\\d+");

    /** The prompt this conversation started from, or null when it started from bare messages. */
    private PromptResult prompt;

    /** Whether cluster expansion ran since the last repair request went out. */
    private boolean expansionAttempted;
    /** Classes whose dependency signatures have already been sent to the model (cluster-growth delta). */
    private final Set<String> describedDependencyClasses = new LinkedHashSet<>();
    /**
     * FQCNs whose static initializer has failed. They stay in the avoidance
     * instructions even when later turns surface different errors.
     */
    private final Set<String> poisonedClasses = new LinkedHashSet<>();
    /** Hint-id -> last repair attempt where it was emitted, for cooldown/dedup. */
    /**
     * Access-violation tracking keys ("memberName:declaringClass") accumulated
     * across repair attempts. Used to detect recurrent inaccessible-member
     * errors and escalate to early termination.
     */
    private final Set<String> seenAccessViolations = new LinkedHashSet<>();

    /**
     * One-shot flag: set when the loop encounters an identical-error retry on
     * a headless/AWT failure and decides to escalate (do not skip; instead
     * inject a stronger "use Mockito.mock(SUT.class)" instruction). The next
     * repair message includes the escalation hint, after which this flag stays
     * true so we do not bypass the identical-error skip again in the same
     * conversation.
     */
    private boolean headlessRepairEscalated;

    /**
     * One-shot flag: set when the loop encounters an identical-error retry
     * that does not match any of the more specific escalation paths (headless,
     * access-violation, dependency-missing). Allows exactly one more repair
     * turn with a generic "the environment cannot satisfy this assumption,
     * change the expected exception or drop the test" nudge prepended to the
     * user message before falling back to abort.
     */
    private boolean identicalErrorEscalated;

    /**
     * Transient text injected at the top of the next repair user message. Used
     * by escalation paths to communicate a single high-priority directive that
     * must not be diluted by the rest of the rule-based hint blocks. Cleared
     * after it is consumed so the same hint is not repeated on later turns.
     */
    private String pendingTopOfMessageEscalationHint;

    /** Records the prompt this conversation started from; null when it started from bare messages. */
    void setPrompt(PromptResult prompt) {
        this.prompt = prompt;
    }

    /** The prompt this conversation started from, whose context metadata repair turns carry; may be null. */
    PromptResult getPrompt() {
        return prompt;
    }

    /** True if repeating the dependency-type catalogue in a repair turn would only duplicate it. */
    boolean isDependencyContextInPrompt() {
        return prompt != null && prompt.isDependencyContextIncluded();
    }

    /** Records that cluster expansion ran; the next repair request reports it. */
    void markExpansionAttempted() {
        expansionAttempted = true;
    }

    /** Returns whether expansion ran since the previous request, and clears the flag. */
    boolean takeExpansionAttempted() {
        boolean expanded = expansionAttempted;
        expansionAttempted = false;
        return expanded;
    }

    /** Returns the pending top-of-message directive, or null, and clears it. */
    String takePriorityDirective() {
        String directive = pendingTopOfMessageEscalationHint;
        pendingTopOfMessageEscalationHint = null;
        return directive;
    }

    /**
     * Returns the classes of {@code expandedClasses} whose signatures have not
     * been sent yet, and records them as sent.
     */
    Set<String> takeUndescribedDependencyClasses(Collection<String> expandedClasses) {
        Set<String> newlyDescribed = new LinkedHashSet<>(expandedClasses);
        newlyDescribed.removeAll(describedDependencyClasses);
        describedDependencyClasses.addAll(newlyDescribed);
        return newlyDescribed;
    }

    void recordPoisonedClass(String className) {
        poisonedClasses.add(className);
    }

    Set<String> getPoisonedClasses() {
        return Collections.unmodifiableSet(poisonedClasses);
    }

    /**
     * Records the access violations of the current error and returns those
     * that were already reported on an earlier turn.
     */
    Set<String> recordAccessViolations(Set<String> trackingKeys) {
        Set<String> recurrent = new LinkedHashSet<>();
        for (String key : trackingKeys) {
            if (seenAccessViolations.contains(key)) {
                recurrent.add(key);
            }
        }
        seenAccessViolations.addAll(trackingKeys);
        return recurrent;
    }

    boolean isHeadlessRepairEscalated() {
        return headlessRepairEscalated;
    }

    /**
     * Returns true if the error should not be sent to the LLM for repair
     * (either because it is an environment-level error that the LLM cannot fix,
     * or because it is identical to the previous error, indicating a stuck loop).
     */
    boolean shouldSkipRepair(String error, String previousError, List<String> diagnostics) {
        if (TestRepairLoop.isUnfixableError(error)) {
            logger.info("Aborting repair: unfixable environment error detected: {}", error);
            diagnostics.add("Skipped repair: unfixable environment error");
            return true;
        }
        if (previousError != null && normalizeError(previousError).equals(normalizeError(error))) {
            RepairFailureKinds kinds = RepairFailureKinds.of(error);
            if (kinds.isDependencyMissing()) {
                logger.info("Aborting repair: dependency-missing error persisted after targeted guidance: {}", error);
                diagnostics.add("Skipped repair: dependency-missing error persisted");
                return true;
            }
            // Headless escalation: when the LLM keeps producing tests that crash on
            // AWT/Swing initialization, the diagnostics fed back to it usually
            // describe downstream NPEs (because the parser elided the GUI ctor with
            // a typed null), not the original headless error. Give the LLM one more
            // turn with an explicit "use Mockito.mock(SUT.class)" instruction
            // before bailing out.
            if (!headlessRepairEscalated && kinds.isHeadlessGui()) {
                headlessRepairEscalated = true;
                logger.info("Continuing repair with headless escalation hint after identical-error retry: {}", error);
                diagnostics.add("Continuing repair: headless escalation hint injected for identical-error retry");
                return false;
            }
            // Generic identical-error escalation: the LLM has produced essentially
            // the same source as last turn (same normalized error). Before
            // bailing out, give it exactly one more turn with a strong, top-of-
            // message directive to either change the expected exception class
            // to what was actually observed or drop the test entirely.
            if (!identicalErrorEscalated) {
                identicalErrorEscalated = true;
                pendingTopOfMessageEscalationHint = buildIdenticalErrorEscalationHint(error);
                logger.info("Continuing repair with identical-error escalation hint: {}", error);
                diagnostics.add("Continuing repair: identical-error escalation hint injected");
                return false;
            }
            logger.info("Aborting repair: equivalent error on consecutive attempts: {}", error);
            diagnostics.add("Skipped repair: identical error repeated");
            return true;
        }
        // Escalation: if every access violation in the current error has already
        // been reported in a previous attempt, the LLM is stuck retrying the same
        // inaccessible members — stop the loop.
        if (hasOnlyRecurrentAccessViolations(error)) {
            logger.info("Aborting repair: all access violations are recurrent: {}", error);
            diagnostics.add("Skipped repair: recurrent access violations");
            return true;
        }
        return false;
    }

    /**
     * Returns true when the error contains access-violation diagnostics and
     * every single one has already been seen in a previous repair attempt.
     * This detects the pattern where the LLM keeps retrying the same
     * inaccessible members despite being told they are off-limits.
     */
    private boolean hasOnlyRecurrentAccessViolations(String error) {
        if (!AccessViolationDiagnosticParser.containsAccessViolation(error)) {
            return false;
        }
        Set<String> currentKeys = AccessViolationDiagnosticParser.extractTrackingKeys(error);
        if (currentKeys.isEmpty()) {
            return false;
        }
        // All current violations must have been seen before
        return seenAccessViolations.containsAll(currentKeys);
    }

    /**
     * Builds the one-shot top-of-message escalation hint sent on the bonus
     * turn after the LLM produced an identical normalized error. The text is
     * intentionally generic: it tells the LLM that the previous repair did not
     * change the failure, so the previous response was effectively the same
     * source, and that on this turn it must either change the assertThrows
     * expectation to whatever was actually observed or drop the offending
     * tests entirely.
     */
    static String buildIdenticalErrorEscalationHint(String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("STOP: identical failure repeated.\n");
        sb.append("Your previous repair produced the same normalized error as the turn before.\n");
        sb.append("That means the test source for the failing tests is effectively unchanged.\n");
        sb.append("Do NOT submit another response that tweaks the same tests in the same shape.\n");
        sb.append("On this turn you MUST do one of the following for every failing test:\n");
        sb.append("  1) Change the assertThrows expectation to the EXACT exception class observed\n");
        sb.append("     at runtime (look at \"actual=...\" in the failure list above).\n");
        sb.append("  2) Replace the test with a different one that does NOT reach the failing call\n");
        sb.append("     (e.g. exercise a different SUT method, constructor, or pure-logic path).\n");
        sb.append("  3) Remove the test from the class entirely.\n");
        if (MockRuntimeExceptions.errorMentionsMockRuntimeException(error)) {
            sb.append("Note: the failure list mentions an exception under ")
                    .append(MockRuntimeExceptions.MOCK_RUNTIME_PACKAGE_PREFIX).append("*.\n");
            sb.append("That is the EvoSuite mock harness, not the SUT, and it is unavoidable in this\n");
            sb.append("environment for any test reaching that call site. Treat it as ground truth.\n");
        }
        sb.append("Returning the same tests with cosmetic changes (renamed locals, reordered imports)\n");
        sb.append("will be rejected as no progress.");
        return sb.toString();
    }

    /**
     * Normalizes an error string for fuzzy comparison by replacing line numbers
     * with a placeholder, so that the same logical error at different positions
     * is recognized as a stuck loop.
     */
    static String normalizeError(String error) {
        if (error == null) {
            return "";
        }
        return LINE_NUMBER_PATTERN.matcher(error).replaceAll("N");
    }
}
