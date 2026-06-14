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

import org.evosuite.testcase.InjectionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CSV formatting and on-disk write behavior of
 * {@link ProblemCardLogRecorder} (constructed with an explicit path, not the
 * singleton).
 */
class ProblemCardLogRecorderTest {

    private static final String HEADER =
            "event,attempt_id,gen,elapsed_ms,source,card_type,root_cause_key,scope_key,priority,goals";

    @Test
    void recordsSelectedAndCoveredRows(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cards.csv");
        ProblemCardLogRecorder recorder = new ProblemCardLogRecorder(file.toString());

        recorder.recordSelectedRow("A1", InjectionSource.LLM_STAGNATION,
                "BRANCH_POLARITY", "root1", "scope1", 0.75,
                Arrays.asList("Foo.bar()V: Branch 1 - true", "Foo.baz(I, J)V: Branch 2 - false"));
        recorder.recordSelectedRow("A2", InjectionSource.LLM_ASYNC,
                "ENVIRONMENT_BARRIER", "", "", 0.5,
                Collections.singletonList("Foo.qux()V: Branch 3 - true"));
        recorder.recordCoveredRow("A1", InjectionSource.LLM_STAGNATION, 7,
                Collections.singletonList("Foo.bar()V: Branch 1 - true"));
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals(HEADER, lines.get(0));
        assertEquals(4, lines.size(), "header + 3 rows");

        // Row 1: goals cell contains a comma (descriptor "(I, J)") -> CSV-quoted.
        assertTrue(lines.get(1).startsWith("SELECTED,A1,-1,"), lines.get(1));
        assertTrue(lines.get(1).contains(",LLM_STAGNATION,BRANCH_POLARITY,root1,scope1,0.75,"),
                lines.get(1));
        assertTrue(lines.get(1).endsWith(
                        "\"Foo.bar()V: Branch 1 - true|Foo.baz(I, J)V: Branch 2 - false\""),
                lines.get(1));

        assertTrue(lines.get(2).startsWith("SELECTED,A2,-1,"), lines.get(2));
        assertTrue(lines.get(2).contains(",LLM_ASYNC,ENVIRONMENT_BARRIER,,,0.5,"), lines.get(2));
        assertTrue(lines.get(2).endsWith(",Foo.qux()V: Branch 3 - true"), lines.get(2));

        assertTrue(lines.get(3).startsWith("COVERED_BY_INJECTION,A1,7,"), lines.get(3));
        assertTrue(lines.get(3).contains(",LLM_STAGNATION,,,,,"), lines.get(3));
        assertTrue(lines.get(3).endsWith(",Foo.bar()V: Branch 1 - true"), lines.get(3));
        assertEquals(3, recorder.size());
    }

    @Test
    void flushWithNoRowsWritesHeaderOnly(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cards.csv");
        ProblemCardLogRecorder recorder = new ProblemCardLogRecorder(file.toString());
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertEquals(HEADER, lines.get(0));
    }

    @Test
    void flushIsIdempotent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cards.csv");
        ProblemCardLogRecorder recorder = new ProblemCardLogRecorder(file.toString());
        recorder.recordSelectedRow("A1", InjectionSource.LLM_STAGNATION,
                "TYPE_BARRIER", "r", "s", 1.0,
                Collections.singletonList("Foo.bar()V: Branch 1 - true"));

        recorder.flush();
        long sizeAfterFirst = Files.size(file);
        recorder.flush();
        assertEquals(sizeAfterFirst, Files.size(file),
                "Re-flushing the same rows should produce identical output");
    }

    @Test
    void encodeGoalsReplacesPipesAndSkipsEmpties() {
        assertEquals("a_b|c", ProblemCardLogRecorder.encodeGoals(
                Arrays.asList("a|b", "", null, "c")));
        assertEquals("", ProblemCardLogRecorder.encodeGoals(Collections.emptyList()));
        assertEquals("", ProblemCardLogRecorder.encodeGoals(null));
    }
}
