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
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.testcase.AbstractTestChromosome;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.BranchOperands;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins down the closest-test/closest-goal lookups and the scope-gated attach
 * behaviour introduced by {@link ExemplarAttacher}.
 */
class ExemplarAttacherTest {

    private Properties.LlmExemplarScope originalScope;
    private boolean originalBranchOperandHints;

    @BeforeEach
    void saveScope() {
        originalScope = Properties.LLM_STAGNATION_EXEMPLAR_SCOPE;
        originalBranchOperandHints = Properties.LLM_BRANCH_OPERAND_HINTS;
    }

    @AfterEach
    void restoreScope() {
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = originalScope;
        Properties.LLM_BRANCH_OPERAND_HINTS = originalBranchOperandHints;
    }

    // ---- closestChromosome ----------------------------------

    @Test
    void closestChromosomeReturnsIndividualNearestExpectedDistance() {
        TestFitnessFunction goal = goal("com.example.Target", "far()V");
        TestChromosome near = chromosomeWithStatement(1);
        near.setFitness(goal, 0.1);
        TestChromosome middle = chromosomeWithStatement(2);
        middle.setFitness(goal, 0.5);
        TestChromosome far = chromosomeWithStatement(3);
        far.setFitness(goal, 0.9);
        List<TestChromosome> population = Arrays.asList(far, near, middle);

        TestChromosome closest = ExemplarAttacher.closestChromosome(goal, population, 0.52);

        assertSame(middle, closest);
        assertSame(middle, ExemplarAttacher.closestChromosome(goal, population, 0.52));
    }

    @Test
    void closestChromosomeReturnsNullWhenNoIndividualHasFiniteFitness() {
        TestFitnessFunction goal = goal("com.example.Target", "unset()V");
        TestChromosome noFitness = chromosomeWithStatement(1);
        TestChromosome infiniteFitness = chromosomeWithStatement(2);
        infiniteFitness.getFitnessValues().put(goal, Double.POSITIVE_INFINITY);
        List<TestChromosome> population = Arrays.asList(noFitness, infiniteFitness);

        assertNull(ExemplarAttacher.closestChromosome(goal, population, 0.3));
        assertNull(ExemplarAttacher.closestChromosome(goal, population, 0.3));
    }

    @Test
    void closestChromosomeIgnoresChromosomesWithoutATestCase() {
        TestFitnessFunction goal = goal("com.example.Target", "noTestCase()V");
        TestChromosome noTestCase = chromosomeWithoutTestCase();
        noTestCase.setFitness(goal, 0.5); // exact match to expected distance
        TestChromosome withTestCase = chromosomeWithStatement(7);
        withTestCase.setFitness(goal, 0.9); // farther, but the only eligible candidate
        List<TestChromosome> population = Arrays.asList(noTestCase, withTestCase);

        TestChromosome closest = ExemplarAttacher.closestChromosome(goal, population, 0.5);

        assertSame(withTestCase, closest,
                "A chromosome without a test case must be skipped even if its fitness is a better numeric match");
    }

    // ---- closestRelatedGoal -------------------------------------------------

    @Test
    void closestRelatedGoalPicksSmallestPositiveFiniteDistance() {
        TestFitnessFunction covered = goal("com.example.Target", "covered()V");
        TestFitnessFunction unreachable = goal("com.example.Target", "unreachable()V");
        TestFitnessFunction farther = goal("com.example.Target", "farther()V");
        TestFitnessFunction nearest = goal("com.example.Target", "nearest()V");
        TestFitnessFunction missing = goal("com.example.Target", "missing()V");

        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(covered, 0.0);
        best.put(unreachable, Double.POSITIVE_INFINITY);
        best.put(farther, 0.3);
        best.put(nearest, 0.1);
        // "missing" intentionally has no entry in the map.

        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("card")
                .relatedGoals(Arrays.asList(covered, unreachable, farther, nearest, missing))
                .build();

        assertSame(nearest, ExemplarAttacher.closestRelatedGoal(card, best));
    }

    @Test
    void closestRelatedGoalReturnsNullWhenNoGoalQualifies() {
        TestFitnessFunction covered = goal("com.example.Target", "covered()V");
        TestFitnessFunction unreachable = goal("com.example.Target", "unreachable()V");

        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(covered, 0.0);
        best.put(unreachable, Double.POSITIVE_INFINITY);

        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("card")
                .relatedGoals(Arrays.asList(covered, unreachable))
                .build();

        assertNull(ExemplarAttacher.closestRelatedGoal(card, best));
    }

