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
import org.evosuite.TimeController;
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
    private boolean assertions;
    private Properties.AssertionStrategy assertionStrategy;
    private boolean llmPostProcessingEnabled;
    private boolean llmPostProcessingAssertions;
    private boolean llmPostProcessingTestNames;
    private boolean llmPostProcessingVariableNames;
    private boolean llmPostProcessingComments;
    private boolean llmPostProcessingSectionBreaks;
    private Properties.LlmPostProcessingAssertionFallback llmAssertionFallback;
    private Properties.LlmProvider llmProvider;
    private int llmPostProcessingTimeout;

    @BeforeEach
    void saveProperties() {
        exportPath = Properties.STRUCTURAL_SUITE_EXPORT;
        replayPath = Properties.ORACLE_REPLAY_INPUT;
        replayStrategy = Properties.ORACLE_REPLAY_STRATEGY;
        parallelClients = Properties.NUM_PARALLEL_CLIENTS;
        assertions = Properties.ASSERTIONS;
        assertionStrategy = Properties.ASSERTION_STRATEGY;
        llmPostProcessingEnabled = Properties.LLM_POSTPROCESSING_ENABLED;
        llmPostProcessingAssertions = Properties.LLM_POSTPROCESSING_ASSERTIONS;
        llmPostProcessingTestNames = Properties.LLM_POSTPROCESSING_TEST_NAMES;
        llmPostProcessingVariableNames = Properties.LLM_POSTPROCESSING_VARIABLE_NAMES;
        llmPostProcessingComments = Properties.LLM_POSTPROCESSING_COMMENTS;
        llmPostProcessingSectionBreaks = Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
        llmAssertionFallback = Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK;
        llmProvider = Properties.LLM_PROVIDER;
        llmPostProcessingTimeout = Properties.LLM_POSTPROCESSING_TIMEOUT;
    }

    @AfterEach
    void restoreProperties() {
        Properties.STRUCTURAL_SUITE_EXPORT = exportPath;
        Properties.ORACLE_REPLAY_INPUT = replayPath;
        Properties.ORACLE_REPLAY_STRATEGY = replayStrategy;
        Properties.NUM_PARALLEL_CLIENTS = parallelClients;
        Properties.ASSERTIONS = assertions;
        Properties.ASSERTION_STRATEGY = assertionStrategy;
        Properties.LLM_POSTPROCESSING_ENABLED = llmPostProcessingEnabled;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = llmPostProcessingAssertions;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = llmPostProcessingTestNames;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = llmPostProcessingVariableNames;
        Properties.LLM_POSTPROCESSING_COMMENTS = llmPostProcessingComments;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = llmPostProcessingSectionBreaks;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK = llmAssertionFallback;
        Properties.LLM_PROVIDER = llmProvider;
        Properties.LLM_POSTPROCESSING_TIMEOUT = llmPostProcessingTimeout;
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
    void configuresLlmReplayBeforeMasterTimeoutIsCalculated() throws Exception {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_TIMEOUT = 3600;
        CommandLine line = parse("-class", "example.Target", "-replayOracle", "suite.bin",
                "-oracleStrategy", "LLM");

        assertTrue(TestGeneration.configureStructuralSuiteMode(line, new ArrayList<>()));
        assertTrue(Properties.LLM_POSTPROCESSING_ENABLED);
        assertTrue(Properties.LLM_POSTPROCESSING_ASSERTIONS);
        assertFalse(Properties.ASSERTIONS);

        int withPostProcessing = TimeController.getInstance().calculateForHowLongClientWillRunInSeconds();
        Properties.LLM_POSTPROCESSING_ENABLED = false;
        int withoutPostProcessing = TimeController.getInstance().calculateForHowLongClientWillRunInSeconds();

        assertEquals(3600, withPostProcessing - withoutPostProcessing);
    }

    @Test
    void configuresMutationLlmReplayWithBothTimeoutPhases() throws Exception {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_POSTPROCESSING_TIMEOUT = 3600;
        CommandLine line = parse("-class", "example.Target", "-replayOracle", "suite.bin",
                "-oracleStrategy", "mutation_llm");

        assertTrue(TestGeneration.configureStructuralSuiteMode(line, new ArrayList<>()));
        assertEquals(Properties.OracleReplayStrategy.MUTATION_LLM,
                Properties.ORACLE_REPLAY_STRATEGY);
        assertTrue(Properties.ASSERTIONS);
        assertEquals(Properties.AssertionStrategy.MUTATION, Properties.ASSERTION_STRATEGY);
        assertTrue(Properties.LLM_POSTPROCESSING_ENABLED);
        assertTrue(Properties.LLM_POSTPROCESSING_ASSERTIONS);

        int withPostProcessing = TimeController.getInstance().calculateForHowLongClientWillRunInSeconds();
        Properties.LLM_POSTPROCESSING_ENABLED = false;
        int mutationOnly = TimeController.getInstance().calculateForHowLongClientWillRunInSeconds();
        Properties.ASSERTIONS = false;
        int withoutAssertions = TimeController.getInstance().calculateForHowLongClientWillRunInSeconds();

        assertEquals(3600, withPostProcessing - mutationOnly);
        assertEquals(Properties.ASSERTION_TIMEOUT, mutationOnly - withoutAssertions);
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
