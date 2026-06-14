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
 * {@link ObjectiveCoverageRecorder}.
 */
class ObjectiveCoverageRecorderTest {

    @Test
    void flushWritesTimelineAndIndex(@TempDir Path tmp) throws IOException {
        Path timeline = tmp.resolve("timeline.csv");
        Path index = tmp.resolve("index.csv");
        ObjectiveCoverageRecorder recorder =
                new ObjectiveCoverageRecorder(timeline.toString(), index.toString());

        recorder.record(0, new double[] {1.0, 0.5, Double.NaN});
        recorder.record(1, new double[] {0.5, 0.0, 0.2});
        recorder.setGoalIndex(
                new String[] {"com.example.Foo", "com.example.Foo", "com.example.Foo"},
                new String[] {"bar", "bar", "baz"},
                new String[] {"com.example.Foo.bar: root-Branch 1 - true",
                               "com.example.Foo.bar: root-Branch 1 - false",
                               "com.example.Foo.baz: root-Branch 2 - true"});
        recorder.flush();

        List<String> timelineLines = Files.readAllLines(timeline);
        assertEquals("gen,goal_id,best_fitness", timelineLines.get(0));
        // gen 0: goal 2 is NaN -> skipped (2 rows); gen 1: all 3 goals (3 rows)
        assertEquals(6, timelineLines.size(), "header + 5 rows");
        assertEquals("0,0,1.0", timelineLines.get(1));
        assertEquals("0,1,0.5", timelineLines.get(2));
        assertEquals("1,0,0.5", timelineLines.get(3));
        assertEquals("1,1,0.0", timelineLines.get(4));
        assertEquals("1,2,0.2", timelineLines.get(5));

        List<String> indexLines = Files.readAllLines(index);
        assertEquals("goal_id,class_name,method_name,description", indexLines.get(0));
        assertEquals(4, indexLines.size(), "header + 3 goals");
        assertEquals("0,com.example.Foo,bar,com.example.Foo.bar: root-Branch 1 - true", indexLines.get(1));
        assertEquals("2,com.example.Foo,baz,com.example.Foo.baz: root-Branch 2 - true", indexLines.get(3));
    }

    @Test
    void flushIsIdempotent(@TempDir Path tmp) throws IOException {
        Path timeline = tmp.resolve("timeline.csv");
        Path index = tmp.resolve("index.csv");
        ObjectiveCoverageRecorder recorder =
                new ObjectiveCoverageRecorder(timeline.toString(), index.toString());
        recorder.record(0, new double[] {0.5});
        recorder.setGoalIndex(new String[] {"C"}, new String[] {"m"}, new String[] {"C.m: d"});

        recorder.flush();
        long sizeAfterFirst = Files.size(timeline);
        recorder.flush();
        assertEquals(sizeAfterFirst, Files.size(timeline),
                "Re-flushing the same rows should produce identical output");
    }

    @Test
    void flushWithoutGoalIndexSkipsIndexFile(@TempDir Path tmp) throws IOException {
        Path timeline = tmp.resolve("timeline.csv");
        Path index = tmp.resolve("index.csv");
        ObjectiveCoverageRecorder recorder =
                new ObjectiveCoverageRecorder(timeline.toString(), index.toString());
        recorder.record(0, new double[] {0.3});
        recorder.flush();

        assertTrue(Files.exists(timeline));
        assertFalse(Files.exists(index),
                "index file should not be created when setGoalIndex was never called");
    }
}
