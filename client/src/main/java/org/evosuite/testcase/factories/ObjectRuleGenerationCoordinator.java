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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.synthesizer.ConstructionPathSynthesizer;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.PartialGraph;
import org.evosuite.testcase.synthesizer.PreparedObjectRuleCandidate;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates speculative EvoObj generation without coupling it to ordinary random generation. */
final class ObjectRuleGenerationCoordinator {

    /**
     * The logger for this class.
     */
    private static final Logger logger =
            LoggerFactory.getLogger(ObjectRuleGenerationCoordinator.class);

    private final RandomLengthTestFactory owner;
    private final ObjectRuleEntryBuilder entryBuilder = new ObjectRuleEntryBuilder();
    private final ObjectRuleTemplateSynthesizer templateSynthesizer;

    ObjectRuleGenerationCoordinator(RandomLengthTestFactory owner) {
        this.owner = owner;
        templateSynthesizer = new ObjectRuleTemplateSynthesizer(this);
    }

    private static ObjectRuleSession session() {
        return org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime().getSession();
    }

    private static ObjectRuleBranchSelector branchSelector() {
        return org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime()
                .getBranchSelector();
    }

    PartialGraph preparedGraph(Branch branch) {
        PreparedObjectRuleCandidate candidate = branchSelector().getPreparedCandidate(branch);
        return candidate == null ? null : candidate.getPartialGraph();
    }

    /** Search state that must be invisible to ordinary generation after a rejected attempt. */
    static final class SpeculativeGenerationState {
        private final Randomness.State randomness;
        private final double primitiveReuseProbability;
        private final boolean entryConstruction;
        private final TargetContext targetContext;

        private SpeculativeGenerationState() {
            randomness = Randomness.snapshot();
            primitiveReuseProbability = Properties.PRIMITIVE_REUSE_PROBABILITY;
            entryConstruction =
                    org.evosuite.TestGenerationContext.getInstance()
                            .getObjectRuleRuntime()
                            .isEntryConstruction();
            targetContext = TargetContext.getActive();
        }
    }

    /** Attempts EvoObj generation, transparently falling back to ordinary generation. */
    TestCase generate(int size) {
        /*
         * Everything below is speculative until an EvoObj template is actually emitted.
         * Keep one checkpoint before the application gate: rejected gates,
         * failed entry construction and failed synthesis must all hand ordinary generation
         * the exact stream state it would have seen with apply_object_rule=false.
         */
        SpeculativeGenerationState stateBeforeAttempt =
                new SpeculativeGenerationState();
        TestCase generated = getWholeClassObjectRuleTestCase(size, stateBeforeAttempt);
        if (generated != null && generated.size() < size) {
            return generated;
        }
        return fallbackToDefault(size, stateBeforeAttempt);
    }

