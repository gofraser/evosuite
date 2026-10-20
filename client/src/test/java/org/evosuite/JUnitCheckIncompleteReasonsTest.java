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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.junit.JUnitCheckState;
import org.evosuite.llm.postprocess.LlmPostProcessingPhaseContext;
import org.evosuite.llm.postprocess.LlmPostProcessor;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reasons the JUnit check phase records when it gives up on a check, in the order it records
 * them. Each reason is one incomplete round of the {@code JUnit_Check_Incomplete_Rounds} output
 * variable.
 */
public class JUnitCheckIncompleteReasonsTest {

    private static final String RECORDING_PREFIX = "Recording JUnit check as incomplete: ";

    private Logger analyzerLogger;
    private Level analyzerLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        JUnitCheckState.resetForPhase();
        TimeController.resetSingleton();
        TestSuiteGenerator.freeMemoryBytesForTesting = null;
        analyzerLogger = (Logger) LoggerFactory.getLogger(JUnitCheckState.class);
        analyzerLevel = analyzerLogger.getLevel();
        analyzerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        analyzerLogger.addAppender(appender);
    }

    @AfterEach
    public void tearDown() {
        analyzerLogger.detachAppender(appender);
        analyzerLogger.setLevel(analyzerLevel);
        appender.stop();
        Properties.getInstance().resetToDefaults();
        JUnitCheckState.resetForPhase();
        TimeController.resetSingleton();
        TestSuiteGenerator.freeMemoryBytesForTesting = null;
    }

    @Test
    public void timeRunningOutMidPhaseRecordsEachSkippedCheck() throws Exception {
        Properties.MIN_FREE_MEM = 0;
        startJUnitCheckPhaseWithTooLittleTimeForATest();

        TestSuiteChromosome suite = new TestSuiteChromosome();
        TestChromosome chromosome = new TestChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        chromosome.setTestCase(test);
        suite.addTest(chromosome);

        compileAndCheckTests(suite);

        assertEquals(Arrays.asList(
                "ran out of time in the compile filter; remaining test(s) kept unchecked",
                "ran out of time before finishing the per-test JUnit checks; 1 test(s) left unchecked",
                "ran out of time before the whole-suite JUnit check"), recordedReasons());
        assertEquals(3, JUnitCheckState.getIncompleteRounds());
    }

    @Test
    public void timeRunningOutWithAnEmptySuiteRecordsTheWholeSuiteCheck() throws Exception {
        Properties.MIN_FREE_MEM = 0;
        startJUnitCheckPhaseWithTooLittleTimeForATest();

        compileAndCheckTests(new TestSuiteChromosome());

        assertEquals(Arrays.asList("ran out of time before the whole-suite JUnit check"), recordedReasons());
    }

    @Test
    public void lowMemoryBeforeThePhaseRecordsTheSkippedPhase() throws Exception {
        TestSuiteGenerator.freeMemoryBytesForTesting = () -> 0L;
        Properties.MIN_FREE_MEM = 1;

        compileAndCheckTests(new TestSuiteChromosome());

        assertEquals(Arrays.asList("skipped the whole JUnit check phase due to low memory"), recordedReasons());
    }

    @Test
    public void lowMemoryDuringThePhaseRecordsTheFirstSkippedCheckOnly() {
        JUnitCheckBudget budget = new JUnitCheckBudget(new PhaseMemoryGuard(() -> 0L, () -> { }, 100L));
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);

        budget.mayRun(JUnitCheckBudget.Step.PER_TEST, 2);
        budget.mayRun(JUnitCheckBudget.Step.WHOLE_SUITE, 0);
        budget.mayRun(JUnitCheckBudget.Step.REVERSE_ORDER, 0);
        budget.mayRun(JUnitCheckBudget.Step.ORDER_SENSITIVITY, 0);

        assertEquals(Arrays.asList("low free JVM memory during JUnit check; "
                + "skipping the remaining per-test JUnit checks and all later checks"), recordedReasons());
    }

    @Test
    public void timeRunningOutBeforeTheReverseOrderCheckRecordsIt() {
        startJUnitCheckPhaseWithTooLittleTimeForATest();
        JUnitCheckBudget budget = new JUnitCheckBudget(new PhaseMemoryGuard(() -> 1_000L, () -> { }, 100L));

        budget.mayRun(JUnitCheckBudget.Step.REVERSE_ORDER, 0);

        assertEquals(Arrays.asList("ran out of time before the reverse-order JUnit check"), recordedReasons());
    }

    private static void startJUnitCheckPhaseWithTooLittleTimeForATest() {
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.JUNIT_CHECK_TIMEOUT = 1;
        TimeController.resetSingleton();
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);
    }

    private List<String> recordedReasons() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(RECORDING_PREFIX))
                .map(message -> message.substring(RECORDING_PREFIX.length()))
                .collect(Collectors.toList());
    }

    private static void compileAndCheckTests(TestSuiteChromosome suite) throws Exception {
        Method m = OraclePhase.class.getDeclaredMethod(
                "compileAndCheckTests", TestSuiteChromosome.class, LlmPostProcessor.class,
                LlmPostProcessingPhaseContext.class);
        m.setAccessible(true);
        m.invoke(null, suite, null, null);
    }
}
