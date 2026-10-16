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
package org.evosuite.runtime.classhandling;

import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * This class resets the static fields of a given class by invoking the &lt;clinit&gt; class initializer.
 * In order to re-invoke the &lt;clinit&gt; this is duplicated with the method name "__STATIC_RESET".
 *
 * @author galeotti
 */
public class ClassResetter {

    private static final Logger logger = LoggerFactory.getLogger(ClassResetter.class);

    /**
     * The name of the instrumented duplication of the class initializer &lt;clinit&gt;.
     */
    public static final String STATIC_RESET = "__STATIC_RESET";

    /**
     * Singleton instance of this class.
     */
    private static final ClassResetter instance = new ClassResetter();

    /**
     * Class-name prefixes whose {@code __STATIC_RESET()} method is never
     * invoked. Use for libraries whose {@code <clinit>} body is not safe to
     * replay — typically because it builds a process-wide interned table or
     * descriptor pool with cross-class invariants that the per-class field
     * reset cannot restore.
     *
     * <p>Skipping the reset means the class keeps the static state populated
     * during its initial {@code <clinit>}. For these libraries that is the
     * desired behavior — their descriptor/registry data is effectively
     * immutable after initial load, so the test cannot meaningfully mutate
     * it, and a reset attempt would only poison the class via a half-completed
     * replay.
     */
    private static final List<String> SKIP_RESET_PREFIXES = Arrays.asList(
            // Generated descriptor pools maintain a process-wide singleton
            // registry in com.google.protobuf.Descriptors. Replaying the
            // generated DescriptorProtos.<clinit> against a partially-reset
            // registry throws IndexOutOfBoundsException in the middle of
            // rebuilding accessor tables, leaving the class half-initialised
            // for the rest of the run.
            "com.google.protobuf."
    );

    private ClassLoader loader;

    private final Map<ClassLoader, Map<String, Method>> resetMethodCache;

    /**
     * Classes whose {@code __STATIC_RESET()} has already thrown once. After
     * the first failure the class is in a half-reset state (fields nulled,
     * {@code <clinit>} replay aborted mid-way); re-invoking the reset method
     * does not undo that and only spams warnings. Keyed by class loader so a
     * fresh load through a new {@code InstrumentingClassLoader} gets a clean
     * slate.
     */
    private final Map<ClassLoader, Set<String>> poisonedClasses;

    private ClassResetter() {
        resetMethodCache = new HashMap<>();
        poisonedClasses = Collections.synchronizedMap(new WeakHashMap<>());
    }

    /**
     * Return singleton instance.
     *
     * @return the singleton instance
     */
    public static ClassResetter getInstance() {
        return instance;
    }

    /**
     * Sets the class loader to be used for loading and resetting classes.
     *
     * @param loader the class loader
     * @throws IllegalArgumentException if the loader is null
     */
    public void setClassLoader(ClassLoader loader) throws IllegalArgumentException {
        if (loader == null) {
            throw new IllegalArgumentException("Null class loader");
        }
        this.loader = loader;
    }

