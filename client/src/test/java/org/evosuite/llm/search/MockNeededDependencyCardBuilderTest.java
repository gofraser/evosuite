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

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.Statement;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockNeededDependencyCardBuilderTest {

    interface Handler {
        void handle();
    }

    static final class HandlerImpl implements Handler {
        @Override
        public void handle() {
        }
    }

    /** Concrete type whose only constructor transitively needs an unconstructable {@link Handler}. */
    static final class NeedsHandler {
        NeedsHandler(Handler handler) {
        }
    }

    /** Concrete type with an implicit no-arg constructor: trivially constructible. */
    static final class Plain {
    }

    @Test
    void firesForDirectInterfaceParameterNeverMaterialized() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Service",
                "run(" + Type.getDescriptor(Handler.class) + ")V");

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.<TestChromosome>emptyList(), 10);

        ProblemCard card = findCard(cards);
        assertNotNull(card, "Expected MOCK_NEEDED_DEPENDENCY for an unsatisfied interface parameter");
        assertEquals(ProblemCardFamily.STRUCTURAL, card.getFamily());
        assertTrue(card.getTitle().contains(Handler.class.getName()),
                "Card title should name the missing collaborator: " + card.getTitle());
    }

    @Test
    void suppressedWhenCollaboratorWasMaterialized() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Service",
                "run(" + Type.getDescriptor(Handler.class) + ")V");

        // A concrete implementation of Handler was produced somewhere in the population.
        TestChromosome supplied = chromosomeReturning(HandlerImpl.class);

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(supplied), 10);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.MOCK_NEEDED_DEPENDENCY),
                "A materialized implementation/mock of the collaborator must suppress the card");
    }

    @Test
    void firesForTransitiveConcreteParameterAndNamesTheLeafCollaborator() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Service",
                "run(" + Type.getDescriptor(NeedsHandler.class) + ")V");

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.<TestChromosome>emptyList(), 10);

        ProblemCard card = findCard(cards);
        assertNotNull(card, "Expected MOCK_NEEDED_DEPENDENCY for a transitive construction barrier");
        assertTrue(card.getTitle().contains(Handler.class.getName()),
                "Indirect card should name the deepest blocking collaborator (Handler): " + card.getTitle());
        assertTrue(card.getEvidence().stream().anyMatch(e -> e.contains(NeedsHandler.class.getName())),
                "Evidence should mention the concrete intermediary that needs the collaborator");
    }

    @Test
    void doesNotFireForConstructibleConcreteParameter() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Service",
                "run(" + Type.getDescriptor(Plain.class) + ")V");

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.<TestChromosome>emptyList(), 10);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.MOCK_NEEDED_DEPENDENCY),
                "A concrete parameter with a no-arg constructor must not raise a mock-needed barrier");
    }

    @Test
    void ignoresPrimitiveAndCommonValueParameters() {
        ProblemCardExtractor extractor = new ProblemCardExtractor(ExtractorTraceSink.NOOP);
        TestFitnessFunction goal = goal("com.example.Service", "run(ILjava/lang/String;)V");

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.<TestChromosome>emptyList(), 10);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.MOCK_NEEDED_DEPENDENCY),
                "Primitive and String parameters are handled by ordinary seeding, not mock cards");
    }

    private static ProblemCard findCard(List<ProblemCard> cards) {
        for (ProblemCard card : cards) {
            if (card.getType() == ProblemCardType.MOCK_NEEDED_DEPENDENCY) {
                return card;
            }
        }
        return null;
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static TestFitnessFunction goal(String className, String methodName) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn(methodName);
        when(goal.toString()).thenReturn(className + "." + methodName);
        return goal;
    }

    private static TestChromosome chromosomeReturning(Class<?>... returnClasses) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        List<Statement> statements = new ArrayList<>();
        for (Class<?> returnClass : returnClasses) {
            Statement statement = mock(Statement.class);
            when(statement.getReturnClass()).thenReturn((Class) returnClass);
            statements.add(statement);
        }
        when(testCase.size()).thenReturn(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            when(testCase.hasStatement(i)).thenReturn(true);
            when(testCase.getStatement(i)).thenReturn(statements.get(i));
        }
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }
}
