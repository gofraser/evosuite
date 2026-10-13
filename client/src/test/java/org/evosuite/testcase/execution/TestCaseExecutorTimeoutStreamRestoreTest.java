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
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a test times out, the executor hands the standard streams of the run back. It must
 * leave them there: restoring a copy of {@code System.out} captured when the executor class was
 * loaded undoes the hand-back and replaces whatever stream the process installed since.
 *
 * <p>The test body sleeps far longer than the executor's timeout, so the timeout always fires;
 * the executor's cancellation interrupts the sleep, so no worker is left running.
 */
public class TestCaseExecutorTimeoutStreamRestoreTest {

    private PrintStream out;
    private PrintStream err;
    private boolean printToSystem;

    public static class Sleeper {
        public static void sleepLong() throws InterruptedException {
            Thread.sleep(30_000L);
        }
    }

    @BeforeEach
    public void setUp() {
        out = System.out;
        err = System.err;
        printToSystem = Properties.PRINT_TO_SYSTEM;
        Properties.PRINT_TO_SYSTEM = false;
    }

    @AfterEach
    public void tearDown() {
        System.setOut(out);
        System.setErr(err);
        Properties.PRINT_TO_SYSTEM = printToSystem;
    }

    @Disabled("D12: executor timeout path restores System.out from a class-load-time static"
            + " - see CLEANUP-PLAN.md")
    @Test
    public void timeoutKeepsTheStreamsTheRunHandedBack() throws Exception {
        TestCaseExecutor executor = TestCaseExecutor.getInstance(); // captures its static streams
        PrintStream installed = new PrintStream(new ByteArrayOutputStream(), true);
        System.setOut(installed);

        DefaultTestCase tc = new DefaultTestCase();
        Method sleep = Sleeper.class.getMethod("sleepLong");
        tc.addStatement(new MethodStatement(tc, new GenericMethod(sleep, Sleeper.class), null,
                Collections.emptyList()));

        ExecutionResult result = executor.execute(tc, 300);

        assertTrue(result.hasTimeout(), "precondition: the execution timed out");
        assertSame(installed, System.out,
                "the timeout path replaced the stream the run handed back with a stale static copy");
    }
}
