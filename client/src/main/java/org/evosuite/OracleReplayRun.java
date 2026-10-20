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
package org.evosuite;

import org.evosuite.junit.writer.Scaffolding;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.result.TestGenerationResult;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.StatisticsSender;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.StructuralSuiteArtifact;
import org.evosuite.testsuite.StructuralSuiteMetadata;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Replays a previously exported structural suite: re-observes it, runs the oracle phase on it
 * and writes it out one-to-one, instead of generating tests.
 */
final class OracleReplayRun {

    private static final Logger logger = LoggerFactory.getLogger(OracleReplayRun.class);

    private OracleReplayRun() {
    }

    static TestGenerationResult run() {
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Replaying structural suite from "
                + new File(Properties.ORACLE_REPLAY_INPUT).getAbsolutePath());

        TestGenerationResult result;
        try {
            StructuralSuiteArtifact.Loaded loaded = StructuralSuiteArtifact.read(
                    new File(Properties.ORACLE_REPLAY_INPUT), Properties.TARGET_CLASS);
            TestSuiteChromosome suite = loaded.getSuite();
            StructuralSuiteMetadata metadata = loaded.getMetadata();
            // Capture pristine structural clones before execution. Executing a
            // test can replace instrumentation class loaders referenced by
            // reflective statements, which changes their rendered fingerprint
            // even though no executable statement was added, removed, or
            // replaced.
            ReplayStructureSnapshot replayStructure = ReplayStructureSnapshot.capture(suite);

            for (TestChromosome test : suite.getTestChromosomes()) {
                test.getTestCase().removeAssertions();
                TestPresentationMetadata.clear(test.getTestCase());
                test.clearCachedResults();
                ExecutionResult executionResult = TestCaseExecutor.runTest(test.getTestCase());
                test.setLastExecutionResult(executionResult);
            }
            if (!replayStructure.hasSameExecutableStructure(suite)) {
                throw new IllegalStateException(
                        "Oracle replay observation execution changed executable structure: expected "
                                + metadata.getTestCount() + " tests/"
                                + metadata.getStatementCount() + " statements but observed "
                                + suite.size() + " tests/"
                                + suite.totalLengthOfTestCases() + " statements");
            }
            Set<String> replayReadProperties =
                    org.evosuite.runtime.System.getAllPropertiesReadSoFar();
            // Assertion generation and LLM validation execute observer code.
            // Any classes initialized only by those auxiliary executions must
            // not leak into the emitted reset fixture, otherwise paired arms
            // no longer execute under the same scaffolding.
            Scaffolding.ReplaySnapshot replayScaffoldingSnapshot =
                    Scaffolding.captureReplaySnapshot(
                            suite.getTests(), suite.getLastExecutionResults());

            MinimizationResult minimizationResult = metadata.toMinimizationResult();
            minimizationResult.publish();
            ClientServices.track(RuntimeVariable.Oracle_Replay_Strategy,
                    Properties.ORACLE_REPLAY_STRATEGY.name());
            ClientServices.track(RuntimeVariable.Oracle_Replay_Input_Fingerprint,
                    metadata.getStructuralFingerprint());
            ClientServices.track(RuntimeVariable.Oracle_Replay_Input_Tests,
                    metadata.getTestCount());
            ClientServices.track(RuntimeVariable.Oracle_Replay_Input_Statements,
                    metadata.getStatementCount());
            StatisticsSender.executedAndThenSendIndividualToMaster(suite);

            OraclePhase.run(suite, minimizationResult, replayStructure);

            // Assertion observation, LLM validation, and JUnit execution can all
            // swap instrumentation class loaders. TestCodeVisitor resolves some
            // statement descriptors through that global state, so rendering the
            // same restored objects is not a stable postcondition. The validated
            // input digest is authoritative after the canonical snapshot has
            // replaced every executable statement in the suite.
            String outputFingerprint = metadata.getStructuralFingerprint();
            boolean structurePreserved = suite.size() == metadata.getTestCount()
                    && suite.totalLengthOfTestCases() == metadata.getStatementCount();
            ClientServices.track(RuntimeVariable.Oracle_Replay_Output_Fingerprint, outputFingerprint);
            ClientServices.track(RuntimeVariable.Oracle_Replay_Output_Tests, suite.size());
            ClientServices.track(RuntimeVariable.Oracle_Replay_Output_Statements,
                    suite.totalLengthOfTestCases());
            // The final preservation boundary is the emitted Java suite. Keep
            // this false until the isolated writer copy has completed without
            // chopping or replacing an executable statement.
            ClientServices.track(RuntimeVariable.Oracle_Replay_Structure_Preserved, false);
            if (!structurePreserved) {
                logger.warn("Oracle replay changed suite structure: input fingerprint={}, output fingerprint={}, "
                                + "input tests/statements={}/{}, output tests/statements={}/{}",
                        metadata.getStructuralFingerprint(), outputFingerprint,
                        metadata.getTestCount(), metadata.getStatementCount(),
                        suite.size(), suite.totalLengthOfTestCases());
                throw new IllegalStateException(
                        "Oracle replay changed executable structure before JUnit rendering");
            }

            StatisticsSender.sendIndividualToMaster(suite);
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Writing replayed tests to file");
            // Assertion generation, snippet validation, and JUnit checking are
            // auxiliary phases. Do not let System-property reads performed by
            // those phases change the scaffolding of otherwise paired replay
            // arms. The writer will add any reads made by the final executable
            // suite to this canonical observation snapshot.
            org.evosuite.runtime.System.restorePropertiesReadSoFar(replayReadProperties);
            result = writeReplayJUnitTestsAndCreateResult(
                    suite, replayStructure, replayScaffoldingSnapshot);
            ClientServices.track(RuntimeVariable.Oracle_Replay_Structure_Preserved, true);
        } catch (Throwable t) {
            logger.error("Oracle replay failed", t);
            result = TestGenerationResultBuilder.buildErrorResult(
                    t.getMessage() != null ? t.getMessage() : t.toString());
        }

