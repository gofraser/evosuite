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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CSV formatting and on-disk write behavior of
 * {@link FitnessSpaceSnapshotRecorder}.
 */
class FitnessSpaceSnapshotRecorderTest {

    @Test
    void flushWritesHeaderAndRows(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("snapshots.csv");
        FitnessSpaceSnapshotRecorder recorder = new FitnessSpaceSnapshotRecorder(file.toString());

        recorder.record(0, Arrays.asList(
                        new double[] {1.0, 0.5, Double.NaN},
                        new double[] {0.8, 0.2, 0.9}),
                Arrays.asList(0, 1));
        recorder.record(10, Arrays.asList(
                        new double[] {0.0, 0.0, 0.1}),
                Arrays.asList(0));
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals("gen,individual_id,rank,goal_0,goal_1,goal_2", lines.get(0));
        assertEquals(4, lines.size(), "header + 3 rows");
        assertEquals("0,0,0,1.0,0.5,", lines.get(1));
        assertEquals("0,1,1,0.8,0.2,0.9", lines.get(2));
        assertEquals("10,0,0,0.0,0.0,0.1", lines.get(3));
    }

    @Test
    void flushIsIdempotent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("snapshots.csv");
        FitnessSpaceSnapshotRecorder recorder = new FitnessSpaceSnapshotRecorder(file.toString());
        recorder.record(0, Arrays.asList(new double[] {0.5, 0.5}), Arrays.asList(0));

        recorder.flush();
        long sizeAfterFirst = Files.size(file);
        recorder.flush();
        assertEquals(sizeAfterFirst, Files.size(file),
                "Re-flushing the same rows should produce identical output");
    }

    @Test
    void flushWithNoRowsWritesHeaderOnly(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("snapshots.csv");
        FitnessSpaceSnapshotRecorder recorder = new FitnessSpaceSnapshotRecorder(file.toString());
        recorder.flush();

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertEquals("gen,individual_id,rank", lines.get(0));
    }
}
