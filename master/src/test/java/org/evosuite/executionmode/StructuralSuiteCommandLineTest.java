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
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.executionmode;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuralSuiteCommandLineTest {

    private String exportPath;
    private String replayPath;
    private Properties.OracleReplayStrategy replayStrategy;
    private int parallelClients;

    @BeforeEach
    void saveProperties() {
        exportPath = Properties.STRUCTURAL_SUITE_EXPORT;
        replayPath = Properties.ORACLE_REPLAY_INPUT;
        replayStrategy = Properties.ORACLE_REPLAY_STRATEGY;
        parallelClients = Properties.NUM_PARALLEL_CLIENTS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.STRUCTURAL_SUITE_EXPORT = exportPath;
        Properties.ORACLE_REPLAY_INPUT = replayPath;
        Properties.ORACLE_REPLAY_STRATEGY = replayStrategy;
        Properties.NUM_PARALLEL_CLIENTS = parallelClients;
    }

    @Test
    void configuresExportAsAbsoluteClientProperty() throws Exception {
        CommandLine line = parse("-class", "example.Target",
                "-exportStructuralSuite", "artifacts/target.structural");
        List<String> javaOptions = new ArrayList<>();

        assertTrue(TestGeneration.configureStructuralSuiteMode(line, javaOptions));
        String expected = new File("artifacts/target.structural").getAbsolutePath();
        assertEquals(expected, Properties.STRUCTURAL_SUITE_EXPORT);
        assertTrue(javaOptions.contains("-Dstructural_suite_export=" + expected));
    }

    @Test
    void configuresCaseInsensitiveReplayStrategy() throws Exception {
        CommandLine line = parse("-class", "example.Target", "-replayOracle", "suite.bin",
                "-oracleStrategy", "mutation");
        List<String> javaOptions = new ArrayList<>();

        assertTrue(TestGeneration.configureStructuralSuiteMode(line, javaOptions));
        assertEquals(Properties.OracleReplayStrategy.MUTATION,
                Properties.ORACLE_REPLAY_STRATEGY);
        assertTrue(javaOptions.contains("-Doracle_replay_strategy=MUTATION"));
    }

    @Test
    void rejectsAmbiguousOrNonClassModes() throws Exception {
        assertFalse(TestGeneration.configureStructuralSuiteMode(
                parse("-class", "example.Target", "-exportStructuralSuite", "out",
                        "-replayOracle", "in"), new ArrayList<>()));
        assertFalse(TestGeneration.configureStructuralSuiteMode(
                parse("-exportStructuralSuite", "out"), new ArrayList<>()));
    }

    @Test
    void rejectsInvalidReplayStrategy() throws Exception {
        assertFalse(TestGeneration.configureStructuralSuiteMode(
                parse("-class", "example.Target", "-replayOracle", "in",
                        "-oracleStrategy", "unknown"), new ArrayList<>()));
    }

    @Test
    void requiresAnExplicitReplayStrategy() throws Exception {
        assertFalse(TestGeneration.configureStructuralSuiteMode(
                parse("-class", "example.Target", "-replayOracle", "in"),
                new ArrayList<>()));
    }

    @Test
    void rejectsParallelReplayWriters() throws Exception {
        Properties.NUM_PARALLEL_CLIENTS = 2;
        assertFalse(TestGeneration.configureStructuralSuiteMode(
                parse("-class", "example.Target", "-replayOracle", "in",
                        "-oracleStrategy", "ALL"), new ArrayList<>()));
    }

    private static CommandLine parse(String... arguments) throws Exception {
        Options options = new Options();
        options.addOption(new Option("class", true, "target class"));
        for (Option option : TestGeneration.getOptions()) {
            options.addOption(option);
        }
        return new DefaultParser().parse(options, arguments);
    }
}
