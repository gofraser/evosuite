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
package org.evosuite.runtime.mock.java.lang;

import org.apache.commons.lang3.SystemUtils;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.annotation.EvoSuiteExclude;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.runtime.thread.ThreadCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Threads are very complex to handle.
 *
 * <p>For the moment, we mock only certain aspects: TODO.
 *
 * @author arcuri
 */
public class MockThread extends Thread implements OverrideMock {

    static {
        final Integer javaVersion = Integer.valueOf(SystemUtils.JAVA_VERSION.split("\\.")[0]);
        if (javaVersion < 11) {
            try {
                Method destroy = MockThread.class.getMethod("destroy");
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
    }

    // ----- mock internals -------

    private static final Logger logger = LoggerFactory.getLogger(MockThread.class);

    private static final Map<Integer, Long> threadMap = new ConcurrentHashMap<>();

    /**
     * Counts how many times {@link #sleep(long)} has been called (successfully)
     * by any thread during the current test execution.  When the count exceeds
     * {@link #MAX_SLEEP_CALLS_PER_TEST}, {@link #sleep(long)} throws
     * {@link TooManyResourcesException} to break out of infinite polling loops
     * in uninstrumented library code that the
     * {@link org.evosuite.runtime.LoopCounter} cannot reach.
     *
     * <p>Uses AtomicLong instead of ThreadLocal so that {@link #reset()} clears
     * the budget for all threads, not just the calling thread.</p>
     */
    private static final AtomicLong sleepCallCount = new AtomicLong(0L);

    /**
     * Maximum number of {@link #sleep(long)} calls allowed per test execution
     * before {@link #sleep(long)} gives up and throws {@link TooManyResourcesException}.
     * This is a hard ceiling on top of the real-time regimes below — it exists
     * for uninstrumented library code (e.g. HSQLDB {@code Server.start()}) that
     * the {@link org.evosuite.runtime.LoopCounter} cannot reach, so that such a
     * loop cannot spin forever even if it somehow escapes EvoSuite's per-test
     * timeout (see the three real-sleep regimes below).
     */
    private static final long MAX_SLEEP_CALLS_PER_TEST = 10_000L;

    /**
     * {@code sleep(long)} performs a genuine {@link Thread#sleep(long)} in three
     * regimes, tracked per test execution (reset alongside {@link #sleepCallCount}
     * by {@link #reset()}), instead of either always sleeping the full requested
     * duration (too slow — see the history below) or never sleeping at all (lets a
     * spinning SUT thread starve another thread contending for the same lock — see
     * the history below):
     *
     * <ol>
     * <li>While the cumulative *real* time this test has spent sleeping is below
     * {@link #REAL_SLEEP_BUDGET_MS} (200ms), each call really sleeps up to
     * {@link #MAX_REAL_SLEEP_PER_CALL_MS} (50ms) — this restores 1.2.0's per-call
     * real-sleep cap, so a SUT that legitimately sleeps a handful of times (e.g.
     * 55_lavalamp's Blink, 10 calls/test) behaves close to how it would on a real
     * JVM.</li>
     * <li>Once that budget is exhausted, every further call in the same test only
     * really sleeps {@link #MIN_REAL_SLEEP_MS} (1ms) — not zero, and deliberately
     * not just {@link Thread#yield()}: a background AEThread-style loop that calls
     * {@code sleep()} on every iteration (e.g. 104_vuze's
     * {@code TRTrackerServerImpl.timerLoop()}, which sleeps 60s between iterations
     * and, with no real wait at all, was observed via jstack spinning at ~98% CPU
     * and permanently starving a lower-priority thread blocked entering the same
     * {@code AEMonitor}) is thereby capped at ~1ms of real cost per iteration
     * forever, cheap enough not to matter, but still real enough that the OS
     * scheduler gets a genuine, repeated chance to run the thread it would
     * otherwise starve. {@code yield()} alone is not enough here — it is only
     * advisory and does not reliably let a blocked waiter run.</li>
     * <li>The {@link #MAX_SLEEP_CALLS_PER_TEST} call-count cap above still applies
     * on top of both regimes and throws — but the *time* budget itself never
     * throws, it only downgrades the per-call cost from 50ms to 1ms. That is the
     * key difference from both the 200ms *cumulative* budget removed by
     * 91aacb37c (which threw, and cut off 55_lavalamp's Blink after its 5th call)
     * and the "no real sleep at all" replacement that budget was replaced with
     * (which let a background sleep-loop spin unthrottled): once a test's SUT
     * threads are into the 1ms-per-call regime, a loop that calls sleep() a lot
     * costs at most ~1ms per iteration, so it is EvoSuite's own per-test timeout
     * (a few seconds) — not this cap — that bounds how long a single evaluation
     * of a pathological polling loop can take, exactly as in 1.2.0.</li>
     * </ol>
     */
    private static final long REAL_SLEEP_BUDGET_MS = 200L;

    /**
     * Upper bound (in real milliseconds) on the genuine {@link Thread#sleep(long)}
     * each mocked {@link #sleep(long)} call performs while under
     * {@link #REAL_SLEEP_BUDGET_MS} — see {@link #REAL_SLEEP_BUDGET_MS}'s doc.
     */
    private static final long MAX_REAL_SLEEP_PER_CALL_MS = 50L;

    /**
     * The real sleep every call performs once {@link #REAL_SLEEP_BUDGET_MS} has
     * been exhausted — see {@link #REAL_SLEEP_BUDGET_MS}'s doc.
     */
    private static final long MIN_REAL_SLEEP_MS = 1L;

    /**
     * Cumulative *real* milliseconds this test execution has spent inside the
     * real {@link Thread#sleep(long)} calls {@link #sleep(long)} performs — see
     * {@link #REAL_SLEEP_BUDGET_MS}. Reset alongside {@link #sleepCallCount}.
     */
    private static final AtomicLong cumulativeRealSleepMillis = new AtomicLong(0L);

    public static void reset() {
        threadMap.clear();
        sleepCallCount.set(0L);  // AtomicLong — visible to all threads
        cumulativeRealSleepMillis.set(0L);
    }

    private boolean isSutRelated() {
        String sut = RuntimeSettings.className;
        String threadName = this.getClass().getName();
        String targetName = target == null ? null : target.getClass().getName();

        /*
            Note: this check would not recognize code like:

            Thread t = new Thread(); t.start();

            however, as it does nothing, no point in starting it anyway
         */

        return match(sut, threadName) || match(sut, targetName);
    }

    private boolean match(String sut, String other) {
        if (other == null || other.length() < sut.length()) {
            return false;
        }
        if (other.length() == sut.length()) {
            //is the thread the SUT itself?
            return other.equals(sut);
        } else {
            //anonymous or internal class of the SUT
            return other.startsWith(sut + "$");
        }
    }

    /**
     * A copy of the private field in superclass.
     */
    private Runnable target;

    // ------ public static fields --------

    public static final int MIN_PRIORITY = 1;
    public static final int NORM_PRIORITY = 5;
    public static final int MAX_PRIORITY = 10;

    // ------ static  methods  --------

    /**
     * Returns a reference to the currently executing thread object.
     *
     * @return the currently executing thread.
     */
    public static Thread currentThread() {
        return Thread.currentThread();
    }

    @EvoSuiteExclude
    public static void yield() {
        Thread.yield();
    }

    /**
     * Causes the currently executing thread to sleep (temporarily cease execution)
     * for the specified number of milliseconds.
     *
     * @param millis the length of time to sleep in milliseconds
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @EvoSuiteExclude
    public static void sleep(long millis) throws InterruptedException {
        // A real sleep below already throws InterruptedException when
        // interrupted, so no need to special-case Thread.interrupted() here
        // as long as some real sleep always runs — see below.

        // Uninstrumented library code (e.g., HSQLDB Server.start()) may sit in
        // a polling loop calling Thread.sleep(). The LoopCounter can't break
        // these loops because no checkLoop() was inserted. We therefore cap
        // the number of sleep() calls a test is allowed to make; once the cap
        // is hit we throw to break out of the loop. This cap never moves —
        // see REAL_SLEEP_BUDGET_MS's doc for how the *cost* of each call
        // changes instead.
        boolean overCap = millis > 0 && sleepCallCount.incrementAndGet() > MAX_SLEEP_CALLS_PER_TEST;

        // Advance the mocked clock by the requested amount so that SUT code
        // observing System.currentTimeMillis()/nanoTime() before and after the
        // sleep sees time having passed, without EvoSuite actually waiting
        // that long.
        org.evosuite.runtime.System.advanceCurrentTimeMillis(millis);

        if (millis <= 0) {
            // Nothing was really requested; let the real Thread.sleep(millis)
            // handle the millis == 0 (near no-op) / millis < 0
            // (IllegalArgumentException) cases exactly like the real method.
            Thread.sleep(millis);
            return;
        }

        // Perform a real sleep instead of just yielding. A pure
        // Thread.yield() is only advisory — it does not necessarily take the
        // calling thread off the run queue, and does not reliably let a
        // thread blocked entering a monitor actually run — so a SUT
        // background thread whose mocked "sleep" period never really elapses
        // (e.g. a periodic AEThread in 104_vuze's TRTrackerServerImpl that
        // sleeps 60s between loop iterations) turned into a genuine CPU-bound
        // spin: thousands of iterations per second, each re-acquiring/
        // releasing some lock that another, lower-priority thread was also
        // trying to acquire. That other thread was starved indefinitely —
        // confirmed with jstack as TEST_EXECUTION_THREAD_0 permanently
        // BLOCKED entering ConfigurationManager's AEMonitor, while the
        // spinning timer thread showed ~98% CPU re-entering the same monitor
        // — even though nothing was technically deadlocked. EvoSuite then
        // force-stopped the blocked thread, sometimes while it held an
        // unrelated java.util.concurrent lock that Thread.stop() cannot
        // release, wedging every later test.
        //
        // But always sleeping up to MAX_REAL_SLEEP_PER_CALL_MS is also wrong:
        // with only the call-count cap above (no cumulative *time* budget), a
        // SUT that sleeps in a loop would pay that cost on every call of
        // every test evaluation, not just once — e.g. a class whose
        // uninstrumented polling loop calls sleep() a few hundred times would
        // burn real time on every single individual the search evaluates,
        // collapsing the number of evaluations the search budget affords (the
        // 200ms *cumulative* budget 91aacb37c removed existed for exactly
        // this reason, and its removal is what let sleep() regress to never
        // sleeping at all in the first place). So: only the first
        // REAL_SLEEP_BUDGET_MS of *real* sleep per test gets the full
        // per-call cap; after that this test's remaining sleep() calls fall
        // back to a fixed, minimal real sleep, cheap enough not to matter but
        // still real enough to starve nobody. See REAL_SLEEP_BUDGET_MS's doc
        // for the three regimes in full.
        if (Thread.currentThread() instanceof MockThread) {
            // A thread the SUT started runs alongside the test, so a real pause
            // costs the search nothing, and a short one is harmful: vuze's
            // TrackerServer timer loop asks for 60 s, does ~10 ms of config
            // lookups under ConfigurationManager's AEMonitor per iteration, and
            // with a 1 ms pause held that monitor almost continuously while the
            // test thread constructing a second server waited for it, was
            // declared stuck and force-stopped with a ReentrantLock in hand.
            Thread.sleep(Math.min(millis, MAX_REAL_SLEEP_PER_CALL_MS));
        } else if (!overCap && cumulativeRealSleepMillis.get() < REAL_SLEEP_BUDGET_MS) {
            long cappedMillis = Math.min(millis, MAX_REAL_SLEEP_PER_CALL_MS);
            Thread.sleep(cappedMillis);
            cumulativeRealSleepMillis.addAndGet(cappedMillis);
        } else {
            Thread.sleep(MIN_REAL_SLEEP_MS);
        }

        // Break out of a loop that has sleep()ed too often. InterruptedException
        // won't work here because server-style loops (like HSQLDB Server.start())
        // typically catch InterruptedException and keep looping;
        // TooManyResourcesException is already used by LoopCounter for the same
        // purpose. The real pause above deliberately happens first: a loop that
        // also swallows this exception (vuze's TRTrackerServerImpl timer loop
        // catches Throwable) would otherwise turn into a busy loop the moment
        // the cap is reached, and a busy loop holding a monitor starves every
        // other thread that needs it.
        if (overCap) {
            throw new TooManyResourcesException(
                    "MockThread: exceeded " + MAX_SLEEP_CALLS_PER_TEST
                            + " sleep() calls in a single test");
        }
    }

    @EvoSuiteExclude
    public static void sleep(long millis, int nanos)
            throws InterruptedException {
        MockThread.sleep(millis);
    }

    /**
     * Tests whether the current thread has been interrupted.
     *
     * @return true if the current thread has been interrupted; false otherwise.
     */
    public static boolean interrupted() {
        return Thread.interrupted();
    }

    /**
     * Returns an estimate of the number of active threads in the current thread's thread group and its subgroups.
     *
     * @return an estimate of the number of active threads in the current thread's thread group and in any other
     *         thread group that has the current thread's thread group as an ancestor
     */
    public static int activeCount() {
        return Thread.activeCount();
    }

    public static int enumerate(Thread[] tarray) {
        return Thread.enumerate(tarray);
    }

    /**
     * Prints a stack trace of the current thread to the standard error stream.
     */
    public static void dumpStack() {
        if (!MockFramework.isEnabled()) {
            Thread.dumpStack();
        } else {
            new MockException("Stack trace").printStackTrace();
        }
    }

    /**
     * Returns a map of stack traces for all live threads.
     *
     * @return a {@code Map} from {@code Thread} to an array of {@code StackTraceElement} that represents the
     *         stack dump of the corresponding thread.
     */
    public static Map<Thread, StackTraceElement[]> getAllStackTraces() {
        if (!MockFramework.isEnabled()) {
            return Thread.getAllStackTraces();
        }
        //get actual running threads, and then replace stack traces

        //this will ask for permissions, but we grant it anyway
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        Map<Thread, StackTraceElement[]> m = new HashMap<>(threads.size());
        for (Thread t : threads) {
            m.put(t, MockThrowable.getDefaultStackTrace());
        }

        return m;
    }

    public static boolean holdsLock(Object obj) {
        return Thread.holdsLock(obj);
    }

    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        Thread.setDefaultUncaughtExceptionHandler(eh);
    }

    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler() {
        return Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    // -------- constructors ---------

    /**
     * Allocates a new {@code MockThread} object.
     */
    public MockThread() {
        super();
        mockSetup(null);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param target the object whose {@code run} method is invoked when this thread is started.
     */
    public MockThread(Runnable target) {
        super(target);
        this.target = target;
        mockSetup(null);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param group  the thread group.
     * @param target the object whose {@code run} method is invoked when this thread is started.
     */
    public MockThread(ThreadGroup group, Runnable target) {
        super(group, target);
        this.target = target;
        mockSetup(null);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param name the name of the new thread
     */
    public MockThread(String name) {
        super(name);
        mockSetup(name);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param group the thread group.
     * @param name  the name of the new thread
     */
    public MockThread(ThreadGroup group, String name) {
        super(group, name);
        mockSetup(name);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param target the object whose {@code run} method is invoked when this thread is started.
     * @param name   the name of the new thread
     */
    public MockThread(Runnable target, String name) {
        super(target, name);
        this.target = target;
        mockSetup(name);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param group  the thread group.
     * @param target the object whose {@code run} method is invoked when this thread is started.
     * @param name   the name of the new thread
     */
    public MockThread(ThreadGroup group, Runnable target, String name) {
        super(group, target, name);
        this.target = target;
        mockSetup(name);
    }

    /**
     * Allocates a new {@code MockThread} object.
     *
     * @param group     the thread group.
     * @param target    the object whose {@code run} method is invoked when this thread is started.
     * @param name      the name of the new thread
     * @param stackSize the desired stack size for the new thread, or zero to indicate that
     *                  this parameter is to be ignored.
     */
    public MockThread(ThreadGroup group, Runnable target, String name,
                      long stackSize) {
        super(group, target, name, stackSize);
        this.target = target;
        mockSetup(name);
    }

    /**
     * Allocates a new {@code MockThread} object.
     * The inheritThreadLocals flag is ignored for compatibility on older JDK APIs.
     *
     * @param group                the thread group.
     * @param target               the object whose {@code run} method is invoked.
     * @param name                 the name of the new thread.
     * @param stackSize            desired stack size.
     * @param inheritThreadLocals  whether inheritable thread locals are inherited.
     */
    public MockThread(ThreadGroup group, Runnable target, String name,
                      long stackSize, boolean inheritThreadLocals) {
        super(group, target, name, stackSize);
        this.target = target;
        mockSetup(name);
    }

    private void mockSetup(String name) {
        if (!MockFramework.isEnabled()) {
            return;
        }

        if (name == null) {
            /*
                If SUT did not specify any name, we need
                to change the one automatically given by the JVM,
                as it could be non-deterministic
             */
            setName("MockThread-" + getId());
        }
    }

    // ---------------

    @Override
    @EvoSuiteExclude
    public synchronized void start() {

        if (!MockFramework.isEnabled()) {
            super.start();
            return;
        }

        if (!isSutRelated()) {
            //no point in starting those 3rd party threads
            return;
        }

        ThreadCounter.getInstance().checkIfCanStartNewThread();

        /*
            TODO: we could rather buffer them like we did for hooks, and execute them at the end.
            We could even have methods in the test-cluster to execute some of those threads till they yield
            within the test case
         */
        super.start();
    }

    @Override
    public void run() {
        super.run();
    }

    @Override
    @EvoSuiteExclude
    public void interrupt() {
        super.interrupt();
    }

    @Override
    public boolean isInterrupted() {
        return super.isInterrupted();
    }

    // @Override
    // No @Override to guarantee Java 11 compatibility
    @EvoSuiteExclude
    public void destroy() {
        // inlined super.destroy()
        throw new NoSuchMethodError();
    }

    @Override
    public String toString() {
        return super.toString();
    }

    @Override
    public ClassLoader getContextClassLoader() {
        return super.getContextClassLoader();
    }

    @Override
    public void setContextClassLoader(ClassLoader cl) {
        super.setContextClassLoader(cl);
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        if (!MockFramework.isEnabled()) {
            return super.getStackTrace();
        }
        return MockThrowable.getDefaultStackTrace();
    }

    @Override
    public long getId() {
        if (!MockFramework.isEnabled()) {
            return super.getId();
        }

        synchronized (threadMap) {
            int identity = java.lang.System.identityHashCode(this);
            if (!threadMap.containsKey(identity)) {
                threadMap.put(identity, (long) threadMap.size());
            }
            return threadMap.get(identity);
        }
    }

    @Override
    public State getState() {
        return super.getState();
    }

    @Override
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return super.getUncaughtExceptionHandler();
    }

    @Override
    public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        super.setUncaughtExceptionHandler(eh);
    }

    // ----- StaticReplacementMethods  ---------

    // Handled in the constructors
    // public final void setName(String name) {   }
    // public final String getName() {}

    //TODO
    // they are final
    /*public final void stop() {
        stop(new ThreadDeath());
    }
    public final synchronized void stop(Throwable obj) {
    ...
    }

    //public final native boolean isAlive();

    @Deprecated
    public final void suspend() {
        checkAccess();
        suspend0();
    }

    @Deprecated
    public final void resume() {
        checkAccess();
        resume0();
    }

    public final void setPriority(int newPriority) {
    }

    public final int getPriority() {
        return priority;
    }

    public final ThreadGroup getThreadGroup() {
        return group;
    }

    @Deprecated
    public native int countStackFrames();

    public final synchronized void join(long millis)
            throws InterruptedException {
    }

    public final synchronized void join(long millis, int nanos)
            throws InterruptedException {

    }

    public final void join() throws InterruptedException {
        join(0);
    }

    public final void setDaemon(boolean on) {
    }

    public final boolean isDaemon() {
        return daemon;
    }

    public final void checkAccess() {
    }
*/

}