    /**
     * EvoObj whole-class generation path. There is no globally fixed target method, so the entry
     * method is derived from the working branch's own containing method: a call to that method is
     * inserted first, then the object-construction template is synthesized against it (with the
     * {@link org.evosuite.testcase.synthesizer.TargetContext} published for the synthesis). Falls
     * back to a plain random test when the object rule does not apply or the branch is not a valid
     * entry.
     */
    private TestCase getWholeClassObjectRuleTestCase(
            int size, SpeculativeGenerationState stateBeforeAttempt) {

        Branch b = session().getWorkingBranch();
        PreparedObjectRuleCandidate candidate = branchSelector().getPreparedCandidate(b);
        org.evosuite.testcase.synthesizer.TargetContext context =
                candidate == null
                        ? ((b == null)
                                ? null
                                : org.evosuite.testcase.synthesizer.TargetContext.forBranch(b))
                        : candidate.getTargetContext();
        boolean applyObjectRule =
                context != null
                        && !ObjectRuleTemplateSynthesizer.shouldStopSynthesisForNoPayoff()
                        && Properties.P_OBJECT_RULE_APPLICATION > 0.0
                        && Randomness.nextDouble() <= Properties.P_OBJECT_RULE_APPLICATION;
        if (!applyObjectRule) {

            // Merely considering EvoObj must not perturb ordinary random generation. In
            // particular, p_object_rule_application=0 must be exactly reproducible against
            // baseline even after whole-class eligibility has selected a working branch.
            return fallbackToDefault(size, stateBeforeAttempt);
        }

        boolean allowNullValue = Properties.NULL_PROBABILITY > Randomness.nextDouble();
        final double oldPrimitiveReuse = Properties.PRIMITIVE_REUSE_PROBABILITY;

        Properties.PRIMITIVE_REUSE_PROBABILITY = 0;

        org.evosuite.testcase.synthesizer.TargetContext.setActive(context);
        try {

            PartialGraph entryGraph =
                    candidate == null
                            ? newConstructionPathSynthesizer().constructPartialComputationGraph(b)
                            : candidate.getPartialGraph();
            // Occasionally reuse a proven cached template.
            TestCase cached = ObjectRuleTemplateSynthesizer.tryReuseCachedTemplate(b);

            if (cached != null && cached.size() < size) {

                session().templateInjections++;
                session().templateCacheHits++;

                return cached;
            }

            TestCase test = null;
            ObjectRuleEntryBuilder.EntryInsertionResult insertion =
                    ObjectRuleEntryBuilder.EntryInsertionResult.RETRYABLE_FAILURE;
            int cleanConstructionAttempts = 0;
            // Generator registration can expose several implementations of an interface. A
            // single construction failure should not immediately force synthesis to decorate a
            // random baseline prefix. Try a small clean portfolio while stable-generator ranking
            // is active; failed candidates are abandoned wholesale, so none of their throwing
            // dependencies leak into the selected entry.
            for (int entryAttempt = 0; entryAttempt < 3; entryAttempt++) {
                cleanConstructionAttempts++;
                test = owner.getNewTestCase();
                boolean previousEntryConstruction =
                        org.evosuite.TestGenerationContext.getInstance()
                                .getObjectRuleRuntime()
                                .setEntryConstruction(true);
                try {
                    insertion = entryBuilder.insertEntryMethodCall(test, context, entryGraph);
                } finally {
                    org.evosuite.TestGenerationContext.getInstance()
                            .getObjectRuleRuntime()
                            .setEntryConstruction(previousEntryConstruction);
                }
                if (test.size() >= size) {
                    while (test.size() > 0) {
                        test.remove(test.size() - 1);
                    }
                    insertion = ObjectRuleEntryBuilder.EntryInsertionResult.RETRYABLE_FAILURE;
                }
                if (insertion == ObjectRuleEntryBuilder.EntryInsertionResult.SUCCESS
                        || insertion
                                == ObjectRuleEntryBuilder.EntryInsertionResult.PERMANENT_FAILURE) {
                    break;
                }
            }
            if (insertion != ObjectRuleEntryBuilder.EntryInsertionResult.SUCCESS) {
                // Preserve baseline generation exactly when speculative construction fails. Once
                // the ordinary test exists, however, it may already contain a receiver produced by
                // the same factory path used by regular search (eg
                // CompoundByteTransition.coalesce).
                TestCase baseline = generateDefaultFromCheckpoint(size, stateBeforeAttempt);
                Randomness.State randomnessAfterBaseline = Randomness.snapshot();
                TestCase attachmentCandidate;
                try {
                    attachmentCandidate = baseline.clone();
                } catch (RuntimeException | AssertionError cloneFailure) {
                    Randomness.restore(randomnessAfterBaseline);
                    return baseline;
                }
                boolean previousFallbackEntryConstruction =
                        org.evosuite.TestGenerationContext.getInstance()
                                .getObjectRuleRuntime()
                                .setEntryConstruction(true);
                boolean attachedToExistingReceiver;
                try {
                    attachedToExistingReceiver =
                            entryBuilder.insertEntryMethodCallOnExistingReceiver(
                                    attachmentCandidate, context);
                } finally {
                    org.evosuite.TestGenerationContext.getInstance()
                            .getObjectRuleRuntime()
                            .setEntryConstruction(previousFallbackEntryConstruction);
                }
                if (attachedToExistingReceiver) {
                    return templateSynthesizer.synthesize(
                            attachmentCandidate, allowNullValue, size, stateBeforeAttempt);
                }
                Randomness.restore(randomnessAfterBaseline);
                if (insertion == ObjectRuleEntryBuilder.EntryInsertionResult.PERMANENT_FAILURE) {
                    branchSelector().recordEntryConstructionFailure(b);
                } else {

                    branchSelector().recordRetryableEntryConstructionFailure(b);
                }
                return baseline;
            }
            return templateSynthesizer.synthesize(test, allowNullValue, size, stateBeforeAttempt);

        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
            logger.debug("Whole-class EvoObj attempt failed; using ordinary generation", failure);
            return fallbackToDefault(size, stateBeforeAttempt);
        } finally {
            Properties.PRIMITIVE_REUSE_PROBABILITY = oldPrimitiveReuse;

            org.evosuite.testcase.synthesizer.TargetContext.clearActive();
        }
    }

    TestCase fallbackToDefault(int size, SpeculativeGenerationState stateBeforeAttempt) {

        return generateDefaultFromCheckpoint(size, stateBeforeAttempt);
    }

    /**
     * Runs default generation with the complete pre-attempt context. The surrounding speculative
     * context is restored only so enclosing finally blocks remain well nested; the RNG deliberately
     * remains at the post-baseline state.
     */
    private TestCase generateDefaultFromCheckpoint(
            int size, SpeculativeGenerationState stateBeforeAttempt) {

        double speculativePrimitiveReuse = Properties.PRIMITIVE_REUSE_PROBABILITY;
        boolean speculativeEntryConstruction =
                org.evosuite.TestGenerationContext.getInstance()
                        .getObjectRuleRuntime()
                        .isEntryConstruction();
        TargetContext speculativeTargetContext = TargetContext.getActive();
        Randomness.restore(stateBeforeAttempt.randomness);
        Properties.PRIMITIVE_REUSE_PROBABILITY =
                stateBeforeAttempt.primitiveReuseProbability;

        org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime()
                .setEntryConstruction(stateBeforeAttempt.entryConstruction);
        restoreTargetContext(stateBeforeAttempt.targetContext);
        try {
            return owner.getBaselineRandomTestCase(size);
        } finally {
            Properties.PRIMITIVE_REUSE_PROBABILITY = speculativePrimitiveReuse;

            org.evosuite.TestGenerationContext.getInstance()
                    .getObjectRuleRuntime()
                    .setEntryConstruction(speculativeEntryConstruction);
            restoreTargetContext(speculativeTargetContext);
        }
    }

    private static void restoreTargetContext(TargetContext context) {
        if (context == null) {
            TargetContext.clearActive();
        } else {
            TargetContext.setActive(context);
        }
    }

    /**
     * Factory seam for deterministic failure-path tests. Production uses the normal synthesizer;
     * tests can supply a no-op, unresolved-root, throwing, or inconsistent implementation without
     * introducing test-only flags into the search.
     */
    ConstructionPathSynthesizer newConstructionPathSynthesizer() {
        return owner.newConstructionPathSynthesizer();
    }

    /** Consistency seam paired with {@link #newConstructionPathSynthesizer()}. */
    boolean isSynthesizedTestConsistent(TestCase test) {
        return owner.isSynthesizedTestConsistent(test);
    }

}
