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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;


/**
 * <p>
 * System class.
 * </p>
 *
 * @author fraser
 */
public class System {

    private static final Logger logger = LoggerFactory.getLogger(System.class);

    private static boolean wasTimeAccessed = false;

    /**
     * Default Java properties before we run the SUT.
     */
    private static final java.util.Properties defaultProperties;

    private static final Set<String> systemProperties;

    static {

        // We have to allow some system properties like "java.io.tmpdir",  "user.home",  "user.name"
        // as otherwise tests using the VFS at runtime might break
        // We are not including "user.dir" because that will break Jacoco and other instrumentation tools
        systemProperties = new HashSet<>(Arrays.asList("java.version", "java.vendor", "java.vendor.url", "java.home",
                "java.vm.specification.version", "java.vm.specification.vendor",
                "java.vm.specification.name", "java.vm.version", "java.vm.vendor", "java.vm.name",
                "java.specification.version", "java.specification.vendor",
                "java.specification.name", "java.class.version", "java.class.path", "java.library.path",
                "java.compiler", "java.ext.dirs",
                "os.name", "os.arch", "os.version", "file.separator", "path.separator", "line.separator",
                "java.endorsed.dirs",
                "awt.toolkit", "java.awt.graphicsenv", "java.awt.printerjob", "java.vm.info", "java.runtime.version",
                "java.runtime.name"));

        java.util.Properties prop = null;
        try {
            prop = (java.util.Properties) java.lang.System.getProperties().clone();
        } catch (Exception e) {
            logger.error("Error while initializing System: " + e.getMessage());
        }
        defaultProperties = prop;
    }


    /**
     * If SUT changed some properties, we need to re-set the default values.
     */
    private static volatile boolean needToRestoreProperties;

    /**
     * Keep track of which System properties were read.
     */
    private static final Set<String> readProperties = new LinkedHashSet<>();

    /**
     * Restore to their original values all the properties that have
     * been modified during test execution.
     */
    public static void restoreProperties() {
        /*
         * The synchronization is used to avoid (if possible) a SUT thread to modify a property
         * just immediately after we restore them. this could
         * actually happen if this method is called while a SUT thread is executing a permission check
         */
        synchronized (defaultProperties) {
            if (needToRestoreProperties) {
                java.lang.System.setProperties((java.util.Properties) defaultProperties.clone());
                needToRestoreProperties = false;
            }
        }
    }

    /**
     * Checks if any system property was written during the current execution.
     *
     * @return true if any property was written, false otherwise
     */
    public static boolean wasAnyPropertyWritten() {
        return needToRestoreProperties;
    }

    public static boolean isSystemProperty(String property) {
        return systemProperties.contains(property);
    }

    /**
     * Handles property permissions, tracking writes and potentially triggering property restoration.
     *
     * @param perm the property permission to handle
     * @return true if the permission is handled successfully
     */
    public static boolean handlePropertyPermission(PropertyPermission perm) {
        /*
         * we allow both writing and reading any properties. But, if SUT writes anything, then we need
         * to re-store the values to their default. this
         * is very important, otherwise: 1) test cases might have side effects on each other
         * 2) SUT might change properties that are used by EvoSuite
         */

        if (readProperties == null) {
            /*
             * this can happen when readProperties is initialized in the static body
             * and security manager is on
             */
            return true;
        }

        if (perm.getActions().contains("write")) {

            if (!RuntimeSettings.mockJVMNonDeterminism) {
                // We cannot restore these properties to ensure cross-OS compatibility,
                // so they can't be written to
                return !isSystemProperty(perm.getName());
            } else {
                // As we do not restore these properties to ensure cross-OS compatibility
                // they should not be written to
                // ...but that would break compatibility with exiting test sets, so ignoring it for now.
                // This risks potentially unstable tests.
                //
                // if(isSystemProperty(perm.getName())) {
                //    return false;
                // }
            }

            synchronized (defaultProperties) {
                needToRestoreProperties = true;
            }
        } else {
            String var = perm.getName();
            // We cannot restore these properties to ensure cross-OS compatibility, so they can't be written to
            if (!isSystemProperty(var)) {
                synchronized (readProperties) {
                    readProperties.add(var);
                }
            }
        }

        return true;
    }

