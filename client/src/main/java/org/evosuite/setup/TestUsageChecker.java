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
package org.evosuite.setup;

import org.apache.commons.lang3.ClassUtils;
import org.evosuite.Properties;
import org.evosuite.annotations.EvoSuiteTest;
import org.evosuite.coverage.MethodNameMatcher;
import org.evosuite.graphs.GraphPool;
import org.evosuite.runtime.annotation.EvoSuiteExclude;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.testdata.EnvironmentDataList;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.utils.Java9InvisiblePackage;
import org.evosuite.utils.LoggingUtils;
import org.junit.*;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.lang.reflect.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Created by Andrea Arcuri on 30/06/15.
 */
public class TestUsageChecker {

    private static final Logger logger = LoggerFactory.getLogger(TestUsageChecker.class);
    private static final Pattern COMPILER_ACCESSOR_NAME = Pattern.compile("access\\$\\d+");

    /**
     * Determine if a constructor can be used for testing.
     *
     * @param c the constructor to check
     * @return true if the constructor can be used
     */
    public static boolean canUse(Constructor<?> c) {

        if (c.isSynthetic()) {
            return false;
        }

        // synthetic constructors are OK
        if (Modifier.isAbstract(c.getDeclaringClass().getModifiers())) {
            return false;
        }

        // TODO we could enable some methods from Object, like getClass
        //if (c.getDeclaringClass().equals(java.lang.Object.class))
        //    return false;// handled here to avoid printing reasons

        if (c.getDeclaringClass().equals(java.lang.Thread.class)) {
            return false;// handled here to avoid printing reasons
        }

        if (isForkJoinClass(c.getDeclaringClass())) {
            return false;
        }

        if (c.getDeclaringClass().isAnonymousClass()) {
            return false;
        }

        if (c.getDeclaringClass().isLocalClass()) {
            logger.debug("Skipping constructor of local class " + c.getName());
            return false;
        }

        if (c.getDeclaringClass().isMemberClass() && !TestUsageChecker.canUse(c.getDeclaringClass())) {
            return false;
        }

        if (!Properties.USE_DEPRECATED && c.isAnnotationPresent(Deprecated.class)) {
            final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();
            if (Properties.hasTargetClassBeenLoaded() && !c.getDeclaringClass().equals(targetClass)) {
                logger.debug("Excluding deprecated constructor " + c.getName());
                return false;
            }
        }

        if (isForbiddenNonDeterministicCall(c)) {
            return false;
        }

        if (Modifier.isPublic(c.getModifiers())) {
            TestClusterUtils.makeAccessible(c);
            return true;
        }

        for (java.lang.reflect.Type paramType : c.getGenericParameterTypes()) {
            if (!canUse(paramType)) {
                return false;
            }
        }

        // If default access rights, then check if this class is in the same package as the target class
        if (!Modifier.isPrivate(c.getModifiers())) {
            //                && !Modifier.isProtected(c.getModifiers())) {
            String packageName = ClassUtils.getPackageName(c.getDeclaringClass());
            if (packageName.equals(Properties.CLASS_PREFIX)) {
                TestClusterUtils.makeAccessible(c);
                return true;
            }
        }

        return false;
    }

