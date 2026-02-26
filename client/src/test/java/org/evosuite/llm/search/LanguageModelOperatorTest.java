package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LanguageModelMutation and LanguageModelCrossover operator wrappers.
 */
class LanguageModelOperatorTest {

    private boolean prevEnabled;
    private double prevMutProb;
    private double prevCrossProb;

    @BeforeEach
    void saveProperties() {
        prevEnabled = Properties.LLM_OPERATOR_ENABLED;
        prevMutProb = Properties.LLM_MUTATION_PROBABILITY;
        prevCrossProb = Properties.LLM_CROSSOVER_PROBABILITY;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_ENABLED = prevEnabled;
        Properties.LLM_MUTATION_PROBABILITY = prevMutProb;
        Properties.LLM_CROSSOVER_PROBABILITY = prevCrossProb;
    }

    @Test
    void mutationDisabledReturnsFalse() {
        Properties.LLM_OPERATOR_ENABLED = false;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        TestChromosome tc = makeChromosome();
        assertFalse(mutation.tryMutate(tc, Collections.emptySet()));
        assertEquals(0, mutation.getAppliedCount());
        verifyNoInteractions(mockSemantic);
    }

    @Test
    void mutationSuccessReplacesTestCase() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_MUTATION_PROBABILITY = 1.0; // always try

        TestChromosome original = makeChromosome();
        TestChromosome mutant = makeChromosome();
        mutant.setTestCase(new DefaultTestCase()); // different test case

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutateSemantically(eq(original), anyCollection()))
                .thenReturn(mutant);

        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);
        boolean result = mutation.tryMutate(original, Collections.emptySet());

        assertTrue(result);
        assertEquals(1, mutation.getAppliedCount());
        assertEquals(0, mutation.getFallbackCount());
    }

    @Test
    void mutationFailureFallsBack() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutateSemantically(any(), anyCollection())).thenReturn(null);

        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);
        TestChromosome tc = makeChromosome();
        boolean result = mutation.tryMutate(tc, Collections.emptySet());

        assertFalse(result);
        assertEquals(0, mutation.getAppliedCount());
        assertEquals(1, mutation.getFallbackCount());
    }

    @Test
    void mutationExceptionFallsBackGracefully() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutateSemantically(any(), anyCollection()))
                .thenThrow(new RuntimeException("boom"));

        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);
        TestChromosome tc = makeChromosome();
        boolean result = mutation.tryMutate(tc, Collections.emptySet());

        assertFalse(result);
        assertEquals(1, mutation.getFallbackCount());
    }

    @Test
    void crossoverDisabledReturnsFalse() {
        Properties.LLM_OPERATOR_ENABLED = false;
        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        LanguageModelCrossover crossover = new LanguageModelCrossover(mockSemantic);

        assertFalse(crossover.tryCrossover(makeChromosome(), makeChromosome(), Collections.emptySet()));
        verifyNoInteractions(mockSemantic);
    }

    @Test
    void crossoverSuccessReplacesOffspring() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;

        TestChromosome offspring1 = makeChromosome();
        TestChromosome offspring2 = makeChromosome();
        TestChromosome child = makeChromosome();
        child.setTestCase(new DefaultTestCase());

        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        when(mockSemantic.crossoverSemantically(eq(offspring1), eq(offspring2), anyCollection()))
                .thenReturn(child);

        LanguageModelCrossover crossover = new LanguageModelCrossover(mockSemantic);
        boolean result = crossover.tryCrossover(offspring1, offspring2, Collections.emptySet());

        assertTrue(result);
        assertEquals(1, crossover.getAppliedCount());
        assertEquals(0, crossover.getFallbackCount());
    }

    @Test
    void crossoverFailureFallsBack() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;

        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        when(mockSemantic.crossoverSemantically(any(), any(), anyCollection())).thenReturn(null);

        LanguageModelCrossover crossover = new LanguageModelCrossover(mockSemantic);
        boolean result = crossover.tryCrossover(makeChromosome(), makeChromosome(), Collections.emptySet());

        assertFalse(result);
        assertEquals(1, crossover.getFallbackCount());
    }

    @Test
    void crossoverExceptionFallsBackGracefully() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;

        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        when(mockSemantic.crossoverSemantically(any(), any(), anyCollection()))
                .thenThrow(new RuntimeException("oops"));

        LanguageModelCrossover crossover = new LanguageModelCrossover(mockSemantic);
        boolean result = crossover.tryCrossover(makeChromosome(), makeChromosome(), Collections.emptySet());

        assertFalse(result);
        assertEquals(1, crossover.getFallbackCount());
    }

    @Test
    void zeroProbabilityNeverTriesLlm() {
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_MUTATION_PROBABILITY = 0.0;
        Properties.LLM_CROSSOVER_PROBABILITY = 0.0;

        LlmSemanticMutation mockMut = mock(LlmSemanticMutation.class);
        LlmSemanticCrossover mockCross = mock(LlmSemanticCrossover.class);

        LanguageModelMutation mutation = new LanguageModelMutation(mockMut);
        LanguageModelCrossover crossover = new LanguageModelCrossover(mockCross);

        for (int i = 0; i < 100; i++) {
            assertFalse(mutation.tryMutate(makeChromosome(), Collections.emptySet()));
            assertFalse(crossover.tryCrossover(
                    makeChromosome(), makeChromosome(), Collections.emptySet()));
        }
        verifyNoInteractions(mockMut);
        verifyNoInteractions(mockCross);
    }

    private TestChromosome makeChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }
}
