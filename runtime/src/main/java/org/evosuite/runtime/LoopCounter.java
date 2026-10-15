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

import org.evosuite.runtime.classhandling.ClassResetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
     * interrupt-driven stack walk in {@link #isInStaticInitThrottled()} — for as long
     * as the process runs. Once a thread is in this map it keeps counting on its own
     * private copy instead, immune to resets made on behalf of other threads' tests.
     */
    private final Map<Thread, List<Long>> protectedCounters = new ConcurrentHashMap<>();

    /**
     * Deadlines for workers that were abandoned while still running a static
     * initializer.  A normal interrupt cannot be used for those workers: an
     * exception escaping {@code <clinit>} poisons the class, and the executor
     * therefore leaves the worker alone after its grace period.  The deadline
     * gives an instrumented loop one final, bounded escape once the initializer
     * has been identified as stuck.
     */
    private final Map<Thread, Long> abandonedStaticInitDeadlines = new ConcurrentHashMap<>();

    /**
     * True while {@link #abandonedStaticInitDeadlines} may be non-empty.  Loop
     * back-edges run on deep SUT stacks, so the common case (nothing abandoned)
     * must be a single volatile read: no Thread.currentThread(), no map lookup and
     * no extra frame.  Writers hold {@link #abandonedStaticInitLock} so the flag
     * never claims "empty" while an entry exists.
     */
    private volatile boolean abandonedStaticInitArmed = false;

    private final Object abandonedStaticInitLock = new Object();

    /**
     * Once an initializer has been abandoned, let it run briefly before the
     * loop counter terminates it.  This small window lets a worker that is just
     * leaving the initializer complete normally, while ensuring that a tight
     * abandoned loop cannot retain the class-initialization lock forever.
     */
    private static final long ABANDONED_STATIC_INIT_GRACE_MILLIS = 1000L;

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
        boolean inStaticInitOrReset = false;
        int checksSinceInitOrResetWalk = 0;
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
        synchronized (abandonedStaticInitLock) {
            abandonedStaticInitDeadlines.keySet().removeIf(t -> !t.isAlive());
            abandonedStaticInitArmed = !abandonedStaticInitDeadlines.isEmpty();
        }
        List<Long> snapshot;
        synchronized (counters) {
            snapshot = new ArrayList<>(counters);
        }
        protectedCounters.putIfAbsent(thread, snapshot);
    }

    /**
     * Arms the bounded escape used for a worker that the test executor has
     * abandoned while it is still inside a static initializer.
     *
     * <p>The marker is deliberately per-thread.  A new test may reset the
     * shared counters immediately after the executor rotates, but that reset
     * must not either erase the abandoned worker's progress or disable its
     * final termination guard.</p>
     *
     * @param thread the worker that was abandoned
     */
    public void abandonStaticInitializer(Thread thread) {
        if (delegate != null) {
            invokeDelegate("abandonStaticInitializer", thread);
            return;
        }
        if (thread == null) {
            return;
        }
        protectFromReset(thread);
        long deadline = java.lang.System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(ABANDONED_STATIC_INIT_GRACE_MILLIS);
        synchronized (abandonedStaticInitLock) {
            abandonedStaticInitDeadlines.put(thread, deadline);
            abandonedStaticInitArmed = true;
        }
    }

    /**
     * Returns whether the calling thread has reached the termination deadline
     * for an abandoned static initializer.  This is also used by
     * {@code ExecutionTracer.checkTimeout()}, because class-initializer loops
     * can reach a timeout probe without reaching a {@code LoopCounter} probe.
     *
     * @return true when the abandoned initializer should be terminated
     */
    public boolean shouldTerminateAbandonedStaticInitializer() {
        if (delegate != null) {
            return (Boolean) invokeDelegate("shouldTerminateAbandonedStaticInitializer");
        }
        return abandonedStaticInitArmed && abandonedStaticInitializerExpired();
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
        testEpoch.incrementAndGet();
        clearCounters();
    }

    private void clearCounters() {
        synchronized (counters) {
            counters.clear();
        }
    }

    /**
     * Counts the calls to {@link #reset()}, so that each thread's {@link #trippedLoops} can tell
     * whether it was recorded in the current test. reset() runs on the thread that prepares the
     * test, not necessarily on the threads that then run it, so it cannot clear their records
     * itself.
     */
    private final java.util.concurrent.atomic.AtomicInteger testEpoch =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The loops that hit the iteration limit on this thread in the current test.
     *
     * <p>When a loop hits the limit a second time, the code under test caught the first
     * exception and went on running, inside that loop or back into it: a while (true) whose
     * body catches Throwable around it would otherwise go
     * round a full limit after every trip, until the test times out. The exception of such a
     * second trip is flagged, and the instrumented catch handlers let it through, see
     * {@link Runtime#rethrowIfTimeout(Throwable)}.
     *
     * <p>The record is per loop and per thread. A trip of another loop, or on another thread,
     * says nothing about whether this loop's exception was swallowed: the earlier one may have
     * reached the test, or belong to a thread abandoned by an earlier test, and the handlers of
     * the code under test must then see this exception like in a replay of the test on its own.
     * The same loop tripping in two statements of one test, the first exception having reached
     * the test, is flagged too; telling that apart would need a hook at the statement boundary,
     * which a replayed test does not have, and the search and the replay must agree.
     */
    private final ThreadLocal<TrippedLoops> trippedLoops = ThreadLocal.withInitial(TrippedLoops::new);

    private static final class TrippedLoops {
        int epoch = -1;
        final java.util.BitSet indices = new java.util.BitSet();
    }

    /**
     * Records that loop {@code index} hit the iteration limit on the calling thread.
     *
     * @return whether it had already hit it on this thread in the current test
     */
    private boolean recordTrip(int index) {
        TrippedLoops tripped = trippedLoops.get();
        // An abandoned thread keeps its record, like its counters (see protectedCounters):
        // the resets are made for other threads' tests.
        int epoch = testEpoch.get();
        if (tripped.epoch != epoch && !protectedCounters.containsKey(Thread.currentThread())) {
            tripped.indices.clear();
            tripped.epoch = epoch;
        }
        boolean repeated = tripped.indices.get(index);
        tripped.indices.set(index);
        return repeated;
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
        synchronized (counters) {
            int index = counters.size();
            counters.add(0L);
            return index;
        }
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
            if (abandonedStaticInitArmed && abandonedStaticInitializerExpired()) {
                throw new TooManyResourcesException(
                        "Abandoned static initializer exceeded its termination grace period");
            }
            if (Thread.currentThread().isInterrupted() && !isInStaticInitThrottled()) {
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

        // This check intentionally precedes both the active flag and the
        // ordinary iteration limit.  TestCaseExecutor disables those controls
        // while it waits out a static initializer, and leaves them disabled for
        // an abandoned worker; the per-thread deadline is the remaining guard.
        if (abandonedStaticInitArmed && abandonedStaticInitializerExpired()) {
            throw new TooManyResourcesException(
                    "Abandoned static initializer exceeded its termination grace period");
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
        // price. A truly stuck static initializer is still terminated by the
        // timeout-probe backstop and, after executor abandonment, by the
        // per-thread deadline checked below.
        if (Thread.currentThread().isInterrupted() && !isInStaticInitThrottled()) {
            // Not reset(): that starts a new test, and would make the code under test that
            // swallows this exception and goes on start over with a clean record of tripped
            // loops, see trippedLoops.
            interruptMemo.remove();
            clearCounters();
            throw new TooManyResourcesException(
                    "Loop terminated early: thread was interrupted (likely timeout)");
        }

        // Use this thread's own protected snapshot if it has been abandoned (see
        // protectedCounters), otherwise the list shared with the active thread.
        List<Long> active = activeCounters();

        // The list is shared with every other thread that runs code under test, and reset()
        // and the trip below clear it from whichever thread gets there. A thread left over
        // from an earlier test (the SUT's own background threads, which also pass through
        // here when the SUT's security manager is asked for a permission during
        // post-processing) can clear it between the growth and the increment, and
        // concurrent growth corrupts an ArrayList. So growth and increment are one step.
        long value;
        synchronized (active) {
            int size = active.size();
            for (int i = size; i <= index; i++) {
                active.add(0L);
            }
            value = active.get(index) + 1L;
            active.set(index, value);
        }

        try {
            // Static initializers are exempt from the iteration limit. A <clinit> that merely runs a long
            // loop is not stuck: building a large lookup table is ordinary code, and some libraries need far
            // more than the limit allows. Throwing here would make the JVM mark the class erroneous for the
            // lifetime of the classloader, so that class and every class reading from it fail with
            // NoClassDefFoundError for the rest of the run. A genuinely stuck initializer is still
            // terminated by time-gated guards that only fire once the test has already run out of budget:
            // the kill-switch probe in ExecutionTracer.checkTimeout(), the timed-out-class tracking in
            // ClassReInitializeExecutor, and the per-thread deadline armed when TestCaseExecutor abandons a
            // worker.
            //
            // Past the limit inside such an initializer, every further back-edge would otherwise pay for a
            // full stack walk, and no interrupt or kill switch can stop it (they are deliberately ignored in
            // a <clinit>). Walk the stack only on the iteration that crosses the limit and then only every
            // INTERRUPT_STATIC_INIT_RECHECK_INTERVAL-th time while it keeps finding a <clinit> (the
            // throttled, positive-only memo).
            //
            // The replay of an initializer by __STATIC_RESET() is exempt from the limit for
            // the same reason: it runs the very same loops, and an exception out of it leaves
            // the class half reset (its static fields zeroed, never reassigned), which breaks
            // every later test that reads them. Unlike for the limit, a stuck replay is still
            // stopped by an interrupt: the executor interrupts it once its time budget is
            // used up, so the interrupt checks above do not treat it as an initializer.
            if (value >= RuntimeSettings.maxNumberOfIterationsPerLoop && !isInStaticInitOrResetThrottled()) {
                interruptMemo.remove();
                clearCounters();
                // The counters start from zero again, so a loop whose body swallows the exception
                // would go round another full limit, and so on until the test times out. From
                // this loop's second trip on the exception gets through such handlers, see
                // trippedLoops.
                boolean repeated = recordTrip(index);
                throw new TooManyResourcesException("Loop has been executed more times than the allowed "
                        + RuntimeSettings.maxNumberOfIterationsPerLoop, repeated);
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
     * Called when we are about to throw because the thread was interrupted or the iteration
     * limit was exceeded, both of which stay true on every further back-edge. Answers
     * whether we are inside a static initializer, using a
     * throttled stack walk so that a hot loop does not pay for
     * {@link #isInStaticInit()} on every remaining back-edge.
     *
     * @return true if the interrupted loop is running inside a &lt;clinit&gt;
     */
    private boolean isInStaticInitThrottled() {
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

    /**
     * Like {@link #isInStaticInitThrottled()}, but also true while a static initializer is being
     * replayed by {@code __STATIC_RESET()}. Kept apart from it, with a memo of its own, because
     * only the iteration limit may ignore a replay, not the interrupt checks.
     */
    private boolean isInStaticInitOrResetThrottled() {
        InterruptMemo memo = interruptMemo.get();
        if (memo.inStaticInitOrReset && memo.checksSinceInitOrResetWalk < INTERRUPT_STATIC_INIT_RECHECK_INTERVAL) {
            memo.checksSinceInitOrResetWalk++;
            return true;
        }
        memo.inStaticInitOrReset = isInStaticInit() || isInStaticReset();
        memo.checksSinceInitOrResetWalk = 1;
        return memo.inStaticInitOrReset;
    }

    private boolean isInStaticInit() {
        for (StackTraceElement elem : new Throwable().getStackTrace()) {
            if (elem.getMethodName().startsWith("<clinit>")) {
                return true;
            }
        }
        return false;
    }

    private boolean isInStaticReset() {
        for (StackTraceElement elem : new Throwable().getStackTrace()) {
            if (elem.getMethodName().equals(ClassResetter.STATIC_RESET)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this worker's abandoned-initializer deadline has elapsed.
     * Before the deadline we avoid a stack walk on every loop back-edge.  Once
     * it expires, the walk distinguishes a worker that completed its initializer
     * from one that is still holding the class-initialization lock.  A positive
     * result deliberately leaves the marker installed: a catch-all in the
     * initializer may swallow the first timeout, and the next probe must still
     * terminate it.  The marker is removed once the worker is observed outside
     * the initializer, or opportunistically when the worker is dead.
     */
    private boolean abandonedStaticInitializerExpired() {
        Thread current = Thread.currentThread();
        Long deadline = abandonedStaticInitDeadlines.get(current);
        if (deadline == null) {
            return false;
        }
        long now = java.lang.System.nanoTime();
        // Compare by subtraction so a nanoTime wrap cannot make a one-second
        // deadline appear centuries away. The interval is far below half the
        // representable range, so this signed comparison is well-defined.
        if (deadline - now > 0) {
            return false;
        }
        if (!isInStaticInit()) {
            synchronized (abandonedStaticInitLock) {
                abandonedStaticInitDeadlines.remove(current, deadline);
                abandonedStaticInitArmed = !abandonedStaticInitDeadlines.isEmpty();
            }
            return false;
        }
        return true;
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
