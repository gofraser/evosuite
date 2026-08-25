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
package org.evosuite.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In some cases, we can end up with infinite loops: eg due to a bug, a seeded mutation,
 * a very large input parameter, or simply it is the expected behavior of the CUT.
 * In such cases, we will every time hit the test timeout, which will hinder the search.
 *
 * <p>Therefore, for each loop in the instrumented CUTs, we can have a limit, and throw an exception
 * if too many iterations have occurred.
 *
 * <p>Created by Andrea Arcuri on 29/03/15.
 */
public class LoopCounter {

    private static LoopCounter singleton = new LoopCounter();

    private Object delegate;

    public void setDelegate(Object delegate) {
        this.delegate = delegate;
    }

    private boolean activated = true;

    /**
     * Number of iterations so far, shared by whichever thread is currently the active
     * test-execution worker. See {@link #protectedCounters} for why a thread that has
     * been abandoned stops using this list.
     */
    private final List<Long> counters;

    /**
     * Snapshots of {@link #counters} for threads that {@link #protectFromReset(Thread)}
     * has flagged as abandoned. TestCaseExecutor sometimes gives up on a thread without
     * forcing it to stop — e.g. one still running a slow &lt;clinit&gt;, where forcing it
     * would risk throwing out of the initializer and poisoning the class — and leaves it
     * running in the background while the search moves on to a fresh thread.
     *
     * <p>Without this map, that abandoned thread keeps reading and writing the single
     * shared {@link #counters} list, and every subsequent, unrelated test starting
     * elsewhere calls {@link #reset()} and wipes it back to zero. A search that starts
     * a new test every few milliseconds can then wipe the abandoned loop's progress
     * far faster than it accumulates, so it never reaches either safety threshold in
     * {@link #checkLoop(int)} and spins at 100% CPU — repeatedly re-triggering the
     * interrupt-driven stack walk in {@link #isInterruptInStaticInit()} — for as long
     * as the process runs. Once a thread is in this map it keeps counting on its own
     * private copy instead, immune to resets made on behalf of other threads' tests.
     */
    private final Map<Thread, List<Long>> protectedCounters = new ConcurrentHashMap<>();

    /**
     * How many interrupted back-edges we let pass between two stack walks, once we have
     * established that we are inside a static initializer. The interrupt flag stays set
     * until the thread is stopped, so from the first interrupted back-edge onwards
     * <i>every</i> further call would otherwise pay for a full
     * {@link #isInStaticInit()} stack walk. Re-checking only every so often keeps that
     * cost bounded; the price is that for at most this many back-edges after the
     * static initializer has returned we may still treat the loop as being inside
     * &lt;clinit&gt;, which merely delays the interrupt-driven exception a little.
     */
    private static final int INTERRUPT_STATIC_INIT_RECHECK_INTERVAL = 1000;

    /**
     * Per-thread memo of whether the last {@link #isInStaticInit()} stack walk done on
     * an interrupted back-edge found a &lt;clinit&gt; frame, and how many interrupted
     * back-edges have been seen since that walk. Thread-local — and deliberately not
     * cleared by {@link #reset()} for any thread but the caller — for the same reason
     * {@link #protectedCounters} exists: an abandoned thread must not have its memo
     * wiped by an unrelated test's reset, or it pays for a full stack walk on every
     * remaining back-edge instead of one every {@link #INTERRUPT_STATIC_INIT_RECHECK_INTERVAL}.
     */
    private final ThreadLocal<InterruptMemo> interruptMemo = ThreadLocal.withInitial(InterruptMemo::new);

    private static final class InterruptMemo {
        boolean inStaticInit = false;
        int checksSinceStackWalk = 0;
    }


    private LoopCounter() {
        counters = new ArrayList<>();
    }

