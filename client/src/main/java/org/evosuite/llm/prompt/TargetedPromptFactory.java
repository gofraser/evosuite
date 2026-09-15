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
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders the prompt for a batch of {@link OperatorTarget}s: the existing
 * test(s) each target starts from, one target block per target (goal,
 * distance, card evidence, action hint, complementarity for crossover) and a
 * single instruction naming the expected test methods. The same target block
 * serves per-individual seeding ({@link #buildGenerate}); the stagnation
 * prompt shares the detail rendering through {@link TargetSection}. Every
 * instruction string that reaches the model for these features is defined here.
 */
public final class TargetedPromptFactory {

    /** Method name the model is asked to use for target {@code i}; responses are mapped back by it. */
    static final String TEST_METHOD_NAME_FORMAT = "test_target_%d";
    static final String TARGETS_HEADER = "Targets:";
    static final String TARGET_HEADING_FORMAT = "Target #%d: %s";
    static final String DISTANCE_FORMAT = " (fitness distance %.4f)";
    static final String START_FROM_FORMAT = "Start from Existing test #%d.";
    static final String COMBINE_FORMAT = "Combine Existing test #%d (A) with Existing test #%d (B).";
    static final String ONLY_COVERS_FORMAT = "Only %s covers: %s";
    static final int MAX_EVIDENCE_LINES = 3;
    static final int MAX_COMPLEMENTARY_GOALS = 3;

    static final String MUTATE_SENTENCE = "For each target with one existing test, produce exactly one "
            + "JUnit test method named test_target_<i> (i = the target number) that starts from that test "
            + "and changes as little as necessary to reach the target.";
    static final String RECOMBINE_SENTENCE = "For each target with two existing tests, produce exactly one "
            + "JUnit test method named test_target_<i> (i = the target number) that combines the setup from "
            + "test A with the call from test B, changing as little as necessary to reach the target.";
    static final String GENERATE_SENTENCE = "Produce exactly one JUnit test method named test_target_1 "
            + "that reaches the target; if an existing test is shown, start from it.";
    static final String KEEP_SETUP_SENTENCE = " Keep the setup that already works.";

    private final GoalDescriptionMapper goalDescriptionMapper;

    public TargetedPromptFactory() {
        this(new GoalDescriptionMapper());
    }

    public TargetedPromptFactory(GoalDescriptionMapper goalDescriptionMapper) {
        this.goalDescriptionMapper = goalDescriptionMapper == null
                ? new GoalDescriptionMapper() : goalDescriptionMapper;
    }

    /** The method name target number {@code index} (1-based) is asked to produce. */
    public static String testMethodName(int index) {
        return String.format(Locale.ROOT, TEST_METHOD_NAME_FORMAT, index);
    }

    /** Operator prompt: mutate every single-parent target, recombine every paired one. */
    public PromptResult build(List<OperatorTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }
        boolean anyMutation = false;
        boolean anyCrossover = false;
        for (OperatorTarget target : targets) {
            anyMutation |= target.getKind() == OperatorTarget.Kind.MUTATION;
            anyCrossover |= target.getKind() == OperatorTarget.Kind.CROSSOVER;
        }
        StringBuilder instruction = new StringBuilder();
        if (anyMutation) {
            instruction.append(MUTATE_SENTENCE);
        }
        if (anyCrossover) {
            instruction.append(anyMutation ? " " : "").append(RECOMBINE_SENTENCE);
        }
        instruction.append(KEEP_SETUP_SENTENCE);
        return build(targets, instruction.toString());
    }

    /** Seeding prompt: generate one new test for a single target (the parent is shown as the starting point). */
    public PromptResult buildGenerate(OperatorTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        return build(Collections.singletonList(target), GENERATE_SENTENCE);
    }

    private PromptResult build(List<OperatorTarget> targets, String instruction) {
        List<TestCase> existingTests = new ArrayList<>();
        Map<TestCase, Integer> testIndex = new IdentityHashMap<>();
        for (OperatorTarget target : targets) {
            register(target.getParent(), existingTests, testIndex);
            target.getPartner().ifPresent(partner -> register(partner, existingTests, testIndex));
        }
        Set<TestFitnessFunction> goals = new LinkedHashSet<>();
        List<ProblemCard> cards = new ArrayList<>();
        List<ProblemCardType> cardTypes = new ArrayList<>();
        List<RepeatedInjectionTarget> repeated = new ArrayList<>();
        StringBuilder section = new StringBuilder(TARGETS_HEADER).append('\n');
        int index = 1;
        for (OperatorTarget target : targets) {
            goals.add(target.getGoal());
            repeated.add(target.toRepeatedInjectionTarget());
            target.getCard().ifPresent(card -> {
                cards.add(card);
                cardTypes.add(card.getType());
            });
            appendTarget(section, index++, target, existingTests, testIndex);
        }

        PromptResult prompt = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withExistingTests(existingTests)
                .withInstruction(section.toString().trim())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction(instruction + LlmAssertionPolicyResolver.instructionSuffix(false))
                .buildWithMetadata();
        return prompt.toBuilder()
                .diagnosticCards(cards)
                .diagnosticCardTypes(cardTypes)
                .repeatedInjectionTargets(repeated)
                .build();
    }

    private static void register(TestChromosome chromosome, List<TestCase> existingTests,
                                 Map<TestCase, Integer> testIndex) {
        TestCase test = chromosome.getTestCase();
        if (!testIndex.containsKey(test)) {
            existingTests.add(test);
            testIndex.put(test, existingTests.size());
        }
    }

    private void appendTarget(StringBuilder sb, int index, OperatorTarget target,
                              List<TestCase> existingTests, Map<TestCase, Integer> testIndex) {
        sb.append(String.format(Locale.ROOT, TARGET_HEADING_FORMAT, index,
                goalDescriptionMapper.describe(target.getGoal())));
        if (Double.isFinite(target.getDistance())) {
            sb.append(String.format(Locale.ROOT, DISTANCE_FORMAT, target.getDistance()));
        }
        sb.append('\n');
        int parentIndex = testIndex.get(target.getParent().getTestCase());
        if (target.getPartner().isPresent()) {
            TestCase partner = target.getPartner().get().getTestCase();
            sb.append(TargetSection.DETAIL_PREFIX)
                    .append(String.format(Locale.ROOT, COMBINE_FORMAT, parentIndex, testIndex.get(partner)))
                    .append('\n');
            appendComplementarity(sb, "A", target.getParent().getTestCase(), partner);
            appendComplementarity(sb, "B", partner, target.getParent().getTestCase());
        } else {
            sb.append(TargetSection.DETAIL_PREFIX)
                    .append(String.format(Locale.ROOT, START_FROM_FORMAT, parentIndex))
                    .append('\n');
        }
        if (target.getCard().isPresent()) {
            ProblemCard card = target.getCard().get();
            TargetSection.appendDetails(sb, card.getEvidence(), MAX_EVIDENCE_LINES,
                    card.getConcreteExamples(), existingTests,
                    card.getType() == null ? "" : card.getType().getActionHint());
        }
    }

    /** One line naming what only {@code test} covers relative to {@code other}; nothing when the sets do not differ. */
    private void appendComplementarity(StringBuilder sb, String label, TestCase test, TestCase other) {
        Set<TestFitnessFunction> only = new LinkedHashSet<>(coveredGoals(test));
        only.removeAll(coveredGoals(other));
        if (only.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (TestFitnessFunction goal : only) {
            if (names.size() >= MAX_COMPLEMENTARY_GOALS) {
                break;
            }
            names.add(goalDescriptionMapper.describe(goal));
        }
        String joined = String.join("; ", names);
        if (only.size() > names.size()) {
            joined += String.format(Locale.ROOT, ClusterGrowthDelta.TRUNCATION_FORMAT, only.size() - names.size());
        }
        sb.append(TargetSection.DETAIL_PREFIX)
                .append(String.format(Locale.ROOT, ONLY_COVERS_FORMAT, label, joined))
                .append('\n');
    }

    private static Set<TestFitnessFunction> coveredGoals(TestCase test) {
        Set<TestFitnessFunction> covered = test == null ? null : test.getCoveredGoals();
        return covered == null ? Collections.emptySet() : covered;
    }
}