    @Test
    void closestRelatedGoalRejectsDistancesAtOrAboveOne() {
        TestFitnessFunction atBound = goal("com.example.Target", "atBound()V");
        TestFitnessFunction farAway = goal("com.example.Target", "farAway()V");
        TestFitnessFunction justBelow = goal("com.example.Target", "justBelow()V");

        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(atBound, ExemplarAttacher.MAX_EXEMPLAR_DISTANCE);
        best.put(farAway, Double.MAX_VALUE);

        ProblemCard card = ProblemCard.builder(ProblemCardType.TYPE_NEVER_ATTEMPTED)
                .title("card")
                .relatedGoals(Arrays.asList(atBound, farAway))
                .build();
        assertNull(ExemplarAttacher.closestRelatedGoal(card, best));

        best.put(justBelow, Math.nextDown(ExemplarAttacher.MAX_EXEMPLAR_DISTANCE));
        ProblemCard withNearMiss = ProblemCard.builder(ProblemCardType.TYPE_NEVER_ATTEMPTED)
                .title("card")
                .relatedGoals(Arrays.asList(atBound, farAway, justBelow))
                .build();
        assertSame(justBelow, ExemplarAttacher.closestRelatedGoal(withNearMiss, best));
    }

    // ---- attach: scope ALL_CARD_TYPES ---------------------------------------

    @Test
    void attachWithAllCardTypesScopeEnrichesUnreachedMethodAndBranchCards() {
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = Properties.LlmExemplarScope.ALL_CARD_TYPES;
        ExemplarAttacher attacher = new ExemplarAttacher();
        GoalDescriptionMapper mapper = new GoalDescriptionMapper();

        TestFitnessFunction unreachedGoal = goal("com.example.Target", "unreached()V");
        TestFitnessFunction branchGoal = goal("com.example.Target", "branch()V");

        TestChromosome closeToUnreached = chromosomeWithStatement(1);
        closeToUnreached.setFitness(unreachedGoal, 0.25);
        TestChromosome farFromUnreached = chromosomeWithStatement(2);
        farFromUnreached.setFitness(unreachedGoal, 9.0);
        TestChromosome closeToBranch = chromosomeWithStatement(3);
        closeToBranch.setFitness(branchGoal, 0.4);
        TestChromosome farFromBranch = chromosomeWithStatement(4);
        farFromBranch.setFitness(branchGoal, 9.0);
        List<TestChromosome> population = Arrays.asList(
                farFromUnreached, closeToUnreached, farFromBranch, closeToBranch);

        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(unreachedGoal, 0.25);
        best.put(branchGoal, 0.4);

        ProblemCard unreachedCard = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("unreached")
                .relatedGoals(Collections.singletonList(unreachedGoal))
                .build();
        ProblemCard branchCard = ProblemCard.builder(ProblemCardType.BRANCH_POLARITY_GAP)
                .title("branch")
                .relatedGoals(Collections.singletonList(branchGoal))
                .build();

        List<ProblemCard> result = attacher.attach(
                Arrays.asList(unreachedCard, branchCard), population, best);

        ProblemCard enrichedUnreached = result.get(0);
        assertEquals(1, enrichedUnreached.getConcreteExamples().size());
        ProblemCard.ConcreteExample unreachedExample = enrichedUnreached.getConcreteExamples().get(0);
        assertSame(closeToUnreached.getTestCase(), unreachedExample.getTestCase());
        assertEquals(
                String.format(Locale.ROOT, ExemplarAttacher.GENERIC_EXEMPLAR_FORMAT,
                        mapper.describe(unreachedGoal), 0.25),
                unreachedExample.getDescription());

        ProblemCard enrichedBranch = result.get(1);
        assertEquals(1, enrichedBranch.getConcreteExamples().size());
        ProblemCard.ConcreteExample branchExample = enrichedBranch.getConcreteExamples().get(0);
        assertSame(closeToBranch.getTestCase(), branchExample.getTestCase());
        assertEquals(
                String.format(Locale.ROOT, ExemplarAttacher.BRANCH_EXEMPLAR_FORMAT,
                        mapper.describe(branchGoal), 0.4),
                branchExample.getDescription());
    }

    @Test
    void attachSetsOperandHintFromTheClosestChromosomesTrace() {
        Properties.LLM_BRANCH_OPERAND_HINTS = true;
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = Properties.LlmExemplarScope.ALL_CARD_TYPES;
        ExemplarAttacher attacher = new ExemplarAttacher();

        Branch branch = mock(Branch.class);
        when(branch.getActualBranchId()).thenReturn(7);
        when(branch.isDefaultCase()).thenReturn(false);
        BranchCoverageTestFitness branchGoal = mock(BranchCoverageTestFitness.class);
        when(branchGoal.getBranch()).thenReturn(branch);
        when(branchGoal.getValue()).thenReturn(true);

        Map<Integer, BranchOperands> operandsByBranch = new HashMap<>();
        operandsByBranch.put(7, BranchOperands.ofInts(50, 0, Opcodes.IF_ICMPLE));
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getLastBranchOperands()).thenReturn(operandsByBranch);
        when(trace.getLastBranchOperands(anyInt())).thenAnswer(inv -> operandsByBranch.get(inv.<Integer>getArgument(0)));
        ExecutionResult result = mock(ExecutionResult.class);
        when(result.getTrace()).thenReturn(trace);