    /**
     * Returns a copy of all system properties that have been read during the current execution.
     *
     * @return a set of property names
     */
    public static Set<String> getAllPropertiesReadSoFar() {
        Set<String> copy;

        synchronized (readProperties) {
            copy = new LinkedHashSet<>(readProperties);
        }

        return copy;
    }

    /**
     * Restore a previously captured property-read set.
     *
     * <p>The set is intentionally cumulative across ordinary per-test runtime
     * resets so reads performed by static initializers are retained. Multi-phase
     * clients can use this method to discard reads introduced by an auxiliary
     * phase while preserving the reads observed during the canonical execution.</p>
     *
     * @param properties snapshot returned by {@link #getAllPropertiesReadSoFar()}
     */
    public static void restorePropertiesReadSoFar(Set<String> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Property-read snapshot must not be null");
        }
        synchronized (readProperties) {
            readProperties.clear();
            readProperties.addAll(properties);
        }
    }

    /**
     * <p>
     * This exception tells the test execution that it should stop at this point.
     * </p>
     *
     * <p>
     * Note that it extends {@code Error}, as we need something that is
     * unchecked.
     * </p>
     */
    public static class SystemExitException extends Error {

        private static final long serialVersionUID = 1L;

    }

    /**
     * Replacement function for System.exit.
     *
     * @param status a int.
     */
    public static void exit(int status) {
        wasTimeAccessed = true;

        /*
         * TODO: Here we could handle the calls to the JVM shutdown hooks, if any is present
         */

        throw new SystemExitException();
    }

    /**
     * The mocked clock does not run on its own: it only moves forward when the
     * SUT actually sleeps (see {@link org.evosuite.runtime.mock.java.lang.MockThread#sleep(long)},
     * which calls {@link #advanceCurrentTimeMillis(long)}), or when explicitly
     * set via {@link #setCurrentTimeMillis(long)}. Reading it via
     * {@link #currentTimeMillis()} or {@link #nanoTime()} never changes it.
     * This makes two clock reads with no intervening sleep return the same
     * value, which many SUTs rely on (e.g. comparing the creation timestamps
     * of two objects built back-to-back).
     */
    // Initialised to 2014-02-14, 20:21
    private static long currentTime = 1392409281320L;

    /**
     * Replacement function for System.currentTimeMillis.
     *
     * @return a long.
     */
    public static synchronized long currentTimeMillis() {
        wasTimeAccessed = true;
        return currentTime;
    }

    /**
     * Advances the mocked clock by the given amount. Used to simulate the
     * passage of time when the SUT sleeps, without EvoSuite actually having
     * to wait that long in real time.
     *
     * @param millis amount of time (in milliseconds) to move the mocked clock
     *               forward by. Non-positive values are ignored.
     */
    public static synchronized void advanceCurrentTimeMillis(long millis) {
        if (millis > 0) {
            currentTime += millis;
        }
    }

    /**
     * Get time without modifying whether the time was accessed.
     * This is important as otherwise the use of VFS would always
     * mark the time as accessed.
     *
     * @return Current time in milliseconds
     */
    public static synchronized long getCurrentTimeMillisForVFS() {
        //wasTimeAccessed = true;
        return currentTime;
    }

    /**
     * Mocked identity hash codes, keyed by the real identity hash of the object. An object
     * keeps its mocked value for as long as it is reachable: SUT code stores such objects as
     * keys of {@code HashMap}s that live in static state across many tests (110_firebird's
     * {@code GDSFactory.typeToPluginMap} is keyed by {@code GDSType}, which defines no
     * {@code hashCode()}), and a key whose hash changes after {@code put()} is searched for in
     * the wrong bucket and never found again. The entries are weak so that objects that died
     * with their test do not accumulate over a run; {@link #resetRuntime()} only restarts the
     * counter, so the objects a test creates still get the same small, deterministic values
     * every time the test runs.
     */
    private static final Map<Integer, List<HashKey>> hashKeys = new HashMap<>();
    private static final ReferenceQueue<Object> deadHashKeys = new ReferenceQueue<>();
    private static int nextHashKey = 1;

    private static final class HashKey extends WeakReference<Object> {
        private final int realId;
        private final int mockedId;

        HashKey(Object referent, int realId, int mockedId) {
            super(referent, deadHashKeys);
            this.realId = realId;
            this.mockedId = mockedId;
        }
    }

    private static void expungeDeadHashKeys() {
        Reference<?> ref;
        while ((ref = deadHashKeys.poll()) != null) {
            HashKey dead = (HashKey) ref;
            List<HashKey> entries = hashKeys.get(dead.realId);
            if (entries != null && entries.remove(dead) && entries.isEmpty()) {
                hashKeys.remove(dead.realId);
            }
        }
    }

    /**
     * Registers an object to ensure it has a stable mocked identity hash code.
     *
     * @param o the object to register
     */
    public static void registerObjectForIdentityHashCode(Object o) {
        identityHashCode(o);
    }

    /**
     * Replacement for {@link java.lang.System#identityHashCode(Object)}.
     *
     * @param o the object for which to get the identity hash code
     * @return the mocked identity hash code
     */
    public static int identityHashCode(Object o) {
        if (o == null) {
            return 0;
        }

        synchronized (hashKeys) {
            expungeDeadHashKeys();
            int realId = java.lang.System.identityHashCode(o);
            List<HashKey> entries = hashKeys.computeIfAbsent(realId, k -> new ArrayList<>(1));
            for (HashKey entry : entries) {
                if (entry.get() == o) {
                    return entry.mockedId;
                }
            }
            int mockedId = nextHashKey++;
            entries.add(new HashKey(o, realId, mockedId));
            return mockedId;
        }
    }

    /**
     * Replacement for a call to {@link Object#hashCode()}.
     *
     * <p>The instrumentation replaces every {@code invokevirtual Object.hashCode()} it
     * sees, and it sees one wherever the receiver's static type is {@code Object} - a
     * generic {@code K key} erases to exactly that. The receiver at run time is usually
     * something else: a String, an Integer, a value class. Answering with the mocked
     * identity id for those made two equal keys hash differently, so any hash table in
     * instrumented code stopped finding its own entries. Guava's {@code RegularImmutableMap}
     * inside Closure lost "desc" that way: {@code putAll} copied the entry, {@code
     * containsKey("desc")} on another String instance said no, and the builder threw
     * "duplicate key". Only an object whose class leaves {@code hashCode()} to
     * {@code Object} gets the deterministic id; everything else gets its own.</p>
     *
     * @param o the receiver of the call
     * @return the receiver's hash code
     * @throws NullPointerException if the receiver is null, as the original call would
     */
    public static int hashCode(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        if (overridesObjectMethod(o.getClass(), "hashCode")) {
            return o.hashCode();
        }
        return identityHashCode(o);
    }

    /**
     * Replacement for a call to {@link Object#toString()}: the receiver's own
     * {@code toString()} when its class defines one, otherwise the default form with the
     * stable mocked identity hash code in place of the JVM's. See {@link #hashCode(Object)}
     * for why the dynamic type has to decide.
     *
     * @param o the object to get the string representation for
     * @return the string representation
     * @throws NullPointerException if the object is null
     */
    public static String toString(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        if (overridesObjectMethod(o.getClass(), "toString")) {
            return o.toString();
        }

        return o.getClass().getName() + "@" + String.format("%010d", identityHashCode(o));
    }

    /*
     * ClassValue associates its values with the Class object itself (effectively a weak key
     * managed by the JVM), so an entry here does not keep a disposable JUnit-check class
     * loader's classes reachable: once nothing else references the Class, both it and this
     * cached Boolean can be collected together. A plain Map<Class<?>, Boolean> would hold the
     * Class strongly forever, which is what pinned whole SUT class graphs loaded through
     * temporary loaders. No explicit clearing from resetRuntime()/fullReset() is needed.
     */
    private static final ClassValue<Boolean> overridesHashCode = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> clazz) {
            return declaresObjectMethod(clazz, "hashCode");
        }
    };
    private static final ClassValue<Boolean> overridesToString = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> clazz) {
            return declaresObjectMethod(clazz, "toString");
        }
    };

    private static boolean declaresObjectMethod(Class<?> clazz, String methodName) {
        try {
            return clazz.getMethod(methodName).getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean overridesObjectMethod(Class<?> clazz, String methodName) {
        ClassValue<Boolean> cache = "hashCode".equals(methodName) ? overridesHashCode : overridesToString;
        return cache.get(clazz);
    }

    /**
     * Replacement function for System.currentTimeMillis.
     *
     * @return a long.
     */
    public static synchronized long nanoTime() {
        wasTimeAccessed = true;
        return currentTime * 1000L;
    }

    /**
     * Replacement function for for Runtime.freeMemory().
     *
     * @return a long.
     */
    public static long freeMemory() {
        return 0L;
    }

    /**
     * Replacement function for for Runtime.maxMemory().
     *
     * @return a long.
     */
    public static long maxMemory() {
        return 0L;
    }

    /**
     * Replacement function for for Runtime.totalMemory().
     *
     * @return a long.
     */
    public static long totalMemory() {
        return 0L;
    }

    /**
     * Replacement function for for Runtime.availableProcessors().
     *
     * @return a int.
     */
    public static int availableProcessors() {
        return 0;
    }


    /**
     * Allow setting the time. This is how a test seeds the clock the SUT will read,
     * so any value must be accepted: a SUT comparing the time against a small
     * constant is only reachable by setting the clock below where it started. Time
     * moves forward on its own only through {@link #advanceCurrentTimeMillis(long)}.
     *
     * @param time a long.
     */
    public static synchronized void setCurrentTimeMillis(long time) {
        currentTime = time;
    }

    /**
     * TODO: Reflection and class initialisation may cause setSecurityManager to be called by the SUT.
     * Until this is resolved, we avoid this using instrumentation.
     *
     * @param manager a {@link java.lang.SecurityManager} object.
     */
    public static void setSecurityManager(SecurityManager manager) {
        throw new SecurityException("Permission Denied");
    }

    /**
     * Reset runtime to initial state.
     */
    public static void resetRuntime() {
        currentTime = 1392409281320L; // 2014-02-14, 20:21
        wasTimeAccessed = false;
        synchronized (hashKeys) {
            // Objects that survive the test keep their mocked hash (see hashKeys); only
            // the numbering of objects created from now on starts over.
            nextHashKey = 1;
        }
        restoreProperties();
        needToRestoreProperties = false;
        //readProperties.clear(); //we cannot reset read properties here
    }

    /**
     * Fully reset the state, not only the one related to
     * latest test case execution.
     */
    public static void fullReset() {
        resetRuntime();

        /*
         * System properties have to be treated very specially because
         * we want to keep track of them when they are accessed in
         * static blocks, and also from non-instrumented classes
         */
        synchronized (readProperties) {
            readProperties.clear();
        }
    }

    /**
     * Getter to check whether the runtime replacement for time was accessed during test
     * execution.
     *
     * @return a boolean.
     */
    public static boolean wasTimeAccessed() {
        return wasTimeAccessed;
    }
}