        TestSuiteGenerator.finishRun();
        return result;
    }

    private static TestGenerationResult writeReplayJUnitTestsAndCreateResult(
            TestSuiteChromosome testSuite, ReplayStructureSnapshot replayStructure,
            Scaffolding.ReplaySnapshot replayScaffoldingSnapshot) {
        TestSuiteChromosome suiteToWrite = cloneSuiteForWriting(testSuite);
        int writerInputTests = suiteToWrite.size();
        int writerInputStatements = suiteToWrite.totalLengthOfTestCases();
        List<TestCase> tests = suiteToWrite.getTests();
        if (Properties.JUNIT_TESTS) {
            ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_TESTS);

            TestSuiteWriter suiteWriter = new TestSuiteWriter();
            // Prefix folding is useful for ordinary generation, but replay
            // promises one-to-one structural suites across oracle arms.
            suiteWriter.insertAllTests(tests);
            suiteWriter.setReplayScaffoldingSnapshot(replayScaffoldingSnapshot);
            TestSuiteGenerator.writeSuite(suiteWriter, testSuite, suiteToWrite, Properties.JUNIT_SUFFIX);
            // The writer renders from a throwaway clone, so it may legitimately
            // reconstruct individual statement objects (for example when
            // TestCodeVisitor resolves descriptors through swapped
            // instrumentation class loaders). Object identity is therefore not a
            // stable postcondition of rendering; the executable structure that
            // must be preserved is the test count and the total statement count,
            // matching the authoritative preservation signal computed before
            // rendering. Compare those counts rather than object identity so a
            // structure-preserving rendering is not misreported as a change.
            if (!writerPreservedExecutableStructure(
                    suiteToWrite, writerInputTests, writerInputStatements)) {
                throw new IllegalStateException(
                        "JUnit writer added or dropped a test, or grew the replay suite,"
                                + " while rendering (tests " + writerInputTests + "->"
                                + suiteToWrite.size() + ", statements " + writerInputStatements
                                + "->" + suiteToWrite.totalLengthOfTestCases() + ")");
            }
            if (testSuite.size() != replayStructure.structuralTestCount()
                    || testSuite.totalLengthOfTestCases()
                    != replayStructure.totalStructuralStatements()) {
                throw new IllegalStateException(
                        "Canonical replay suite changed while writing an isolated rendering copy");
            }
        }
        return TestGenerationResultBuilder.buildSuccessResult();
    }

    /**
     * Decides whether the JUnit writer preserved the executable structure of a
     * replay suite. The writer renders from a throwaway clone and may
     * reconstruct individual statement objects, so structure preservation is
     * not defined by object identity.
     *
     * <p>The writer also chops every statement after an uncaught exception,
     * because code after an exception cannot compile (see
     * {@link org.evosuite.junit.writer.TestSuiteWriter} and
     * {@link org.evosuite.testcase.TestCase#chop(int)}). That reduction is
     * deterministic, required for compilable output, and applied identically for
     * every arm replaying the same structural suite, so it is not a structural
     * divergence between arms. It only ever <em>reduces</em> the statement count
     * and never adds or drops a whole test. The invariant that must hold is
     * therefore that the writer neither adds nor removes a test and never grows
     * the suite; the canonical (unchopped) suite is validated separately against
     * the pre-render replay snapshot. Requiring an exact statement count here
     * would reject large exception-throwing suites (e.g. Closure NodeUtil) whose
     * only change is the compilability-required chop.
     */
    static boolean writerPreservedExecutableStructure(
            TestSuiteChromosome renderedSuite, int inputTests, int inputStatements) {
        return renderedSuite.size() == inputTests
                && renderedSuite.totalLengthOfTestCases() <= inputStatements;
    }

    static TestSuiteChromosome cloneSuiteForWriting(TestSuiteChromosome source) {
        TestSuiteChromosome copy = new TestSuiteChromosome();
        for (TestCase test : source.getTests()) {
            copy.addTest(test.clone());
        }
        copy.setExtensionInitializationOrder(source.getExtensionInitializationOrder());
        return copy;
    }
}