        TestChromosome closest = mock(TestChromosome.class);
        when(closest.getTestCase()).thenReturn(new DefaultTestCase());
        when(closest.getFitnessValues()).thenReturn(Collections.singletonMap(
                (TestFitnessFunction) branchGoal, 0.4));
        when(closest.getLastExecutionResult()).thenReturn(result);

        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(branchGoal, 0.4);
        ProblemCard branchCard = ProblemCard.builder(ProblemCardType.BRANCH_POLARITY_GAP)
                .title("branch")
                .relatedGoals(Collections.singletonList(branchGoal))
                .build();

        List<ProblemCard> result2 = attacher.attach(
                Collections.singletonList(branchCard), Collections.singletonList(closest), best);

        assertEquals("Last evaluation: 50 vs 0 (IF_ICMPLE); need left <= right",
                result2.get(0).getOperandHint());
    }

    // ---- attach: scope BRANCH_POLARITY_GAP ----------------------------------

    @Test
    void attachWithBranchScopeLeavesUnreachedMethodCardUntouchedButEnrichesBranchCard() {
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = Properties.LlmExemplarScope.BRANCH_POLARITY_GAP;
        ExemplarAttacher attacher = new ExemplarAttacher();

        TestFitnessFunction unreachedGoal = goal("com.example.Target", "unreached()V");
        TestFitnessFunction branchGoal = goal("com.example.Target", "branch()V");

        TestChromosome closeToUnreached = chromosomeWithStatement(1);
        closeToUnreached.setFitness(unreachedGoal, 0.25);
        TestChromosome closeToBranch = chromosomeWithStatement(2);
        closeToBranch.setFitness(branchGoal, 0.4);
        List<TestChromosome> population = Arrays.asList(closeToUnreached, closeToBranch);

        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(unreachedGoal, 0.25);
        best.put(branchGoal, 0.4);

        ProblemCard unreachedCard = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("unreached")
                .relatedGoals(Collections.singletonList(unreachedGoal))
                .build();
        ProblemCard branchCard = ProblemCard.builder(ProblemCardType.BRANCH_POLARITY_GAP)
                .title("branch")
                .relatedGoals(Collections.singletonList(branchGoal))
                .build();

        List<ProblemCard> result = attacher.attach(
                Arrays.asList(unreachedCard, branchCard), population, best);

        assertSame(unreachedCard, result.get(0),
                "Out-of-scope card types must be returned unchanged");
        assertTrue(result.get(0).getConcreteExamples().isEmpty());

        assertEquals(1, result.get(1).getConcreteExamples().size());
        assertSame(closeToBranch.getTestCase(), result.get(1).getConcreteExamples().get(0).getTestCase());
    }

    // ---- attach: pass-through / edge cases ----------------------------------

    @Test
    void attachReturnsNullCardsUnchanged() {
        ExemplarAttacher attacher = new ExemplarAttacher();
        assertNull(attacher.attach(null, someNonEmptyPopulation(), someNonEmptyBestMap()));
    }

    @Test
    void attachReturnsEmptyCardsUnchanged() {
        ExemplarAttacher attacher = new ExemplarAttacher();
        List<ProblemCard> emptyCards = Collections.emptyList();
        assertSame(emptyCards, attacher.attach(emptyCards, someNonEmptyPopulation(), someNonEmptyBestMap()));
    }

    @Test
    void attachReturnsCardsUnchangedForEmptyPopulation() {
        ExemplarAttacher attacher = new ExemplarAttacher();
        List<ProblemCard> cards = Collections.singletonList(someCardWithRelatedGoal());
        assertSame(cards, attacher.attach(cards, Collections.emptyList(), someNonEmptyBestMap()));
    }

    @Test
    void attachReturnsCardsUnchangedForEmptyBestFitnessMap() {
        ExemplarAttacher attacher = new ExemplarAttacher();
        List<ProblemCard> cards = Collections.singletonList(someCardWithRelatedGoal());
        assertSame(cards, attacher.attach(cards, someNonEmptyPopulation(), Collections.emptyMap()));
    }

    @Test
    void attachLeavesCardWithOnlyCoveredRelatedGoalsWithoutAnExemplar() {
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = Properties.LlmExemplarScope.ALL_CARD_TYPES;
        ExemplarAttacher attacher = new ExemplarAttacher();

        TestFitnessFunction covered = goal("com.example.Target", "covered()V");
        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(covered, 0.0);

        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("all covered")
                .relatedGoals(Collections.singletonList(covered))
                .build();

        List<ProblemCard> result = attacher.attach(
                Collections.singletonList(card), someNonEmptyPopulation(), best);

        assertSame(card, result.get(0));
        assertTrue(result.get(0).getConcreteExamples().isEmpty());
    }

    @Test
    void attachPassesThroughANullCardEntry() {
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = Properties.LlmExemplarScope.ALL_CARD_TYPES;
        ExemplarAttacher attacher = new ExemplarAttacher();
        List<ProblemCard> cards = new ArrayList<>();
        cards.add(null);
        cards.add(someCardWithRelatedGoal());

        List<ProblemCard> result = attacher.attach(cards, someNonEmptyPopulation(), someNonEmptyBestMap());

        assertNull(result.get(0));
    }

    @Test
    void attachAppendsToExistingConcreteExamplesInsteadOfReplacingThem() {
        Properties.LLM_STAGNATION_EXEMPLAR_SCOPE = Properties.LlmExemplarScope.ALL_CARD_TYPES;
        ExemplarAttacher attacher = new ExemplarAttacher();

        TestFitnessFunction goal = goal("com.example.Target", "grows()V");
        TestChromosome closest = chromosomeWithStatement(1);
        closest.setFitness(goal, 0.2);
        List<TestChromosome> population = Collections.singletonList(closest);
        Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
        best.put(goal, 0.2);

        TestCase preexistingExample = new DefaultTestCase();
        preexistingExample.addStatement(new IntPrimitiveStatement(preexistingExample, 99));
        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("already has one")
                .relatedGoals(Collections.singletonList(goal))
                .concreteExample(preexistingExample, "pre-existing")
                .build();
        assertEquals(1, card.getConcreteExamples().size());

        List<ProblemCard> result = attacher.attach(Collections.singletonList(card), population, best);

        List<ProblemCard.ConcreteExample> examples = result.get(0).getConcreteExamples();
        assertEquals(2, examples.size());
        assertSame(preexistingExample, examples.get(0).getTestCase());
        assertEquals("pre-existing", examples.get(0).getDescription());
        assertSame(closest.getTestCase(), examples.get(1).getTestCase());
    }

    // ---- fixtures ------------------------------------------------------------

    private static TestChromosome chromosomeWithStatement(int value) {
        TestChromosome chromosome = new TestChromosome();
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, value));
        chromosome.setTestCase(testCase);
        return chromosome;
    }

    private static TestChromosome chromosomeWithoutTestCase() {
        TestChromosome chromosome = new TestChromosome();
        try {
            Field testField = AbstractTestChromosome.class.getDeclaredField("test");
            testField.setAccessible(true);
            testField.set(chromosome, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return chromosome;
    }

    private static List<TestChromosome> someNonEmptyPopulation() {
        return Collections.singletonList(chromosomeWithStatement(0));
    }

    private static Map<TestFitnessFunction, Double> someNonEmptyBestMap() {
        Map<TestFitnessFunction, Double> best = new HashMap<>();
        best.put(goal("com.example.Target", "whatever()V"), 0.3);
        return best;
    }

    private static ProblemCard someCardWithRelatedGoal() {
        return ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("some card")
                .relatedGoals(Collections.singletonList(goal("com.example.Target", "someGoal()V")))
                .build();
    }

    private static TestFitnessFunction goal(String targetClass, String targetMethod) {
        return new StubGoal(targetClass, targetMethod);
    }

    /** Minimal goal stub; {@link GoalDescriptionMapper} falls back to {@code toString()} for it. */
    private static final class StubGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String targetClass;
        private final String targetMethod;

        private StubGoal(String targetClass, String targetMethod) {
            this.targetClass = targetClass;
            this.targetMethod = targetMethod;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return 0.0;
        }

        @Override
        public ExecutionResult runTest(TestCase test) {
            return new ExecutionResult(test);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return 0;
        }

        @Override
        public int hashCode() {
            int result = targetClass.hashCode();
            result = 31 * result + targetMethod.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StubGoal)) {
                return false;
            }
            StubGoal that = (StubGoal) other;
            return targetClass.equals(that.targetClass) && targetMethod.equals(that.targetMethod);
        }

        @Override
        public String getTargetClass() {
            return targetClass;
        }

        @Override
        public String getTargetMethod() {
            return targetMethod;
        }

        @Override
        public String toString() {
            return "StubGoal[" + targetClass + "." + targetMethod + "]";
        }
    }
}