    /**
     * Marks {@code thread} as abandoned: from now on, calls to {@link #checkLoop(int)}
     * made by that thread track their iteration counts on a private copy — snapshotted
     * from the shared {@link #counters} at the moment of this call — rather than on the
     * list shared with whichever thread is currently active. See {@link #protectedCounters}
     * for why this is necessary. A no-op if {@code thread} is null or already protected.
     *
     * @param thread the thread to protect from future {@link #reset()} calls
     */
    public void protectFromReset(Thread thread) {
        if (thread == null) {
            return;
        }
        // Opportunistic cleanup: a dead thread can never call checkLoop() again, so
        // there is no point holding its snapshot for the rest of the process.
        protectedCounters.keySet().removeIf(t -> !t.isAlive());
        protectedCounters.putIfAbsent(thread, new ArrayList<>(counters));
    }

    /**
     * The iteration-count list {@link #checkLoop(int)} should use for the calling
     * thread: its own protected snapshot if it has been abandoned, otherwise the
     * list shared with whichever thread is currently active.
     */
    private List<Long> activeCounters() {
        List<Long> snapshot = protectedCounters.get(Thread.currentThread());
        return snapshot != null ? snapshot : counters;
    }

    public static LoopCounter getInstance() {
        return singleton;
    }

    /**
     * Reset the loop counters for the next test on the calling thread.
     *
     * <p>Only ever clears the <em>calling</em> thread's own interrupt memo — see
     * {@link #interruptMemo} — and, on the non-delegate path, the shared
     * {@link #counters} list. It deliberately never touches {@link #protectedCounters}:
     * those belong to threads this instance was told to abandon via
     * {@link #protectFromReset(Thread)}, and must keep counting undisturbed by a
     * reset triggered on behalf of some other, unrelated test.
     */
    public void reset() {
        interruptMemo.remove();
        if (delegate != null) {
            invokeDelegate("reset");
            return;
        }
        counters.clear();
    }

    /**
     * Set the loop counter to active or inactive.
     *
     * @param active true if loop counter should be active.
     */
    public void setActive(boolean active) {
        if (delegate != null) {
            invokeDelegate("setActive", active);
            return;
        }
        this.activated = active;
    }

    /**
     * Check if the loop counter is activated.
     *
     * @return true if loop counter is activated.
     */
    public boolean isActivated() {
        if (delegate != null) {
            return (Boolean) invokeDelegate("isActivated");
        }
        return activated;
    }

    /**
     * This is called during bytecode instrumentation to determine which index
     * to assign to a new parsed loop.
     *
     * @return the next valid index for a new loop
     */
    public int getNewIndex() {
        if (delegate != null) {
            return (Integer) invokeDelegate("getNewIndex");
        }
        int index = counters.size();
        counters.add(0L);
        return index;
    }


