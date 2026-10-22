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
package org.evosuite.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** The question about existing suites, and the dialog that asks it. */
public class ExistingSuitesQuestionTest {

    @Test
    public void asksAboutOneSuiteAndPreselectsAdd() {
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(List.of("Calculator"), 1, "src/test/evosuite",
                ExistingSuitesQuestion.Scope.CLASSES);

        assertEquals("Existing Test Suite", question.title);
        assertTrue(question.message.startsWith("Calculator already has a test suite in src/test/evosuite.\n\n"),
                question.message);
        assertTrue(question.message.contains(
                        "Replace: overwrite it with the new suite, including any manual changes."), question.message);
        assertFalse(question.message.contains("choice applies"), question.message);
        assertEquals(SuiteAction.ADD, question.defaultAction);
        assertEquals(1, question.defaultButton());
    }

    @Test
    public void asksOnceForAllClassesAndAbbreviatesLongLists() {
        List<String> classes = List.of("A", "B", "C", "D", "E", "F", "G");
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(classes, 9, null, ExistingSuitesQuestion.Scope.CLASSES);

        assertEquals("Existing Test Suites", question.title);
        assertTrue(question.message.startsWith("7 of the 9 classes already have test suites: A, B, C, D and 3 more."),
                question.message);
        assertTrue(question.message.endsWith("The choice applies to all 7; the other classes get new suites."),
                question.message);
    }

    @Test
    public void preselectsAddingForMethodsAndSaysWhy() {
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(List.of("Calculator"), 1, "src/test/evosuite",
                ExistingSuitesQuestion.Scope.METHODS);

        assertEquals(SuiteAction.ADD, question.defaultAction);
        assertEquals(1, question.defaultButton());
        assertTrue(question.message.contains("only exercise the selected methods"), question.message);
    }

    @Test
    public void preselectsAddingWhenExtendingTestsAndSaysWhy() {
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(List.of("Calculator"), 1, "src/test/evosuite",
                ExistingSuitesQuestion.Scope.EXTENSION);

        assertEquals(SuiteAction.ADD, question.defaultAction);
        assertTrue(question.message.contains("only cover what the existing tests leave uncovered"), question.message);
        assertFalse(question.message.contains("selected methods"), question.message);
    }

    @Test
    public void mapsButtonsToActions() {
        assertEquals(SuiteAction.REPLACE, ExistingSuitesQuestion.action(0));
        assertEquals(SuiteAction.ADD, ExistingSuitesQuestion.action(1));
        assertEquals(SuiteAction.COMPARE, ExistingSuitesQuestion.action(2));
        assertNull(ExistingSuitesQuestion.action(3), "Cancel");
        assertNull(ExistingSuitesQuestion.action(-1), "closed with Escape");
    }

}
