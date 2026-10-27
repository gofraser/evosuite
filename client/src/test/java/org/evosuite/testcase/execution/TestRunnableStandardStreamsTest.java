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
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testsupport.StreamWrappingFixture;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * During the search, code under test that wraps System.out on construction must not see its
 * wrappers pile up over the executions: the streams are handed back after every test.
 */
public class TestRunnableStandardStreamsTest {

    private PrintStream out;
    private PrintStream err;
    private boolean printToSystem;

    @BeforeEach
    public void setUp() {
        out = System.out;
        err = System.err;
        printToSystem = Properties.PRINT_TO_SYSTEM;
    }

    @AfterEach
    public void tearDown() {
        System.setOut(out);
        System.setErr(err);
        Properties.PRINT_TO_SYSTEM = printToSystem;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void wrappersDoNotStackAcrossExecutions(boolean printToSystem) throws Exception {
        Properties.PRINT_TO_SYSTEM = printToSystem;
        try (StreamWrappingFixture.Recording recording = StreamWrappingFixture.record()) {
            for (int i = 0; i < 5; i++) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new ConstructorStatement(tc,
                        new GenericConstructor(StreamWrappingFixture.class.getConstructor(),
                                StreamWrappingFixture.class), Collections.emptyList()));
                new TestRunnable(tc, new Scope(), Collections.emptySet()).call();
            }
            Assertions.assertEquals(5, recording.depths().size());
            for (int depth : recording.depths()) {
                Assertions.assertEquals(1, depth, "wrappers survived: " + recording.depths());
            }
        }
        Assertions.assertSame(out, System.out);
        Assertions.assertSame(err, System.err);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void streamsMutedByATimedOutExecutionAreNotTakenAsTheRealOnes(boolean printToSystem) {
        Properties.PRINT_TO_SYSTEM = printToSystem;
        // An earlier execution timed out with its worker still running: the streams are muted
        // when the next execution starts.
        LoggingUtils.muteCurrentOutAndErrStream();
        try {
            new TestRunnable(new DefaultTestCase(), new Scope(), Collections.emptySet()).call();
            // Whatever the execution handed back must be the real streams, not the muted
            // ones, before anybody else gets to unmute.
            Assertions.assertSame(out, System.out);
            Assertions.assertSame(err, System.err);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            LoggingUtils.restorePreviousOutAndErrStream();
        }
        Assertions.assertSame(out, System.out);
        Assertions.assertSame(err, System.err);
    }

    /** Blocks the worker before its first statement until released. */
    private static class BlockingObserver extends ExecutionObserver {
        final CountDownLatch inObserver = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile PrintStream replaceOutWith;

        @Override
        public void beforeStatement(Statement statement, Scope scope) {
            // the code under test replacing System.out while the run is in flight
            if (replaceOutWith != null) {
                System.setOut(replaceOutWith);
            }
            inObserver.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void afterStatement(Statement statement, Scope scope, Throwable exception) {
        }

    }

    private static final class Worker {
        final TestRunnable runnable;
        final BlockingObserver observer;
        final Thread thread;

        Worker(PrintStream replaceOutWith) throws InterruptedException {
            DefaultTestCase tc = new DefaultTestCase();
            tc.addStatement(new IntPrimitiveStatement(tc, 5));
            observer = new BlockingObserver();
            observer.replaceOutWith = replaceOutWith;
            runnable = new TestRunnable(tc, new Scope(), Collections.singleton(observer));
            thread = new Thread(() -> {
                try {
                    runnable.call();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "stream-worker");
            thread.start();
            Assertions.assertTrue(observer.inObserver.await(10, TimeUnit.SECONDS));
        }

        void finish() throws InterruptedException {
            observer.release.countDown();
            thread.join(10_000);
            Assertions.assertFalse(thread.isAlive());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void workerUnwindingInsideTheGraceWindowRestoresTheStreams(boolean printToSystem)
            throws Exception {
        Properties.PRINT_TO_SYSTEM = printToSystem;
        PrintStream wrapper = new PrintStream(new ByteArrayOutputStream());
        Worker worker = new Worker(wrapper);
        try {
            // the waiting thread starts handling the timeout; the worker still owns the streams
            worker.runnable.markResultDiscarded();
            Assertions.assertNotSame(out, System.out);
            worker.finish();
            Assertions.assertSame(out, System.out, "the wrapper installed by the run survived");
            Assertions.assertSame(err, System.err);
        } finally {
            worker.observer.release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void giveUpOnAWorkerThatNeverUnwindsRestoresTheStreams(boolean printToSystem)
            throws Exception {
        Properties.PRINT_TO_SYSTEM = printToSystem;
        PrintStream wrapper = new PrintStream(new ByteArrayOutputStream());
        Worker worker = new Worker(wrapper);
        try {
            worker.runnable.markResultDiscarded();
            // the executor gives up on the worker, which is still blocked
            worker.runnable.handBackStreams();
            Assertions.assertSame(out, System.out, "the wrapper installed by the run survived");
            Assertions.assertSame(err, System.err);
        } finally {
            worker.finish();
        }
        Assertions.assertSame(out, System.out);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void streamsAreHandedBackOnlyOnce(boolean printToSystem) throws Exception {
        Properties.PRINT_TO_SYSTEM = printToSystem;
        Worker worker = new Worker(null);
        try {
            worker.runnable.markResultDiscarded();
            worker.runnable.handBackStreams();
            // the next execution is under way when the abandoned worker finally unwinds
            PrintStream next = new PrintStream(new ByteArrayOutputStream());
            System.setOut(next);
            worker.finish();
            Assertions.assertSame(next, System.out, "the late worker restored its snapshot again");
            worker.runnable.handBackStreams();
            Assertions.assertSame(next, System.out);
        } finally {
            worker.observer.release.countDown();
        }
    }

    @Test
    public void discardingTheResultDoesNotUnmuteTheStreams() throws Exception {
        Properties.PRINT_TO_SYSTEM = false;
        Worker worker = new Worker(null);
        try {
            PrintStream muted = System.out;
            Assertions.assertNotSame(out, muted);
            worker.runnable.markResultDiscarded();
            Assertions.assertSame(muted, System.out, "streams unmuted for the whole grace window");
        } finally {
            worker.finish();
        }
        Assertions.assertSame(out, System.out);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void zombieMuteIsNotTakenAsTheRealStreamsByTheNextExecution(boolean printToSystem)
            throws Exception {
        Properties.PRINT_TO_SYSTEM = printToSystem;
        // A is a worker that timed out and has not unwound; B is the next execution.
        Worker zombie = new Worker(null);
        try {
            new TestRunnable(new DefaultTestCase(), new Scope(), Collections.emptySet()).call();
            zombie.runnable.markResultDiscarded();
            zombie.runnable.handBackStreams();
            Assertions.assertSame(out, System.out);
            Assertions.assertSame(err, System.err);
        } finally {
            zombie.finish();
        }
        Assertions.assertSame(out, System.out);
        Assertions.assertSame(err, System.err);
    }
}
