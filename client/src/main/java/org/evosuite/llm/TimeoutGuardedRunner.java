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
package org.evosuite.llm;

import org.evosuite.llm.provider.RequestCancellation;
import org.evosuite.llm.search.LlmPrivilegedThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Runs one provider call on a worker thread and bounds the caller's wait by
 * both the configured call timeout and the phase deadline. On a timeout or a
 * caller interrupt, the call's own connection is aborted; a worker that still
 * does not stop takes its executor with it, and a fresh executor serves the
 * next call.
 */
final class TimeoutGuardedRunner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TimeoutGuardedRunner.class);

    private static final long WORKER_EXIT_WAIT_MS = 2_000L;

    /** One provider call, given the cancellation handle that aborts its connection. */
    interface Call<T> {
        T run(RequestCancellation cancellation) throws Exception;
    }

    private final Supplier<ExecutorService> executorFactory;
    private final Runnable onExecutorReplaced;
    private final AtomicLong generation = new AtomicLong(1L);
    private volatile ExecutorService executorService;

    /**
     * @param executorFactory    creates the executor the calls run on, now and after each replacement
     * @param onExecutorReplaced runs after a stuck worker forced the executor to be replaced
     */
    TimeoutGuardedRunner(Supplier<ExecutorService> executorFactory, Runnable onExecutorReplaced) {
        this.executorFactory = executorFactory;
        this.onExecutorReplaced = onExecutorReplaced;
        this.executorService = executorFactory.get();
    }

    /** The executor for provider calls: unbounded, with privileged daemon threads. */
    static ExecutorService newDefaultExecutor() {
        return Executors.newCachedThreadPool(r -> LlmPrivilegedThreads.newThread(r, "timeout-guard"));
    }

    /** How many executors this runner has used, starting at 1; it grows by one per replacement. */
    long getGeneration() {
        return generation.get();
    }

    /**
     * Runs {@code call} and waits at most {@code timeoutNanos}, and never past
     * the absolute {@link System#nanoTime()} {@code deadlineNanos}.
     *
     * @throws TimeoutException     if the call did not finish in time
     * @throws InterruptedException if the caller was interrupted; the interrupt flag is restored
     * @throws Exception            the call's own failure, wrapped in an
     *                              {@link java.util.concurrent.ExecutionException}
     */
    <T> T run(Call<T> call, long timeoutNanos, long deadlineNanos) throws Exception {
        ExecutorService runner = executorService;
        RequestCancellation cancellation = new RequestCancellation();
        AtomicBoolean workerEntered = new AtomicBoolean();
        CountDownLatch workerExited = new CountDownLatch(1);
        Future<T> future = runner.submit(() -> {
            workerEntered.set(true);
            try {
                return call.run(cancellation);
            } finally {
                workerExited.countDown();
            }
        });
        try {
            long remainingNanos = PhaseDeadline.remainingNanos(deadlineNanos);
            if (remainingNanos <= 0) {
                future.cancel(true);
                throw new TimeoutException("LLM phase deadline exhausted");
            }
            return future.get(Math.min(timeoutNanos, remainingNanos),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // Interrupt the worker first. A blocked HttpURLConnection read
            // normally ends on its own once the socket's read timeout elapses,
            // but that timeout is a much coarser bound than the phase deadline
            // being enforced here, so cancelling the future lets the worker
            // notice sooner if it happens to be at an interruptible point.
            future.cancel(true);
            // Future.cancel(true) cannot interrupt a thread blocked inside
            // HttpURLConnection I/O, so force this specific request's
            // connection closed directly; that turns the blocked read into
            // an IOException immediately instead of waiting for the read
            // timeout, which is a much coarser bound than this deadline. Only
            // this call's own connection is affected — other concurrent calls
            // sharing the same model are untouched.
            cancellation.abort();
            if (!waitForWorkerToFinish(workerEntered, workerExited, WORKER_EXIT_WAIT_MS)) {
                // Worker is still running — the read timeout hasn't elapsed yet
                // and the socket read isn't interruptible on this JVM. Drop the
                // executor entirely so the leaked worker can't keep the sandbox
                // lock (or anything else) alive forever; the read timeout will
                // eventually unblock and terminate the leaked thread on its own.
                // A fresh executor is created lazily on the next call.
                logger.warn("LLM worker did not terminate after cancel; shutting down executor to drop the stuck call");
                replaceExecutor(runner);
            }
            throw e;
        } catch (InterruptedException e) {
            // Cancelling is just as important for caller interruption as for a
            // timeout: otherwise a completed request can mutate state after a
            // search worker has been stopped. As above, only this call's own
            // connection is aborted.
            future.cancel(true);
            cancellation.abort();
            if (!waitForWorkerToFinish(workerEntered, workerExited, WORKER_EXIT_WAIT_MS)) {
                logger.warn("LLM worker did not terminate after caller interruption; replacing executor");
                replaceExecutor(runner);
            }
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static boolean waitForWorkerToFinish(AtomicBoolean workerEntered,
                                                 CountDownLatch workerExited,
                                                 long waitMs) {
        // Future.isDone() is not a worker-liveness signal: cancellation marks
        // a FutureTask done before its callable has necessarily returned.
        // The callable's finally block is the only reliable acknowledgement.
        if (!workerEntered.get()) {
            // A task cancelled while queued never enters the callable. Give the
            // narrow runner-registration race a short chance to become visible.
            try {
                if (!workerExited.await(Math.min(50L, waitMs), TimeUnit.MILLISECONDS)
                        && !workerEntered.get()) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        try {
            return workerExited.await(Math.max(0L, waitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Replaces the executor with a fresh one when its worker is stuck inside an
     * unresponsive HTTP call. {@link ExecutorService#shutdownNow()} interrupts
     * all workers; on a worker blocked in a non-interruptible socket read this
     * won't return immediately, but the new executor lets subsequent calls
     * proceed unimpeded. The leaked worker itself is left to unblock on its
     * own once its read timeout elapses.
     */
    private synchronized void replaceExecutor(ExecutorService stuck) {
        if (this.executorService != stuck) {
            // Already replaced by a concurrent call.
            return;
        }
        try {
            stuck.shutdownNow();
        } catch (Throwable ignored) {
            // Best effort — even if shutdown throws, we still want a fresh executor below.
        }
        this.executorService = executorFactory.get();
        generation.incrementAndGet();
        onExecutorReplaced.run();
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }
}
