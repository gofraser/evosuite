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

import org.evosuite.Properties;
import org.evosuite.ga.diversity.PopulationSpeciesRecorder.BreedingStats;
import org.evosuite.ga.diversity.PopulationSpeciesRecorder.GenerationSnapshot;
import org.evosuite.testcase.InjectionSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the exact bytes and default file names of the four search sidecar CSVs, so that changes
 * to how they are written cannot silently alter what offline analysis scripts read. The golden
 * files use {@code \n}; the recorders write the platform line separator.
 */
class SidecarRecorderGoldenOutputTest {

    private String savedReportDir;
    private String savedTargetClass;

    @BeforeEach
    void saveProperties() {
        savedReportDir = Properties.REPORT_DIR;
        savedTargetClass = Properties.TARGET_CLASS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.REPORT_DIR = savedReportDir;
        Properties.TARGET_CLASS = savedTargetClass;
    }

    @Test
    void defaultPathsUseReportDirAndSanitizedTargetClass() {
        Properties.REPORT_DIR = "some/report";
        Properties.TARGET_CLASS = "com.example.Outer$Inner<T> x";
        String sep = File.separator;
        String suffix = "_com.example.Outer$Inner_T__x.csv";

        assertEquals("some/report" + sep + "population_species_timeline" + suffix,
                new PopulationSpeciesRecorder().getSidecarPath());
        assertEquals("some/report" + sep + "population_shape" + suffix,
                new PopulationShapeRecorder().getPath());
        assertEquals("some/report" + sep + "fitness_space_snapshots" + suffix,
                new FitnessSpaceSnapshotRecorder().getPath());
        ObjectiveCoverageRecorder objectives = new ObjectiveCoverageRecorder();
        assertEquals("some/report" + sep + "objective_coverage_timeline" + suffix,
                objectives.getTimelinePath());
        assertEquals("some/report" + sep + "objective_index" + suffix,
                objectives.getIndexPath());

        Properties.TARGET_CLASS = "";
        assertEquals("some/report" + sep + "population_shape.csv",
                new PopulationShapeRecorder().getPath());
    }

    @Test
    void populationSpeciesBytes(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested").resolve("species.csv");
        PopulationSpeciesRecorder recorder = new PopulationSpeciesRecorder(file.toString());
        recorder.record(new GenerationSnapshot(
                0, 100L, 4, 2, 1, 1, 0, InjectionSource.LLM_STAGNATION,
                3, InjectionSource.LLM_STAGNATION,
                3, 0, 0, 0,
                3, 7, 1, 0.5, 0.75, 0.3, 0.5,
                1, 2, 1, 3,
                new int[]{0, 0, 1, 1}, new int[]{0, 0, 1, 1},
                new BreedingStats(5, 1, 2, 1, 0, 1, 0, 2, 1, new int[]{0, 3, 1, 2}),
                "CARD_A:2|CARD_B:1", "mut:1;xover:2"));
        recorder.record(new GenerationSnapshot(
                1, 250L, 4, 2, 0, 0, 0, null,
                0, null,
                0, 0, 0, 0,
                4, 6, 2, 0.25, Double.NaN, Double.NaN, 1.0E-7,
                0, 0, 0, 0,
                new int[0], new int[]{0, 1, 1, 2},
                null, null));
        recorder.flush();
        assertGolden("population_species.csv", file);
        recorder.flush();
        assertGolden("population_species.csv", file);
    }

    @Test
    void populationShapeBytes(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested").resolve("shape.csv");
        PopulationShapeRecorder recorder = new PopulationShapeRecorder(file.toString());
        recorder.record(0, 12L, 0, 3, 0, "LLM_ASYNC", 4, 1.5,
                new int[]{1, 4, 9}, new String[]{"a()V", "b(I)V"}, new String[]{"k:CALL", "lit:7"});
        recorder.record(0, 12L, 1, -1, 2, null, 0, Double.NaN,
                new int[0], new String[0], new String[0]);
        recorder.record(5, 900L, 0, 2, 1, null, 2, 0.125,
                new int[]{7}, new String[]{"c()V"}, new String[0]);
        recorder.flush();
        assertGolden("population_shape.csv", file);
    }

    @Test
    void fitnessSpaceBytes(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested").resolve("fitness.csv");
        FitnessSpaceSnapshotRecorder recorder = new FitnessSpaceSnapshotRecorder(file.toString());
        recorder.record(0, Arrays.asList(new double[]{0.0, 1.0, 0.5}, new double[]{Double.NaN, 0.25}),
                Arrays.asList(0, null));
        recorder.record(3, Arrays.asList(new double[]{1.0, 1.0, 1.0E-5}), null);
        recorder.flush();
        assertGolden("fitness_space.csv", file);
    }

    @Test
    void fitnessSpaceEmptyBytes(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("fitness-empty.csv");
        new FitnessSpaceSnapshotRecorder(file.toString()).flush();
        assertGolden("fitness_space_empty.csv", file);
    }

    @Test
    void objectiveCoverageBytes(@TempDir Path tmp) throws IOException {
        Path timeline = tmp.resolve("nested").resolve("timeline.csv");
        Path index = tmp.resolve("nested2").resolve("index.csv");
        ObjectiveCoverageRecorder recorder =
                new ObjectiveCoverageRecorder(timeline.toString(), index.toString());
        recorder.record(0, new double[]{0.0, Double.NaN, 0.75});
        recorder.record(2, new double[]{1.0E-4, 1.0, 0.5});
        recorder.setGoalIndex(
                new String[]{"com.example.Foo", "com.example.Foo", null},
                new String[]{"bar(II)V", "baz(Ljava/lang/String;)V", "m"},
                new String[]{"Branch 1 IFEQ L12 - true", "a,b \"quoted\"", null});
        recorder.flush();
        assertGolden("objective_timeline.csv", timeline);
        assertGolden("objective_index.csv", index);
    }

    @Test
    void objectiveCoverageWithoutIndexWritesNoIndexFile(@TempDir Path tmp) throws IOException {
        Path timeline = tmp.resolve("timeline.csv");
        Path index = tmp.resolve("index.csv");
        ObjectiveCoverageRecorder recorder =
                new ObjectiveCoverageRecorder(timeline.toString(), index.toString());
        recorder.flush();
        assertGolden("objective_timeline_empty.csv", timeline);
        assertFalse(Files.exists(index));
    }

    private static void assertGolden(String goldenName, Path actualFile) throws IOException {
        String resource = "sidecar-golden/" + goldenName;
        String expected;
        try (InputStream in = SidecarRecorderGoldenOutputTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing golden resource " + resource);
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\n", System.lineSeparator());
        }
        String actual = new String(Files.readAllBytes(actualFile), StandardCharsets.UTF_8);
        assertEquals(expected, actual, goldenName);
    }
}
