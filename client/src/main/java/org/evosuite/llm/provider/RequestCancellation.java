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
package org.evosuite.llm.provider;

/**
 * A cancellation handle scoped to a single LLM request.
 *
 * <p>{@code LlmService} used to abort a stuck call by asking the whole
 * {@code ChatLanguageModel} to disconnect every connection its underlying
 * {@link JsonHttpClient} had open — but the client's HTTP connection is
 * shared across concurrent calls on the service's executor, so one request's
 * timeout could disconnect a completely unrelated, healthy request. This
 * class narrows that abort to the one request it is created for: the caller
 * creates one instance per call, the provider {@link #attach(Runnable)
 * attaches} how to abort that specific connection once it is open, and only
 * that connection is torn down when {@link #abort()} is called.
 *
 * <p>Thread-safe: {@link #attach(Runnable)} and {@link #abort()} may be
 * called from different threads (the request worker and the thread enforcing
 * the timeout, respectively).
 */
public final class RequestCancellation {

    private final Object lock = new Object();
    private boolean aborted = false;
    private Runnable aborter;

    /**
     * Registers how to abort the in-flight request this handle guards. If
     * {@link #abort()} has already been called, {@code aborter} is run
     * immediately on the calling thread, and this method returns
     * {@code false} so the caller — which may have opened a connection
     * before reaching this call — knows to stop before sending anything
     * rather than trust that running {@code aborter} necessarily had an
     * effect.
     *
     * @return {@code true} if this handle was not yet aborted when attached,
     *         {@code false} if {@link #abort()} had already run
     */
    public boolean attach(Runnable aborter) {
        boolean runImmediately;
        synchronized (lock) {
            this.aborter = aborter;
            runImmediately = aborted;
        }
        if (runImmediately && aborter != null) {
            aborter.run();
        }
        return !runImmediately;
    }

    /**
     * Aborts the request, running the attached aborter exactly once. Safe to
     * call more than once or before {@link #attach(Runnable)}; a later
     * {@code attach} call then runs its aborter immediately instead.
     */
    public void abort() {
        Runnable toRun;
        synchronized (lock) {
            if (aborted) {
                return;
            }
            aborted = true;
            toRun = aborter;
        }
        if (toRun != null) {
            toRun.run();
        }
    }

    public boolean isAborted() {
        synchronized (lock) {
            return aborted;
        }
    }
}
