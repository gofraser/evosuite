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
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds real {@link TargetedPromptFactory} prompts from synthetic {@link OperatorTarget}s
 * and inspects the rendered messages: the targets section (goal, distance,
 * combine/start-from, complementarity, card evidence), the batch instruction,
 * and the trace metadata carried on {@link PromptResult}.
 */
class TargetedPromptFactoryTest {

    private String originalTargetClass;

    @BeforeEach
    void saveProperties() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = "com.example.Foo";
    }

    @AfterEach
    void restoreProperties() {
        Properties.TARGET_CLASS = originalTargetClass;
    }

    private static TestChromosome chromosome(int marker) {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, marker));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        chromosome.setLastExecutionResult(new ExecutionResult(testCase));
        chromosome.setChanged(false);
        return chromosome;
    }

    private static TestFitnessFunction goal(String name) {
        return new StubGoal(name);
    }

    private static String userText(PromptResult result) {
        for (LlmMessage message : result.getMessages()) {
            if (message.getRole() == LlmMessage.Role.USER) {
                return message.getContent();
            }
        }
        return "";
    }

    private static String systemText(PromptResult result) {
        for (LlmMessage message : result.getMessages()) {
            if (message.getRole() == LlmMessage.Role.SYSTEM) {
                return message.getContent();
            }
        }
        return "";
    }

    // ---- testMethodName ------------------------------------------------------

    @Test
    void testMethodNameFormatsIndex() {
        assertEquals("test_target_3", TargetedPromptFactory.testMethodName(3));
    }

    // ---- mutation target -------------------------------------------------------

    @Test
    void mutationTargetRendersDistanceStartFromAndMutateInstruction() {
        TestChromosome parent = chromosome(1);
        TestFitnessFunction g = goal("g1");
        OperatorTarget target = OperatorTarget.builder(parent, g).distance(0.25).priority(0.5).build();

        String user = userText(new TargetedPromptFactory().build(Collections.singletonList(target)));

        assertTrue(user.contains("Existing tests:"));
        assertTrue(user.contains("Existing test #1:"));
        String heading = String.format(Locale.ROOT, TargetedPromptFactory.TARGET_HEADING_FORMAT, 1,
                new GoalDescriptionMapper().describe(g))
                + String.format(Locale.ROOT, TargetedPromptFactory.DISTANCE_FORMAT, 0.25);
        assertTrue(user.contains(heading), "expected heading not found: " + heading);
        assertTrue(user.contains(String.format(Locale.ROOT, TargetedPromptFactory.START_FROM_FORMAT, 1)));
        assertTrue(user.contains(TargetedPromptFactory.MUTATE_SENTENCE));
        assertTrue(user.contains(TargetedPromptFactory.KEEP_SETUP_SENTENCE));
        assertFalse(user.contains(TargetedPromptFactory.RECOMBINE_SENTENCE));
        assertTrue(user.contains(LlmAssertionPolicyResolver.instructionSuffix(false)));
    }

    // ---- crossover target -------------------------------------------------------

    @Test
    void crossoverTargetRendersCombineAndComplementarity() {
        TestChromosome parent = chromosome(1);
        TestChromosome partner = chromosome(2);
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");
        TestFitnessFunction goalY = goal("y");
        parent.getTestCase().addCoveredGoal(goalX);
        partner.getTestCase().addCoveredGoal(goalY);

        OperatorTarget target = OperatorTarget.builder(parent, g).partner(partner).build();
        String user = userText(new TargetedPromptFactory().build(Collections.singletonList(target)));

        assertTrue(user.contains("Existing test #1:"));
        assertTrue(user.contains("Existing test #2:"));
        assertTrue(user.contains(String.format(Locale.ROOT, TargetedPromptFactory.COMBINE_FORMAT, 1, 2)));
        GoalDescriptionMapper mapper = new GoalDescriptionMapper();
        assertTrue(user.contains(String.format(Locale.ROOT, TargetedPromptFactory.ONLY_COVERS_FORMAT,
                "A", mapper.describe(goalX))));
        assertTrue(user.contains(String.format(Locale.ROOT, TargetedPromptFactory.ONLY_COVERS_FORMAT,
                "B", mapper.describe(goalY))));
        assertTrue(user.contains(TargetedPromptFactory.RECOMBINE_SENTENCE));
        assertFalse(user.contains(TargetedPromptFactory.MUTATE_SENTENCE));
    }

    @Test
    void crossoverWithEqualCoveredGoalsOmitsComplementarityLines() {
        TestChromosome parent = chromosome(1);
        TestChromosome partner = chromosome(2);
        TestFitnessFunction g = goal("g");
        TestFitnessFunction shared = goal("shared");
        parent.getTestCase().addCoveredGoal(shared);
        partner.getTestCase().addCoveredGoal(shared);

        OperatorTarget target = OperatorTarget.builder(parent, g).partner(partner).build();
        String user = userText(new TargetedPromptFactory().build(Collections.singletonList(target)));

        assertFalse(user.contains("Only A covers:"));
        assertFalse(user.contains("Only B covers:"));
    }

    @Test
    void crossoverComplementarityListIsCappedAndTruncated() {
        TestChromosome parent = chromosome(1);
        TestChromosome partner = chromosome(2);
        TestFitnessFunction g = goal("g");
        List<TestFitnessFunction> exclusive = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            TestFitnessFunction goalI = goal("g" + i);
            exclusive.add(goalI);
            parent.getTestCase().addCoveredGoal(goalI);
        }

        OperatorTarget target = OperatorTarget.builder(parent, g).partner(partner).build();
        String user = userText(new TargetedPromptFactory().build(Collections.singletonList(target)));

        GoalDescriptionMapper mapper = new GoalDescriptionMapper();
        String joined = mapper.describe(exclusive.get(0)) + "; " + mapper.describe(exclusive.get(1)) + "; "
                + mapper.describe(exclusive.get(2))
                + String.format(Locale.ROOT, ClusterGrowthDelta.TRUNCATION_FORMAT, 2);
        String expectedLine = String.format(Locale.ROOT, TargetedPromptFactory.ONLY_COVERS_FORMAT, "A", joined);
        assertTrue(user.contains(expectedLine), "expected truncated complementarity line not found: " + expectedLine);
        assertTrue(expectedLine.endsWith(" ... (+2 more)"));
    }

    // ---- mixed batch -------------------------------------------------------------

    @Test
    void mixedBatchOrdersMutationBeforeRecombineAndNumbersThreeTests() {
        TestChromosome mutationParent = chromosome(1);
        TestChromosome crossoverParent = chromosome(2);
        TestChromosome crossoverPartner = chromosome(3);
        TestFitnessFunction g1 = goal("g1");
        TestFitnessFunction g2 = goal("g2");

        OperatorTarget mutationTarget = OperatorTarget.builder(mutationParent, g1).build();
        OperatorTarget crossoverTarget = OperatorTarget.builder(crossoverParent, g2).partner(crossoverPartner).build();

        String user = userText(new TargetedPromptFactory().build(Arrays.asList(mutationTarget, crossoverTarget)));

        int mutateIdx = user.indexOf(TargetedPromptFactory.MUTATE_SENTENCE);
        int recombineIdx = user.indexOf(TargetedPromptFactory.RECOMBINE_SENTENCE);
        assertTrue(mutateIdx >= 0, "mutate sentence must be present");
        assertTrue(recombineIdx >= 0, "recombine sentence must be present");
        assertTrue(mutateIdx < recombineIdx, "mutation sentence must come first");
        assertTrue(user.contains("Target #1"));
        assertTrue(user.contains("Target #2"));
        assertTrue(user.contains("Existing test #1:"));
        assertTrue(user.contains("Existing test #2:"));
        assertTrue(user.contains("Existing test #3:"));
    }

    @Test
    void sharedChromosomeAcrossTargetsIsRegisteredOnce() {
        TestChromosome shared = chromosome(1);
        TestChromosome otherParent = chromosome(2);
        TestFitnessFunction g1 = goal("g1");
        TestFitnessFunction g2 = goal("g2");

        OperatorTarget target1 = OperatorTarget.builder(shared, g1).build();
        OperatorTarget target2 = OperatorTarget.builder(otherParent, g2).partner(shared).build();

        String user = userText(new TargetedPromptFactory().build(Arrays.asList(target1, target2)));

        assertTrue(user.contains("Existing test #1:"));
        assertTrue(user.contains("Existing test #2:"));
        assertFalse(user.contains("Existing test #3:"));
        assertTrue(user.contains(String.format(Locale.ROOT, TargetedPromptFactory.COMBINE_FORMAT, 2, 1)));
    }

    // ---- distance edge cases -----------------------------------------------------

    @Test
    void nanDistanceRendersNoDistanceParenthetical() {
        TestChromosome parent = chromosome(1);
        TestFitnessFunction g = goal("g");
        OperatorTarget target = OperatorTarget.builder(parent, g).build();

        String user = userText(new TargetedPromptFactory().build(Collections.singletonList(target)));

        assertFalse(user.contains("(fitness distance"));
    }

    // ---- problem card rendering ---------------------------------------------------

    @Test
    void cardEvidenceIsCappedAndConcreteExampleReferencesParentsOwnTest() {
        TestChromosome parent = chromosome(1);
        TestFitnessFunction g = goal("g");
        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("t")
                .evidence(Arrays.asList("ev1", "ev2", "ev3", "ev4", "ev5"))
                .impact(1.0).blockage(1.0).confidence(1.0)
                .build()
                .withConcreteExample(parent.getTestCase(), "desc");

        OperatorTarget target = OperatorTarget.builder(parent, g).card(card).build();
        String user = userText(new TargetedPromptFactory().build(Collections.singletonList(target)));

        assertTrue(user.contains("ev1"));
        assertTrue(user.contains("ev2"));
        assertTrue(user.contains("ev3"));
        assertFalse(user.contains("ev4"));
        assertFalse(user.contains("ev5"));
        assertTrue(user.contains(TargetSection.SUGGESTED_ACTION_PREFIX + ProblemCardType.UNREACHED_METHOD.getActionHint()));
        assertTrue(user.contains("desc" + String.format(TargetSection.SEE_EXISTING_TEST_FORMAT, 1)));
    }

    // ---- metadata -------------------------------------------------------------------

    @Test
    void metadataCollectsRepeatedInjectionTargetsAndDiagnosticCards() {
        TestChromosome parent1 = chromosome(1);
        TestChromosome parent2 = chromosome(2);
        TestFitnessFunction g1 = goal("g1");
        TestFitnessFunction g2 = goal("g2");
        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("t").impact(1.0).blockage(1.0).confidence(1.0).build();

        OperatorTarget withCard = OperatorTarget.builder(parent1, g1).card(card).build();
        OperatorTarget withoutCard = OperatorTarget.builder(parent2, g2).build();

        PromptResult result = new TargetedPromptFactory().build(Arrays.asList(withCard, withoutCard));

        assertEquals(2, result.getRepeatedInjectionTargets().size());
        assertTrue(result.getRepeatedInjectionTargets().get(0).getKey().startsWith("operator|"));
        assertEquals(1, result.getDiagnosticCards().size());
        assertSame(card, result.getDiagnosticCards().get(0));
        assertEquals(1, result.getDiagnosticCardTypes().size());
        assertEquals(ProblemCardType.UNREACHED_METHOD, result.getDiagnosticCardTypes().get(0));
    }

    // ---- buildGenerate ----------------------------------------------------------------

    @Test
    void buildGenerateProducesGenerateInstructionAndSingleTarget() {
        TestChromosome parent = chromosome(1);
        TestFitnessFunction g = goal("g");
        OperatorTarget target = OperatorTarget.builder(parent, g).build();

        String user = userText(new TargetedPromptFactory().buildGenerate(target));

        assertTrue(user.contains(TargetedPromptFactory.GENERATE_SENTENCE));
        assertFalse(user.contains(TargetedPromptFactory.MUTATE_SENTENCE));
        assertFalse(user.contains(TargetedPromptFactory.RECOMBINE_SENTENCE));
        assertTrue(user.contains("Target #1"));
    }

    // ---- prefix stability ----------------------------------------------------------

    @Test
    void prefixHashIsStableAcrossDifferentTargets() {
        TestChromosome parent1 = chromosome(1);
        TestChromosome parent2 = chromosome(2);
        TestFitnessFunction g1 = goal("g1");
        TestFitnessFunction g2 = goal("g2");

        PromptResult result1 = new TargetedPromptFactory().build(
                Collections.singletonList(OperatorTarget.builder(parent1, g1).distance(0.1).build()));
        PromptResult result2 = new TargetedPromptFactory().build(
                Collections.singletonList(OperatorTarget.builder(parent2, g2).distance(0.9).build()));

        if (result1.getPrefixHash() != null && result2.getPrefixHash() != null) {
            assertEquals(result1.getPrefixHash(), result2.getPrefixHash(),
                    "prefix hash must be stable since the system message is static");
        } else {
            assertEquals(systemText(result1), systemText(result2));
        }
    }

    // ---- argument validation ---------------------------------------------------------

    @Test
    void buildRejectsEmptyTargetList() {
        assertThrows(IllegalArgumentException.class,
                () -> new TargetedPromptFactory().build(Collections.emptyList()));
    }

    @Test
    void buildRejectsNullTargetList() {
        assertThrows(IllegalArgumentException.class,
                () -> new TargetedPromptFactory().build(null));
    }

    @Test
    void buildGenerateRejectsNullTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new TargetedPromptFactory().buildGenerate(null));
    }

    /** Minimal goal stub whose fitness is read from the individual's cached fitness map. */
    private static final class StubGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String name;

        StubGoal(String name) {
            this.name = name;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return individual.getFitnessValues().getOrDefault(this, 1.0);
        }

        @Override
        public ExecutionResult runTest(org.evosuite.testcase.TestCase test) {
            return new ExecutionResult(test);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            if (!(other instanceof StubGoal)) {
                return compareClassName(other);
            }
            return name.compareTo(((StubGoal) other).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StubGoal)) {
                return false;
            }
            return name.equals(((StubGoal) other).name);
        }

        @Override
        public String getTargetClass() {
            return "example.Target";
        }

        @Override
        public String getTargetMethod() {
            return name;
        }

        @Override
        public String toString() {
            return "StubGoal[" + name + "]";
        }
    }
}
