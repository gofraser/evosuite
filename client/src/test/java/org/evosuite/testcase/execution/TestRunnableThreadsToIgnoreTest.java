/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.execution;

import org.evosuite.llm.search.LlmPrivilegedThreads;
import org.evosuite.runtime.thread.ThreadStopper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a test runs, {@link ThreadStopper} interrupts every thread that was not alive before
 * it, treating it as a leaked SUT thread. LLM background threads start on the search's own
 * schedule, so one born mid-test (an operator worker, the parse-phase compiler) must be left
 * alone; otherwise its HTTP read or javac run dies half-way with an interrupt.
 */
public class TestRunnableThreadsToIgnoreTest {

    @Test
    public void llmThreadPrefixIsIgnored() {
        assertTrue(TestRunnable.threadsToIgnore().contains(LlmPrivilegedThreads.THREAD_NAME_PREFIX));
        assertTrue(TestRunnable.threadsToIgnore().contains(TestCaseExecutor.TEST_EXECUTION_THREAD));
        assertTrue(LlmPrivilegedThreads.threadName("operator-1").startsWith(LlmPrivilegedThreads.THREAD_NAME_PREFIX));
    }

    @Test
    public void threadStopperLeavesLlmThreadsAloneButInterruptsSutThreads() throws Exception {
        ThreadStopper stopper = new ThreadStopper(kill -> { }, TestRunnable.threadsToIgnore(), 200L);
        stopper.storeCurrentThreads();
        stopper.startRecordingTime();

        CountDownLatch started = new CountDownLatch(2);
        AtomicBoolean llmInterrupted = new AtomicBoolean();
        AtomicBoolean sutInterrupted = new AtomicBoolean();
        Thread llm = blockingThread(LlmPrivilegedThreads.threadName("parse-compile"), started, llmInterrupted);
        Thread sut = blockingThread("sut-worker", started, sutInterrupted);
        llm.start();
        sut.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        stopper.killAndJoinClientThreads();

        sut.join(5000);
        assertTrue(sutInterrupted.get(), "a thread the test spawned is interrupted");
        assertFalse(llmInterrupted.get(), "an LLM background thread is not");
        llm.interrupt();
        llm.join(5000);
    }

    private static Thread blockingThread(String name, CountDownLatch started, AtomicBoolean interrupted) {
        Thread t = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }, name);
        t.setDaemon(true);
        return t;
    }
}
