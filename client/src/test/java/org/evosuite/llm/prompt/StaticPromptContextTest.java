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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link StaticPromptContext} entries defensively copy the
 * caller-supplied collections they are constructed with: these entries are
 * memoised and shared across every prompt (and thread) in a run, so a caller
 * mutating its own collection afterwards must not affect what other prompts see.
 */
class StaticPromptContextTest {

    @Test
    void sutEntryReminderTextsIsUnaffectedByLaterMutationOfInputList() {
        List<String> reminders = new ArrayList<>();
        reminders.add("first reminder");
        StaticPromptContext.SutEntry entry = new StaticPromptContext.SutEntry(
                "section", reminders, Properties.LlmSutContextMode.SIGNATURE_ONLY,
                false, false, false, false);

        reminders.add("mutated after construction");

        assertEquals(1, entry.getReminderTexts().size());
        assertEquals("first reminder", entry.getReminderTexts().get(0));
    }

    @Test
    void sutEntryReminderTextsGetterIsUnmodifiable() {
        StaticPromptContext.SutEntry entry = new StaticPromptContext.SutEntry(
                "section", new ArrayList<>(), Properties.LlmSutContextMode.SIGNATURE_ONLY,
                false, false, false, false);

        assertThrows(UnsupportedOperationException.class, () -> entry.getReminderTexts().add("x"));
    }

    @Test
    void dependencyEntryClusterClassNamesAtRenderIsUnaffectedByLaterMutationOfInputSet() {
        Set<String> names = new TreeSet<>();
        names.add("com.example.Foo");
        StaticPromptContext.DependencyEntry entry = new StaticPromptContext.DependencyEntry(
                "section", false, 0, "none", null, names);

        names.add("com.example.MutatedAfterConstruction");

        assertEquals(1, entry.getClusterClassNamesAtRender().size());
        assertFalse(entry.getClusterClassNamesAtRender().contains("com.example.MutatedAfterConstruction"));
    }

    @Test
    void dependencyEntryClusterClassNamesAtRenderGetterIsUnmodifiable() {
        StaticPromptContext.DependencyEntry entry = new StaticPromptContext.DependencyEntry(
                "section", false, 0, "none", null, new TreeSet<>());

        assertThrows(UnsupportedOperationException.class,
                () -> entry.getClusterClassNamesAtRender().add("com.example.Foo"));
    }
}