    /**
     * This is added directly in the instrumented CUT after each loop statement.
     *
     * @param index a int.
     * @throws org.evosuite.runtime.TooManyResourcesException if this loop has executed too
     *     many iterations
     * @throws java.lang.IllegalArgumentException if any.
     */
    public void checkLoop(int index) throws TooManyResourcesException, IllegalArgumentException {
        if (delegate != null) {
            // Check interrupt before delegating — the delegate runs via
            // reflection which is slow, and we want fast termination.
            // Never on an interrupt alone inside a static initializer though:
            // see the comment at the interrupt check further down.
            if (Thread.currentThread().isInterrupted() && !isInterruptInStaticInit()) {
                throw new TooManyResourcesException(
                        "Loop terminated early: thread was interrupted (likely timeout)");
            }
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("checkLoop", int.class);
                m.invoke(delegate, index);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof TooManyResourcesException) {
                    throw (TooManyResourcesException) e.getCause();
                }
                if (e.getCause() instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e.getCause();
                }
            } catch (Exception e) {
                // Ignore
            }
            return;
        }
        if (index < 0) {
            throw new IllegalArgumentException("Loop index cannot be negative");
        }

        if (!activated) {
            return;
        }

        if (RuntimeSettings.maxNumberOfIterationsPerLoop < 0) {
            return; //do nothing, no check
        }

        // If the thread has been interrupted (e.g., by the timeout handler),
        // terminate the loop immediately. This is critical because the kill
        // switch / ExecutionTracer.checkTimeout() only fires at instrumented
        // line checkpoints, which the thread may never reach if each loop
        // iteration blocks in native code (e.g., file I/O, DNS lookup).
        // checkLoop() is called at the loop back-edge — the earliest point
        // we regain control after a native call returns.
        //
        // Exception: inside a static initializer we must not throw on a mere
        // interrupt. Any exception escaping a <clinit> makes the JVM mark the
        // class erroneous for the lifetime of the classloader, so that class and
        // everything that reads from it throw NoClassDefFoundError from then on —
        // which silently zeroes the coverage of the whole run, not just this test.
        // An interrupt only says "we would like to stop", so it is not worth that
        // price. A truly stuck static initializer is still terminated: by the 10x
        // clinit iteration limit below, and by the kill-switch probe backstop in
        // ExecutionTracer.checkTimeout().
        if (Thread.currentThread().isInterrupted() && !isInterruptInStaticInit()) {
            this.reset();
            throw new TooManyResourcesException(
                    "Loop terminated early: thread was interrupted (likely timeout)");
        }

        // Use this thread's own protected snapshot if it has been abandoned (see
        // protectedCounters), otherwise the list shared with the active thread.
        List<Long> active = activeCounters();

        //first check initialization
        int size = active.size();
        if (index >= size) {
            for (int i = 0; i < 1 + (index - size); i++) {
                active.add(0L);
            }
        }
        assert index < active.size();

        //do increment
        try {
            long value = active.get(index) + 1L;
            active.set(index, value);

            if (value >= RuntimeSettings.maxNumberOfIterationsPerLoop) {
                if (!isInStaticInit()) {
                    this.reset();
                    throw new TooManyResourcesException("Loop has been executed more times than the allowed "
                            + RuntimeSettings.maxNumberOfIterationsPerLoop);
                }
                // In static initializers, allow a 10x higher limit before giving up.
                // Throwing here will leave the class in a broken state (NoClassDefFoundError),
                // but that's better than a permanently stalled thread consuming resources.
                if (value >= RuntimeSettings.maxNumberOfIterationsPerLoop * 10) {
                    this.reset();
                    throw new TooManyResourcesException("Loop in static initializer has been executed more "
                            + "times than the allowed " + (RuntimeSettings.maxNumberOfIterationsPerLoop * 10)
                            + " (10x clinit limit)");
                }
            }
        } catch (TooManyResourcesException e) {
            throw e; // re-throw — must not be swallowed
        } catch (NullPointerException e) {
            // Some weird Java internal NPE can happen:
            // https://github.com/EvoSuite/evosuite/issues/143
            // Seems safe to swallow this instead of crashing EvoSuite
        }
    }


    /**
     * Called only when the current thread is already interrupted and we are about to
     * throw because of it. Answers whether we are inside a static initializer, using a
     * throttled stack walk so that a hot loop does not pay for
     * {@link #isInStaticInit()} on every remaining back-edge.
     *
     * @return true if the interrupted loop is running inside a &lt;clinit&gt;
     */
    private boolean isInterruptInStaticInit() {
        // Only a positive answer is memoized. Wrongly believing that we are *not*
        // inside a <clinit> would reintroduce the very bug this guards against,
        // whereas wrongly believing that we still are merely postpones termination
        // to the iteration limit.
        InterruptMemo memo = interruptMemo.get();
        if (memo.inStaticInit && memo.checksSinceStackWalk < INTERRUPT_STATIC_INIT_RECHECK_INTERVAL) {
            memo.checksSinceStackWalk++;
            return true;
        }
        memo.inStaticInit = isInStaticInit();
        memo.checksSinceStackWalk = 1;
        return memo.inStaticInit;
    }

    private boolean isInStaticInit() {
        for (StackTraceElement elem : new Throwable().getStackTrace()) {
            if (elem.getMethodName().startsWith("<clinit>")) {
                return true;
            }
        }
        return false;
    }

    private Object invokeDelegate(String methodName, Object... args) {
        try {
            Class<?>[] argTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Boolean) {
                    argTypes[i] = boolean.class;
                } else if (args[i] instanceof Integer) {
                    argTypes[i] = int.class;
                } else {
                    argTypes[i] = args[i].getClass();
                }
            }
            java.lang.reflect.Method m = delegate.getClass().getMethod(methodName, argTypes);
            return m.invoke(delegate, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
