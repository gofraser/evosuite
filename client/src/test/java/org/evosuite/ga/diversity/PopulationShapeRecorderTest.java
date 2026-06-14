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
package org.evosuite.ga.diversity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CSV formatting and on-disk write behavior of
 * {@link PopulationShapeRecorder}.
 */
class PopulationShapeRecorderTest {

    private static final String HEADER =
            "gen,elapsed_ms,idx,species_id,rank,injection_source,covered_goals,sum_fitness,branches,method_sigs,struct_features";

    @Test
    void recordsAndFlushesRows(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("shape.csv");
        PopulationShapeRecorder recorder = new PopulationShapeRecorder(file.toString());

        recorder.record(0, 0L, 0, 3, 0, null, 2, 4.5, new int[] {1, 2, 3},
                new String[] {"Foo.bar()V", "Foo.baz(I)I"},
                new String[] {"C:Foo()V", "M:Foo.bar()V", "V:int=3"});
        recorder.record(0, 0L, 1, 3, 1, "LLM_ASYNC", 1, 6.0, new int[] {1, 3},
                new String[] {"Foo.bar()V"}, new String[] {"C:Foo()V"});
        recorder.record(5, 5012L, 0, -1, -1, null, 0, Double.NaN, new int[0],
                new String[0], new String[0]);
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals(HEADER, lines.get(0));
        assertEquals(4, lines.size(), "header + 3 rows");
        assertEquals("0,0,0,3,0,,2,4.5,1;2;3,Foo.bar()V|Foo.baz(I)I,C:Foo()V|M:Foo.bar()V|V:int=3",
                lines.get(1));
        assertEquals("0,0,1,3,1,LLM_ASYNC,1,6.0,1;3,Foo.bar()V,C:Foo()V", lines.get(2));
        assertEquals("5,5012,0,-1,-1,,0,,,,", lines.get(3));
        assertEquals(3, recorder.size());
    }

    @Test
    void flushWithNoRowsWritesHeaderOnly(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("shape.csv");
        PopulationShapeRecorder recorder = new PopulationShapeRecorder(file.toString());
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertEquals(HEADER, lines.get(0));
    }

    @Test
    void flushIsIdempotent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("shape.csv");
        PopulationShapeRecorder recorder = new PopulationShapeRecorder(file.toString());
        recorder.record(0, 0L, 0, 0, 0, null, 1, 2.0, new int[] {7},
                new String[] {"Foo.bar()V"}, new String[] {"M:Foo.bar()V"});

        recorder.flush();
        long sizeAfterFirst = Files.size(file);
        recorder.flush();
        assertEquals(sizeAfterFirst, Files.size(file),
                "Re-flushing the same rows should produce identical output");
        assertEquals(2, Files.readAllLines(file).size(), "header + 1 row");
    }
}
