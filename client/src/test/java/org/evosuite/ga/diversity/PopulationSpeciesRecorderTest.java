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

import org.evosuite.ga.diversity.PopulationSpeciesRecorder.GenerationSnapshot;
import org.evosuite.testcase.InjectionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CSV header/row formatting and on-disk write behavior of
 * {@link PopulationSpeciesRecorder}.
 */
class PopulationSpeciesRecorderTest {

    @Test
    void flushWritesHeaderAndRows(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(PopulationSpeciesRecorder.FILENAME);
        PopulationSpeciesRecorder recorder = new PopulationSpeciesRecorder(file.toString());

        recorder.record(new GenerationSnapshot(
                0, 100L, 4, 2, 1, 1, 0, InjectionSource.LLM_STAGNATION,
                3, InjectionSource.LLM_STAGNATION,
                3, 0, 0, 0,
                3, 7, 1, 0.5, 0.75, 0.3, 0.5,
                1, 2, 1, 3,
                new int[]{0, 0, 1, 1}, new int[]{0, 0, 1, 1}));
        recorder.record(new GenerationSnapshot(
                1, 250L, 4, 2, 0, 0, 0, null,
                0, null,
                0, 0, 0, 0,
                4, 6, 2, 0.25, 0.4, Double.NaN, 0.5,
                0, 0, 0, 0,
                new int[]{0, 0, 1, 2}, new int[]{0, 1, 1, 2}));
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "header + 2 rows");

        assertTrue(lines.get(0).startsWith("gen,elapsed_ms"), "header present");

        String row0 = lines.get(1);
        // gen,...,injection_source,n_injected_attempts,injection_attempt_source,<per-source-attempts>,...
        assertTrue(row0.startsWith("0,100,4,2,1,1,0,LLM_STAGNATION,3,LLM_STAGNATION,3,0,0,0,"),
                "row 0 metadata prefix mismatch: " + row0);
        assertTrue(row0.contains(",0.5,1,2,1,3,"),
                "row 0 should contain protection counters: " + row0);
        assertTrue(row0.endsWith(",0;0;1;1,0;0;1;1"),
                "row 0 per-slot suffix mismatch: " + row0);

        String row1 = lines.get(2);
        // injection_source / injection_attempt_source empty; diversity NaN → empty
        assertTrue(row1.startsWith("1,250,4,2,0,0,0,,0,,0,0,0,0,"),
                "row 1 should have empty injection_source: " + row1);
        assertTrue(row1.contains(",,0.5,"),
                "row 1 should have empty diversity field: " + row1);
        assertTrue(row1.endsWith(",0;0;1;2,0;1;1;2"),
                "row 1 per-slot suffix mismatch: " + row1);
    }

    @Test
    void flushIsIdempotent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("idem.csv");
        PopulationSpeciesRecorder recorder = new PopulationSpeciesRecorder(file.toString());
        recorder.record(new GenerationSnapshot(
                0, 0L, 1, 1, 0, 0, 0, null, 0, null, 0, 0, 0, 0, 0, 1, 1, 0.0, 0.0, 0.0, 1.0,
                0, 0, 0, 0,
                new int[]{0}, new int[]{0}));

        recorder.flush();
        long sizeAfterFirst = Files.size(file);
        recorder.flush();
        long sizeAfterSecond = Files.size(file);

        assertEquals(sizeAfterFirst, sizeAfterSecond,
                "Re-flushing the same snapshots should produce identical output");
    }
}