    /**
     * Determine if a type can be used for testing.
     *
     * @param t the type to check
     * @return true if the type can be used
     */
    public static boolean canUse(java.lang.reflect.Type t) {
        if (t instanceof Class<?>) {
            return canUse((Class<?>) t);
        } else if (t instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) t;
            for (java.lang.reflect.Type parameterType : pt.getActualTypeArguments()) {
                if (!canUse(parameterType)) {
                    return false;
                }
            }
            java.lang.reflect.Type ownerType = pt.getOwnerType();
            return ownerType == null || canUse(ownerType);
        }
        // If it's not declared, let's assume it's ok
        return true;
    }

    /**
     * Determine if a class can be used for testing.
     *
     * @param c the class to check
     * @return true if the class can be used
     */
    public static boolean canUse(Class<?> c) {
        //if (Throwable.class.isAssignableFrom(c))
        //    return false;
        if (Modifier.isPrivate(c.getModifiers())) {
            return false;
        }

        if (!Properties.USE_DEPRECATED) {
            try {
                if (c.isAnnotationPresent(Deprecated.class)) {
                    final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

                    if (Properties.hasTargetClassBeenLoaded() && !c.equals(targetClass)) {
                        logger.debug("Skipping deprecated class " + c.getName());
                        return false;
                    }
                }
            } catch (java.lang.ArrayStoreException e) {
                // https://bugs.java.com/view_bug.do?bug_id=JDK-7183985
                AtMostOnceLogger.warn(LoggingUtils.getEvoLogger(),
                        "ArrayStoreException caught while handling class " + c.getName());
                AtMostOnceLogger.warn(LoggingUtils.getEvoLogger(),
                        "This is likely due to a missing dependency used as annotation: "
                                + "https://bugs.java.com/view_bug.do?bug_id=JDK-7183985");
                return false;
            }
        }

        if (c.isAnonymousClass() || c.isLocalClass() || c.isSynthetic()) {
            return false;
        }

        if (isClassIncludedInPackage(c.getName(), Java9InvisiblePackage.instance.getClassesToBeIgnored())) {
            return false;
        }

        if (!TestClusterUtils.checkIfCanUse(c.getName())) {
            return false;
        }

        if (c.getName().startsWith("junit")) {
            return false;
        }

        // Environment data classes (eg EvoSuiteFile) are runtime support types that
        // should remain usable when their corresponding mocking subsystem is enabled.
        if (EnvironmentDataList.getListOfClasses().contains(c)) {
            return true;
        }

        if (TestClusterUtils.isEvoSuiteClass(c) && !MockList.isAMockClass(c.getCanonicalName())) {
            return false;
        }

        if (c.getEnclosingClass() != null) {
            if (!canUse(c.getEnclosingClass())) {
                return false;
            }
        }

        if (c.getDeclaringClass() != null) {
            if (!canUse(c.getDeclaringClass())) {
                return false;
            }
        }

        // If the SUT is not in the default package, then
        // we cannot import classes that are in the default
        // package
        if (!c.isArray() && !c.isPrimitive() && !Properties.CLASS_PREFIX.isEmpty()
                && !c.getName().contains(".")) {
            return false;
        }

        if (c.getName().contains("EnhancerByMockito")) {
            return false;
        }

        if (c.getName().contains("$MockitoMock")) {
            return false;
        }

        // Piped I/O primitives are highly prone to deadlocks in generated tests:
        // constructors like new PipedInputStream(new PipedOutputStream()) create
        // blocking channels that require coordinated writer/closer threads.
        if (java.io.PipedInputStream.class.isAssignableFrom(c)
                || java.io.PipedOutputStream.class.isAssignableFrom(c)
                || java.io.PipedReader.class.isAssignableFrom(c)
                || java.io.PipedWriter.class.isAssignableFrom(c)) {
            return false;
        }

        // Don't use Lambdas...for now
        if (c.getName().contains("$$Lambda")) {
            return false;
        }


        if (Modifier.isPublic(c.getModifiers())) {
            return true;
        }

        // If default access rights, then check if this class is in the same package as the target class
        if (!Modifier.isPrivate(c.getModifiers())) {
            //                && !Modifier.isProtected(c.getModifiers())) {
            String packageName = ClassUtils.getPackageName(c);
            if (packageName.equals(Properties.CLASS_PREFIX)) {
                return true;
            }
        }

        logger.debug("Not public");
        return false;
    }

    /**
     * Determine if a field can be used for testing.
     *
     * @param f the field to check
     * @return true if the field can be used
     */
    public static boolean canUse(Field f) {
        return canUse(f, f.getDeclaringClass());
    }

    /**
     * Determine if a field can be used for testing.
     *
     * @param f the field to check
     * @param ownerClass the class that owns the field
     * @return true if the field can be used
     */
    public static boolean canUse(Field f, Class<?> ownerClass) {

        // TODO we could enable some methods from Object, like getClass
        if (f.getDeclaringClass().equals(java.lang.Object.class)) {
            return false;// handled here to avoid printing reasons
        }

        if (f.getDeclaringClass().equals(java.lang.Thread.class)) {
            return false;// handled here to avoid printing reasons
        }

        if (isForkJoinClass(f.getDeclaringClass())) {
            return false;
        }

        if (!Properties.USE_DEPRECATED && f.isAnnotationPresent(Deprecated.class)) {
            final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

            if (Properties.hasTargetClassBeenLoaded() && !f.getDeclaringClass().equals(targetClass)) {
                logger.debug("Skipping deprecated field " + f.getName());
                return false;
            }
        }

        if (f.isSynthetic()) {
            logger.debug("Skipping synthetic field " + f.getName());
            return false;
        }

        if (f.getName().startsWith("ajc$")) {
            logger.debug("Skipping AspectJ field " + f.getName());
            return false;
        }

        if (!f.getType().equals(String.class) && !canUse(f.getType())) {
            return false;
        }

        // in, out, err
        if (f.getDeclaringClass().equals(FileDescriptor.class)) {
            return false;
        }

        if (f.getName().equals("serialVersionUID")) {
            return false;
        }

        if (Modifier.isPublic(f.getModifiers())) {
            // It may still be the case that the field is defined in a non-visible superclass of the class
            // we already know we can use. In that case, the compiler would be fine with accessing the
            // field, but reflection would start complaining about IllegalAccess!
            // Therefore, we set the field accessible to be on the safe side
            TestClusterUtils.makeAccessible(f);
            return true;
        }

        // If default access rights, then check if this class is in the same package as the target class
        if (!Modifier.isPrivate(f.getModifiers())) {
            // && !Modifier.isProtected(f.getModifiers())) {
            String packageName = ClassUtils.getPackageName(ownerClass);

            String declaredPackageName = ClassUtils.getPackageName(f.getDeclaringClass());

            if (packageName.equals(Properties.CLASS_PREFIX)
                    && packageName.equals(declaredPackageName)) {
                TestClusterUtils.makeAccessible(f);
                return true;
            }
        }

        return false;
    }

    /**
     * Determine if a method can be used for testing.
     *
     * @param m the method to check
     * @return true if the method can be used
     */
    public static boolean canUse(Method m) {
        return canUse(m, m.getDeclaringClass());
    }

    /**
     * Determine if a method can be used for testing.
     *
     * @param m the method to check
     * @param ownerClass the class that owns the method
     * @return true if the method can be used
     */
    public static boolean canUse(Method m, Class<?> ownerClass) {

        final MethodNameMatcher matcher = new MethodNameMatcher();
        String methodSignature = m.getName() + Type.getMethodDescriptor(m);
        if (!matcher.methodMatches(methodSignature)) {
            logger.debug("Excluding method '" + methodSignature + "' that does not match criteria");
            return false;
        }

        if (m.isBridge()) {
            logger.debug("Excluding bridge method: " + m);
            return false;
        }

        if (isCompilerGeneratedAccessorMethod(m)) {
            logger.debug("Excluding compiler-generated accessor method: " + m);
            return false;
        }

        if (m.isSynthetic()) {
            logger.debug("Excluding synthetic method: " + m);
            return false;
        }

        if (isCompilerGeneratedEnumMethod(m)) {
            logger.debug("Excluding compiler-generated enum method: " + m);
            return false;
        }

        if (!Properties.USE_DEPRECATED && m.isAnnotationPresent(Deprecated.class)) {
            final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

            if (Properties.hasTargetClassBeenLoaded() && !m.getDeclaringClass().equals(targetClass)) {
                logger.debug("Excluding deprecated method " + m.getName());
                return false;
            }
        }

        if (m.isAnnotationPresent(Test.class) || m.isAnnotationPresent(Before.class)
                || m.isAnnotationPresent(BeforeClass.class)
                || m.isAnnotationPresent(After.class) || m.isAnnotationPresent(AfterClass.class)) {
            logger.debug("Excluding test method " + m.getName());
            return false;
        }

        if (m.isAnnotationPresent(EvoSuiteTest.class)) {
            logger.debug("Excluding EvoSuite test method " + m.getName());
            return false;
        }

        if (m.isAnnotationPresent(EvoSuiteExclude.class)) {
            logger.debug("Excluding method with exclusion annotation " + m.getName());
            return false;
        }

        if (m.getDeclaringClass().equals(java.lang.Object.class)) {
            return false;
        }

        // FIXME: EvoSuite currently can't deal properly with the Map.of(...) methods introduced in Java 9
        if (m.getDeclaringClass().equals(java.util.Map.class) && Modifier.isStatic(m.getModifiers())) {
            return false;
        }

        // FIXME: EvoSuite currently can't deal properly with the Set.of(...) methods introduced in Java 9
        if (m.getDeclaringClass().equals(java.util.Set.class) && Modifier.isStatic(m.getModifiers())) {
            return false;
        }

        if (!m.getReturnType().equals(String.class) && (!canUse(m.getReturnType())
                || !canUse(m.getGenericReturnType()))) {
            return false;
        }

        for (java.lang.reflect.Type paramType : m.getGenericParameterTypes()) {
            if (!canUse(paramType)) {
                return false;
            }
        }

        if (m.getDeclaringClass().equals(Enum.class)) {
            return false;
            /*
            if (m.getName().equals("valueOf") || m.getName().equals("values")
                    || m.getName().equals("ordinal")) {
                logger.debug("Excluding valueOf for Enum " + m.toString());
                return false;
            }
            // Skip compareTo on enums (like Randoop)
            if (m.getName().equals("compareTo") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].equals(Enum.class))
                return false;
                */
        }

        if (m.getDeclaringClass().equals(java.lang.Thread.class)) {
            return false;
        }

        if (isForkJoinClass(m.getDeclaringClass())) {
            return false;
        }

        if (isBlockingMethod(m)) {
            return false;
        }

        // Hashcode only if we need to cover it
        if (m.getName().equals("hashCode")) {
            final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

            if (!m.getDeclaringClass().equals(targetClass)) {
                return false;
            } else {
                if (GraphPool.getInstance(ownerClass.getClassLoader()).getActualCFG(Properties.TARGET_CLASS,
                        m.getName() + Type.getMethodDescriptor(m)) == null) {
                    // Don't cover generated hashCode
                    // TODO: This should work via annotations
                    return false;
                }
            }
        }

        // Randoop special case: just clumps together a bunch of hashCodes, so skip it
        if (m.getName().equals("deepHashCode")
                && m.getDeclaringClass().equals(Arrays.class)) {
            return false;
        }

        // Randoop special case: differs too much between JDK installations
        if (m.getName().equals("getAvailableLocales")) {
            return false;
        }

        if (m.getName().equals(ClassResetter.STATIC_RESET)) {
            logger.debug("Ignoring static reset method");
            return false;
        }

        if (isForbiddenNonDeterministicCall(m)) {
            return false;
        }

        if (!Properties.CONSIDER_MAIN_METHODS && m.getName().equals("main")
                && Modifier.isStatic(m.getModifiers())
                && Modifier.isPublic(m.getModifiers())) {
            logger.debug("Ignoring static main method ");
            return false;
        }

        /*
        if(m.getTypeParameters().length > 0) {
            logger.debug("Cannot handle generic methods at this point");
            if(m.getDeclaringClass().equals(Properties.getTargetClass())) {
                LoggingUtils.getEvoLogger().info("* Skipping method " + m.getName()
                        + ": generic methods are not handled yet");
            }
            return false;
        }
        */

        // If default or
        if (Modifier.isPublic(m.getModifiers())) {
            TestClusterUtils.makeAccessible(m);
            return true;
        }

        // If default access rights, then check if this class is in the same package as the target class
        if (!Modifier.isPrivate(m.getModifiers())) {
            // && !Modifier.isProtected(m.getModifiers())) {
            String packageName = ClassUtils.getPackageName(ownerClass);
            String declaredPackageName = ClassUtils.getPackageName(m.getDeclaringClass());
            if (packageName.equals(Properties.CLASS_PREFIX)
                    && packageName.equals(declaredPackageName)
                    && !Modifier.isAbstract(m.getModifiers())) {
                TestClusterUtils.makeAccessible(m);
                return true;
            }
        }

        return false;
    }

    /**
     * Detects enum methods generated by the compiler.
     */
    public static boolean isCompilerGeneratedEnumMethod(Method m) {
        if (!m.getDeclaringClass().isEnum()) {
            return false;
        }

        if (m.getName().equals("values") && m.getParameterCount() == 0) {
            return true;
        }

        return m.getName().equals("valueOf")
                && m.getParameterCount() == 1
                && m.getParameterTypes()[0].equals(String.class);
    }

    /**
     * Detect package-private accessor stubs emitted by older bytecode targets.
     * Some instrumented class-loading paths can drop the synthetic flag while
     * keeping the compiler-assigned accessor name.
     */
    public static boolean isCompilerGeneratedAccessorMethod(Method m) {
        int modifiers = m.getModifiers();
        return Modifier.isStatic(modifiers)
                && !Modifier.isPublic(modifiers)
                && !Modifier.isProtected(modifiers)
                && !Modifier.isPrivate(modifiers)
                && COMPILER_ACCESSOR_NAME.matcher(m.getName()).matches();
    }


    /**
     * If we try to get deterministic tests, we must not include these methods.
     *
     * @param m the method to check
     * @return true if the method is forbidden
     */
    private static boolean isForbiddenNonDeterministicCall(Method m) {
        if (!Properties.REPLACE_CALLS) {
            return false;
        }

        Class<?> declaringClass = m.getDeclaringClass();

        // Calendar is initialized with current time
        if (declaringClass.equals(Calendar.class)) {
            if (m.getName().equals("getInstance")) {
                return true;
            }
        }

        // Locale will return system specific information
        if (declaringClass.equals(Locale.class)) {
            if (m.getName().equals("getDefault")) {
                return true;
            }
            if (m.getName().equals("getAvailableLocales")) {
                return true;
            }
        }

        // MessageFormat will return system specific information
        if (declaringClass.equals(MessageFormat.class)) {
            if (m.getName().equals("getLocale")) {
                return true;
            }
        }

        if (m.getDeclaringClass().equals(Date.class)) {
            if (m.getName().equals("toLocaleString")) {
                return true;
            }
        }

        if (m.getDeclaringClass().equals(ClassLoader.class)) {
            String name = m.getName();
            if (name.startsWith("getSystemResource")) {
                return true;
            } else {
                return name.startsWith("getResource");
            }
        }

        return false;
    }

    /**
     * If we try to get deterministic tests, we must not include these
     * constructors.
     *
     * @param c the constructor to check
     * @return true if the constructor is forbidden
     */
    private static boolean isForbiddenNonDeterministicCall(Constructor<?> c) {
        if (!Properties.REPLACE_CALLS) {
            return false;
        }

        // Date default constructor uses current time
        if (c.getDeclaringClass().equals(Date.class)) {
            if (c.getParameterTypes().length == 0) {
                return true;
            }
        }

        // Random without seed parameter is...random
        if (c.getDeclaringClass().equals(Random.class)) {
            return c.getParameterTypes().length == 0;
        }

        return false;
    }

    /**
     * ForkJoinTask and ForkJoinPool should not appear in generated tests.
     * ForkJoinTask.join()/get()/invokeAll() block until the task completes,
     * and ForkJoinTask.awaitDone() swallows Thread.interrupt() — meaning the
     * timeout handler cannot stop a thread stuck in these methods.
     * ForkJoinPool.submit()/invoke() can similarly create tasks that never
     * complete in the test environment.
     */
    private static boolean isForkJoinClass(Class<?> clazz) {
        return java.util.concurrent.ForkJoinTask.class.isAssignableFrom(clazz)
                || java.util.concurrent.ForkJoinPool.class.isAssignableFrom(clazz);
    }

    /**
     * Methods that block indefinitely when called without proper setup
     * (e.g., take() on an empty queue, await() on a latch).  These always
     * hit the timeout and waste search budget without contributing coverage.
     */
    private static boolean isBlockingMethod(Method m) {
        Class<?> declaringClass = m.getDeclaringClass();
        String name = m.getName();

        // BlockingQueue / BlockingDeque: take() and put() block indefinitely
        if (java.util.concurrent.BlockingQueue.class.isAssignableFrom(declaringClass)) {
            if ("take".equals(name) || "put".equals(name)) {
                return true;
            }
            if ("poll".equals(name) && m.getParameterCount() == 2
                    && m.getParameterTypes()[0].equals(long.class)
                    && m.getParameterTypes()[1].equals(java.util.concurrent.TimeUnit.class)) {
                return true;
            }
        }

        // SynchronousQueue: all blocking ops (offer/poll with timeout are fine,
        // but the no-arg variants and take/put block forever without a partner thread)
        if (java.util.concurrent.SynchronousQueue.class.isAssignableFrom(declaringClass)) {
            if ("take".equals(name) || "put".equals(name)) {
                return true;
            }
        }

        // CountDownLatch.await(), CyclicBarrier.await() — need coordinating threads
        if (java.util.concurrent.CountDownLatch.class.isAssignableFrom(declaringClass)
                || java.util.concurrent.CyclicBarrier.class.isAssignableFrom(declaringClass)) {
            if ("await".equals(name) && m.getParameterCount() == 0) {
                return true;
            }
        }

        // Phaser.awaitAdvance variants without timeout
        if (java.util.concurrent.Phaser.class.isAssignableFrom(declaringClass)) {
            if ("awaitAdvance".equals(name) || "awaitAdvanceInterruptibly".equals(name)) {
                if (m.getParameterCount() <= 1) {
                    return true;
                }
            }
        }

        // Semaphore.acquire() with no permits available blocks forever
        if (java.util.concurrent.Semaphore.class.isAssignableFrom(declaringClass)) {
            if ("acquire".equals(name) || "acquireUninterruptibly".equals(name)) {
                return true;
            }
        }

        // Exchanger.exchange() blocks until a partner thread arrives
        if (java.util.concurrent.Exchanger.class.isAssignableFrom(declaringClass)) {
            if ("exchange".equals(name) && m.getParameterCount() == 1) {
                return true;
            }
        }

        // CompletableFuture.get() / join() block until completion
        if (java.util.concurrent.CompletableFuture.class.isAssignableFrom(declaringClass)) {
            if ("get".equals(name) && m.getParameterCount() == 0) {
                return true;
            }
            if ("join".equals(name)) {
                return true;
            }
        }

        // Future.get() without timeout blocks indefinitely
        if (java.util.concurrent.Future.class.isAssignableFrom(declaringClass)) {
            if ("get".equals(name) && m.getParameterCount() == 0) {
                return true;
            }
        }

        // Lock.lock() / lockInterruptibly() — no unlock in generated tests
        if (java.util.concurrent.locks.Lock.class.isAssignableFrom(declaringClass)) {
            if ("lock".equals(name) || "lockInterruptibly".equals(name)) {
                return true;
            }
        }

        // Condition.await() — needs signal from another thread
        if (java.util.concurrent.locks.Condition.class.isAssignableFrom(declaringClass)) {
            if ("await".equals(name) && m.getParameterCount() == 0) {
                return true;
            }
            if ("awaitUninterruptibly".equals(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a class is included in a list of packages.
     *
     * @param className the class name to check
     * @param classList the list of package prefixes
     * @return true if the class is included in the list
     */
    private static boolean isClassIncludedInPackage(String className, List<String> classList) {
        String result = classList.stream()
                .filter(class1 -> className.startsWith(class1))
                .findAny()
                .orElse(null);
        return result != null;
    }

}