    /**
     * Forgets everything cached for the given class loader. The cached reset methods belong to
     * classes defined by that loader, so as long as they are cached the loader, and every class
     * it defined, stays reachable. If the loader is the current one it is dropped as well; the
     * next reset then needs a new {@link #setClassLoader(ClassLoader)} call.
     *
     * @param classLoader the class loader that is no longer used
     */
    public synchronized void releaseClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        resetMethodCache.remove(classLoader);
        poisonedClasses.remove(classLoader);
        if (loader == classLoader) {
            loader = null;
        }
    }


    /**
     * Only log once for a class.
     *
     * @param className the name of the class
     * @param msg       the message to log
     */
    public synchronized void logWarn(String className, String msg) {
        AtMostOnceLogger.warn(logger, msg);
    }

    private void cacheResetMethod(String classNameWithDots) {

        if (!resetMethodCache.containsKey(loader)) {
            resetMethodCache.put(loader, new HashMap<>());
        }

        Map<String, Method> methodMap = resetMethodCache.get(loader);
        if (methodMap.containsKey(classNameWithDots)) {
            return;
        }

        try {
            Class<?> clazz = loader.loadClass(classNameWithDots);

            if (clazz.isInterface() || clazz.isAnonymousClass()) {
                return;
            }

            Method m = clazz.getDeclaredMethod(STATIC_RESET, (Class<?>[]) null);
            m.setAccessible(true);
            methodMap.put(classNameWithDots, m);

        } catch (NoSuchMethodException e) {
            //this can happen if class was not instrumented with a static reset
            logger.debug("__STATIC_RESET() method does not exists in class {}", classNameWithDots);
        } catch (Exception | Error e) {
            logWarn(classNameWithDots, e.getClass()
                    + " thrown while loading method  __STATIC_RESET() for class " + classNameWithDots);
        }
    }

    public Method getResetMethod(String classNameWithDots) {
        cacheResetMethod(classNameWithDots);
        return resetMethodCache.get(loader).get(classNameWithDots);
    }

    /**
     * Invoke the duplicated version of class initializar &lt;clinit&gt;.
     *
     * @param classNameWithDots the class for invoking the duplicated version of class initializer &lt;clinit&gt;
     */
    public void reset(String classNameWithDots) throws IllegalArgumentException, IllegalStateException {
        if (classNameWithDots == null || classNameWithDots.isEmpty()) {
            throw new IllegalArgumentException("Empty class name in input");
        }

        if (loader == null) {
            throw new IllegalStateException("No specified loader");
        }

        if (isSkippedByPrefix(classNameWithDots)) {
            return;
        }
        if (isPoisoned(classNameWithDots)) {
            return;
        }

        Method m = getResetMethod(classNameWithDots);
        if (m == null) {
            return;
        }

        boolean safe = Sandbox.isSafeToExecuteSUTCode();

        assert !Sandbox.isSecurityManagerInitialized() || Sandbox.isOnAndExecutingSUTCode();

        InstrumentingAgent.activate();
        org.evosuite.runtime.Runtime.getInstance().resetRuntime();
        boolean wasLoopCheckOn = LoopCounter.getInstance().isActivated();

        try {
            if (!safe) {
                Sandbox.goingToExecuteUnsafeCodeOnSameThread();
            }
            LoopCounter.getInstance().setActive(false);
            m.invoke(null, (Object[]) null);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            logger.error("" + e, e);
        } catch (NoClassDefFoundError e) {
            AtMostOnceLogger.error(logger, e.toString());
            // The declaring class (or one its <clinit> depends on) is already
            // erroneous, so every further reset attempt raises the same error.
            markPoisoned(classNameWithDots);
        } catch (InvocationTargetException e) {

            Throwable cause = e.getCause();
            if (cause instanceof TooManyResourcesException || cause instanceof NoClassDefFoundError) {
                logWarn(classNameWithDots, e + ", caused by: " + cause);
            } else {
                // Summarise at WARN and keep the stack trace for DEBUG. A replayed <clinit>
                // failing is routine on subjects whose static state needs an environment the
                // tests do not provide - 108_liferay alone hits this for over a hundred
                // distinct classes in a single run, and a stack trace apiece dominated the
                // run log without telling us anything the class name and cause do not.
                logWarn(classNameWithDots, e + ", caused by: " + cause);
                if (logger.isDebugEnabled()) {
                    StringWriter errors = new StringWriter();
                    // we are only interested in the stack trace of the cause
                    cause.printStackTrace(new PrintWriter(errors));
                    logger.debug("__STATIC_RESET() of {} failed: {}\n{}", classNameWithDots, cause, errors);
                }
            }
            // __STATIC_RESET threw mid-replay. The class is now in a half-reset
            // state (fields zeroed by the prepended reset code, then the
            // duplicated <clinit> body aborted before reassigning them).
            // Re-invoking the reset on later tests cannot undo that and only
            // produces duplicate warnings — record it as poisoned so we stop
            // trying for the lifetime of this class loader.
            markPoisoned(classNameWithDots);
        } finally {
            if (!safe) {
                Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
            }
            LoopCounter.getInstance().setActive(wasLoopCheckOn);
        }

        InstrumentingAgent.deactivate();
    }

    private static boolean isSkippedByPrefix(String classNameWithDots) {
        for (String prefix : SKIP_RESET_PREFIXES) {
            if (classNameWithDots.startsWith(prefix)) {
                // If the user is generating tests for this exact class (not
                // merely some other class that happens to live in the same
                // skip-listed package), honor that intent and still attempt
                // to reset it.
                //
                // This must be scoped to the exact class under test, not
                // the whole prefix: ExcludedClasses.
                // getPackagesShouldNotBeInstrumented() bypasses its
                // exclusion list for the CUT's entire package, which is
                // correct there (it's about classloader conflicts - every
                // class in the CUT's package needs consistent classloading).
                // Here it would be wrong: a package-wide bypass would
                // silently re-enable replay for every fragile sibling class
                // in the skip-listed library whenever the CUT happens to
                // live in the same package - e.g. com.liferay.portal.kernel.
                // util. holds both ordinary target classes (like
                // RandomAccessInputStream) and fragile singletons (like
                // SystemProperties) side by side.
                if (classNameWithDots.equals(RuntimeSettings.className)) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    private boolean isPoisoned(String classNameWithDots) {
        Set<String> set = poisonedClasses.get(loader);
        return set != null && set.contains(classNameWithDots);
    }

    private void markPoisoned(String classNameWithDots) {
        Set<String> set = poisonedClasses.computeIfAbsent(
                loader, k -> Collections.synchronizedSet(new HashSet<>()));
        set.add(classNameWithDots);
    }

    /**
     * Visible for testing. Clears the poison set for all class loaders.
     */
    void clearPoisonedClassesForTests() {
        poisonedClasses.clear();
    }

}
