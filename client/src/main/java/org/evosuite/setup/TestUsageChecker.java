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
import org.evosuite.runtime.Reflection;
import org.evosuite.Properties;
import org.evosuite.annotations.EvoSuiteTest;
import org.evosuite.coverage.MethodNameMatcher;
import org.evosuite.graphs.GraphPool;
import org.evosuite.instrumentation.OriginalFieldVisibility;
import org.evosuite.runtime.annotation.EvoSuiteExclude;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.testdata.EnvironmentDataList;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.utils.Java9InvisiblePackage;
import org.evosuite.utils.LoggingUtils;
import org.junit.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.InputStream;
import java.lang.reflect.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.security.CodeSource;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Created by Andrea Arcuri on 30/06/15.
 */
public class TestUsageChecker {

    private static final Logger logger = LoggerFactory.getLogger(TestUsageChecker.class);
    private static final Pattern COMPILER_ACCESSOR_NAME = Pattern.compile("access\\$\\d+");
    private static final List<String> UNSAFE_DEPENDENCY_CLASS_PREFIXES = Collections.unmodifiableList(
            Arrays.asList(
                    "org.mockito.internal.",
                    "net.bytebuddy.",
                    "org.objenesis.",
                    "scala."));
    private static final Set<String> UNSTABLE_GUI_ACCESSORS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList(
                    "toString",
                    "isVisible",
                    "isForegroundSet",
                    "isBackgroundSet",
                    "isFontSet",
                    "isCursorSet",
                    "isDisplayable",
                    "isEnabled",
                    "isFocusable",
                    "isFocusOwner",
                    "isFocusTraversable",
                    "isLightweight",
                    "isMaximumSizeSet",
                    "isMinimumSizeSet",
                    "isPreferredSizeSet",
                    "isShowing",
                    "isValid",
                    "countComponents",
                    "getComponentCount",
                    "getBackground",
                    "getBounds",
                    "bounds",
                    "getLocation",
                    "location",
                    "getSize",
                    "size",
                    "getX",
                    "getY",
                    "getWidth",
                    "getHeight",
                    "getMinimumSize",
                    "getMaximumSize",
                    "getPreferredSize",
                    "getComponentOrientation",
                    "getFocusTraversalKeysEnabled",
                    "getIgnoreRepaint",
                    "isFocusTraversalPolicySet",
                    "isFocusCycleRoot",
                    "isFocusTraversalPolicyProvider")));

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

        if (isProtectedMemberOfOtherPackage(c.getDeclaringClass())) {
            logger.debug("Skipping constructor of protected member class {}", c.getName());
            return false;
        }

        if (!Properties.USE_DEPRECATED && c.isAnnotationPresent(Deprecated.class)
                && !isExemptFromDeprecationFilter(c.getDeclaringClass())) {
            logger.debug("Excluding deprecated constructor " + c.getName());
            return false;
        }

        if (isForbiddenNonDeterministicCall(c)) {
            return false;
        }

        // A public constructor is as unusable as a method when it takes a source-private type
        if (hasSourcePrivateParameterType(c.getParameterTypes())) {
            return false;
        }

        if (!Modifier.isPublic(c.getModifiers())) {
            for (java.lang.reflect.Type paramType : c.getGenericParameterTypes()) {
                if (!canUse(paramType)) {
                    return false;
                }
            }
        }

        if (!isVisibleFromTestPackage(c.getDeclaringClass(), c.getModifiers())) {
            return false;
        }
        TestClusterUtils.makeAccessible(c);
        return true;
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

        if (isUnsafeDependencyClass(c)) {
            return false;
        }

        if (!Properties.USE_DEPRECATED) {
            try {
                if (c.isAnnotationPresent(Deprecated.class) && !isPlatformClass(c)
                        && !isExemptFromDeprecationFilter(c)) {
                    logger.debug("Skipping deprecated class " + c.getName());
                    return false;
                }
            } catch (java.lang.ArrayStoreException e) {
                // https://bugs.java.com/view_bug.do?bug_id=JDK-7183985
                AtMostOnceLogger.warn(LoggingUtils.getEvoLogger(),
                        "ArrayStoreException caught while handling class " + c.getName());
                AtMostOnceLogger.warn(LoggingUtils.getEvoLogger(),
                        "This is likely due to a missing dependency used as annotation: "
                                + "https://bugs.java.com/view_bug.do?bug_id=JDK-7183985");
                return false;
            } catch (LinkageError e) {
                // Reading the annotations initialises every enum an annotation member defaults
                // to, and that static initialiser belongs to the subject under test. When it
                // throws (ExceptionInInitializerError, then NoClassDefFoundError on every later
                // read) the class cannot be inspected, so it cannot be used; it must not abort
                // the whole generation from inside cluster analysis.
                logger.debug("Cannot read the annotations of {}: {}", c.getName(), e.toString());
                return false;
            }
        }

        if (c.isAnonymousClass() || c.isLocalClass() || c.isSynthetic()) {
            return false;
        }

        if (isClassIncludedInPackage(c.getName(), Java9InvisiblePackage.instance.getClassesToBeIgnored())) {
            return false;
        }

        // javac refuses any mention of a class whose package its named module does not export
        // (java.awt.peer.ComponentPeer, sun.security.validator.*): a method returning or taking one
        // cannot even be called from a test, so it must not enter the cluster.
        if (isInUnexportedPackage(c)) {
            logger.debug("Skipping class in a package its module does not export: {}", c.getName());
            return false;
        }

        if (!TestClusterUtils.checkIfCanUse(c.getName(), c)) {
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

        // The configured target class (or a member nested inside it) is exempt from the
        // "reject EvoSuite's own classes" rule below, for the same reason checkIfCanUse()
        // above already exempts it from the package-exclusion check: if the class under test
        // itself happens to live under org.evosuite (dogfooding EvoSuite against its own
        // code, or - as here - a unit test using a nested class of itself as a stand-in SUT),
        // then rejecting the target class' own members as "EvoSuite-internal" makes every
        // generic-bound parameter typed on one of them permanently unconstructible: nothing
        // can ever satisfy the bound, no matter how the search proceeds. No real-world class
        // under test lives under org.evosuite, so this bypass cannot change behaviour for the
        // ordinary case; it only stops EvoSuite from refusing to use the very class it was
        // told to target.
        if (TestClusterUtils.isEvoSuiteClass(c) && !MockList.isAMockClass(c.getCanonicalName())
                && !TestClusterUtils.isConfiguredTargetClassOrPackage(c.getName())) {
            return false;
        }

        Class<?> enclosingClass = getEnclosingClassSafely(c);
        if (enclosingClass == UNRESOLVABLE_OWNER_CLASS) {
            return false;
        }
        if (enclosingClass != null) {
            if (!canUse(enclosingClass)) {
                return false;
            }
        }

        Class<?> declaringClass = getDeclaringClassSafely(c);
        if (declaringClass == UNRESOLVABLE_OWNER_CLASS) {
            return false;
        }
        if (declaringClass != null) {
            if (!canUse(declaringClass)) {
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


        if (isVisibleFromTestPackage(c, c.getModifiers())) {
            return true;
        }

        logger.debug("Not public");
        return false;
    }

    private static final Class<?> UNRESOLVABLE_OWNER_CLASS = UnresolvableOwnerClass.class;

    private static final class UnresolvableOwnerClass {
    }

    /**
     * Whether the {@code USE_DEPRECATED} filter leaves {@code declaringClass} alone.
     *
     * <p>The target class is always exempt: we are asked to test it, deprecated or
     * not. When the target is itself {@code @Deprecated} the filter is moot -- its
     * tests unavoidably exercise deprecated API -- and enforcing it is harmful: a
     * deprecated abstract class (commons-cli {@code Parser}, commons-math
     * {@code optimization.AbstractScalarDifferentiableOptimizer}) has only
     * deprecated concrete subclasses, whose constructors take deprecated types in
     * turn, so filtering leaves no way to instantiate the target at all and the
     * search runs on an empty population, reporting 0% coverage. A deprecated
     * target therefore implies {@code use_deprecated}.
     *
     * @param declaringClass the class that declares the deprecated element
     * @return true if the element may be used despite being deprecated
     */
    private static boolean isExemptFromDeprecationFilter(Class<?> declaringClass) {
        if (!Properties.hasTargetClassBeenLoaded()) {
            return true;
        }
        final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();
        if (targetClass == null) {
            return false;
        }
        return declaringClass.equals(targetClass) || targetClass.isAnnotationPresent(Deprecated.class);
    }

    /**
     * Whether a class comes from the JDK itself rather than from the code under test.
     *
     * <p>Since Java 9 the JDK's named modules are not all loaded the same way: a module
     * such as {@code java.base} or {@code jdk.unsupported} can still be defined by the
     * bootstrap loader (a null {@code getClassLoader()}), while another such as
     * {@code java.sql} is defined by the <em>platform</em> loader instead. Loader
     * identity alone is therefore insufficient to recognise JDK classes.
     *
     * <p>We classify by module origin, not by module name or loader identity: a module's
     * name is just a string the code that built it chose, and a module placed on
     * {@code --module-path} and named in {@code --add-modules} at JVM <em>startup</em>
     * resolves into the same boot layer, and can be defined by the same loader, as genuine
     * JDK code -- so neither a {@code java.}/{@code jdk.}-prefixed name nor loader identity
     * proves JDK provenance. {@link JavaModuleLookup#isFromSystemImage} is the authoritative
     * per-class check; see its documentation for the actual reference-origin contract
     * (including why it still accepts a genuine, system-image module that the ordinary
     * system/application loader defines, such as {@code jdk.jdi}, and rejects a same-named
     * user module such as a spoofed {@code jdk.hotspot.agent}).
     *
     * <p>{@code Class#getModule()} is Java 9+, and this module's main source compiles against
     * the Java 8 API (see {@code runtime}'s own Java 8 floor, enforced on this module the same
     * way), so it is looked up reflectively via the shared {@link JavaModuleLookup}, which also
     * backs {@code TestClusterUtils#isJdkClass} for the module half of the identical
     * constraint; on an actual Java 8 JVM the module lookup itself fails (there is no module
     * system yet) and this falls back to the old bootstrap-loader check, which is exactly
     * correct there, since Java 8 predates both named modules and the boot layer entirely.
     *
     * <p>A user class loaded the ordinary way (app class loader, classpath) is always in
     * an <em>unnamed</em> module, which fails the {@code isNamed()} check regardless of
     * its package name -- except that a {@code java.}-prefixed package is also
     * independently blocked there ({@code SecurityException: Prohibited package name}).
     * Neither restriction applies to a module built and loaded the way the spoofs above
     * do, which is why the system-module check is also needed.
     *
     * <p>This matters for the {@code USE_DEPRECATED} filter. That option exists to stop
     * us generating tests for API the project itself has deprecated, but several JDK
     * types picked up a {@code @Deprecated} annotation after Java 8 -- {@code
     * java.util.Observable} in Java 9, {@code org.xml.sax.AttributeList} long before
     * that, and (depending on the running JDK) platform-loaded types such as {@code
     * java.sql.SQLPermission}. Excluding those does not spare anyone a test of obsolete
     * API: it makes the SUT's own undeprecated methods uncallable, because a method
     * whose parameter type we refuse to use is dropped from the test cluster entirely.
     * So a class like {@code nu.staldal.xmlutil.ContentHandlerAdapter}, whose whole
     * contract is {@code startElement(String, AttributeList)}, loses the branches that
     * matter.
     *
     * @param c the class to check
     * @return true if {@code c} is a JDK platform class
     */
    static boolean isPlatformClass(Class<?> c) {
        JavaModuleLookup.Result result = JavaModuleLookup.lookupModule(c);
        if (result.isUnavailable()) {
            return c.getClassLoader() == null;
        }
        return JavaModuleLookup.isSystemImageJdkModule(result);
    }

    /**
     * Whether {@code c} is declared in a package its named module does not export to everyone
     * (e.g. {@code java.awt.peer}, {@code sun.security.validator}). Generated tests cannot import
     * or name such a class: javac reports "package ... is not visible".
     *
     * @param c the class to check
     * @return true if generated source cannot refer to {@code c} by name
     */
    public static boolean isInUnexportedPackage(Class<?> c) {
        return JavaModuleLookup.isInUnexportedPackage(c);
    }

    /**
     * Whether generated test code, which lives in the package of the class under test
     * ({@code Properties.CLASS_PREFIX}) and is not a subclass of anything, can call {@code member}
     * (or a constructor or field): public members always, package-private and protected ones only when
     * their declaring class is in that same package. A protected method inherited from a class
     * in another package is not callable from the test, however deep in the hierarchy it is
     * found by {@code getDeclaredMethods()}.
     *
     * @param member the method, constructor or field to check
     * @return true if javac accepts a call to it from the generated test
     */
    public static boolean isCallableFromTestPackage(java.lang.reflect.Member member) {
        int modifiers = member instanceof Field
                ? getOriginalFieldModifiers((Field) member) : member.getModifiers();
        return isVisibleFromTestPackage(member.getDeclaringClass(), modifiers);
    }

    /**
     * Whether a class or member with these modifiers, declared in {@code declaringClass}, is visible
     * from the package of the generated test: public, or neither private nor in another package.
     */
    private static boolean isVisibleFromTestPackage(Class<?> declaringClass, int modifiers) {
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        return ClassUtils.getPackageName(declaringClass).equals(Properties.CLASS_PREFIX);
    }

    private static final Map<Class<?>, Integer> SOURCE_MEMBER_MODIFIERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The modifiers javac attributes to {@code type}. For a member class javac reads the access
     * flags from the InnerClasses table of the enclosing class, while reflection reads them from
     * the member's own class file; the two disagree for some older libraries (xerces'
     * {@code protected class ScannedEntity} reflects as public). The enclosing table wins.
     */
    public static int getSourceModifiers(Class<?> type) {
        int reflected = type.getModifiers();
        Class<?> enclosing = getDeclaringClassSafely(type);
        if (enclosing == null || enclosing == UNRESOLVABLE_OWNER_CLASS) {
            return reflected;
        }
        Integer cached = SOURCE_MEMBER_MODIFIERS.get(type);
        if (cached == null) {
            cached = readEnclosingInnerClassAccess(type, enclosing, reflected);
            SOURCE_MEMBER_MODIFIERS.put(type, cached);
        }
        return cached;
    }

    private static int readEnclosingInnerClassAccess(Class<?> type, Class<?> enclosing,
                                                     int reflected) {
        ClassLoader loader = enclosing.getClassLoader();
        String resource = enclosing.getName().replace('.', '/') + ".class";
        String internalName = type.getName().replace('.', '/');
        int[] found = {reflected};
        try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            if (input != null) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInnerClass(String name, String outerName, String innerName,
                                                int access) {
                        if (internalName.equals(name) && outerName != null) {
                            // A promoted (instrumented) class reflects as more visible than its
                            // original; only the access levels are taken over from the table.
                            int levels = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;
                            found[0] = (reflected & ~levels) | (access & levels);
                        }
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        } catch (java.io.IOException | RuntimeException e) {
            logger.debug("Cannot read the InnerClasses table of {}", enclosing.getName(), e);
        }
        return found[0];
    }

    /**
     * Whether {@code type} is, or sits inside, a protected member class of a package other than the
     * generated test's. JLS 6.6.2 admits such a class only in subclasses of its enclosing class, so
     * the test can neither name it nor call its constructor.
     */
    public static boolean isProtectedMemberOfOtherPackage(Class<?> type) {
        for (Class<?> current = type; current != null; current = getDeclaringClassSafely(current)) {
            if (current == UNRESOLVABLE_OWNER_CLASS) {
                return false;
            }
            if (Modifier.isProtected(getSourceModifiers(current))
                    && !ClassUtils.getPackageName(current).equals(Properties.CLASS_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** Field modifiers as javac sees them, before accessibility instrumentation promotes them. */
    public static int getOriginalFieldModifiers(Field field) {
        return OriginalFieldVisibility.getModifiers(field);
    }

    private static boolean isUnsafeDependencyClass(Class<?> c) {
        String className = c.getName();
        return UNSAFE_DEPENDENCY_CLASS_PREFIXES.stream()
                .anyMatch(prefix -> className.startsWith(prefix) && !isTargetInPrefix(prefix));
    }

    private static boolean isTargetInPrefix(String prefix) {
        return Properties.TARGET_CLASS != null && Properties.TARGET_CLASS.startsWith(prefix);
    }

    private static Class<?> getEnclosingClassSafely(Class<?> c) {
        try {
            return c.getEnclosingClass();
        } catch (LinkageError | TypeNotPresentException e) {
            logger.debug("Skipping class with unresolvable enclosing class metadata: {}", c.getName(), e);
            return UNRESOLVABLE_OWNER_CLASS;
        }
    }

    private static Class<?> getDeclaringClassSafely(Class<?> c) {
        try {
            return c.getDeclaringClass();
        } catch (LinkageError | TypeNotPresentException e) {
            logger.debug("Skipping class with unresolvable declaring class metadata: {}", c.getName(), e);
            return UNRESOLVABLE_OWNER_CLASS;
        }
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

        if (!Properties.USE_DEPRECATED && f.isAnnotationPresent(Deprecated.class)
                && !isExemptFromDeprecationFilter(f.getDeclaringClass())) {
            logger.debug("Skipping deprecated field " + f.getName());
            return false;
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

        // Field shadowing: when the field is inherited from a superclass but
        // an intermediate or the owner class itself redeclares a field with
        // the same name, Java resolves direct access (e.g. `obj.name`) through
        // the static type of `obj` to the shadowing declaration, not the
        // inherited one. Emitting direct access in that case would not compile.
        if (isShadowedInOwner(f, ownerClass)) {
            return false;
        }

        int modifiers = getOriginalFieldModifiers(f);
        if (!isVisibleFromTestPackage(f.getDeclaringClass(), modifiers)) {
            return false;
        }
        if (Modifier.isPublic(modifiers)) {
            // Reflection may still need access to a public field declared in a hidden superclass.
            TestClusterUtils.makeAccessible(f);
            return true;
        }

        // A package-private field is not inherited by an owner in a different package.
        // FieldStatement renders owner.field directly, without a cast to the declaring type.
        if (ClassUtils.getPackageName(ownerClass).equals(
                ClassUtils.getPackageName(f.getDeclaringClass()))) {
            TestClusterUtils.makeAccessible(f);
            return true;
        }

        return false;
    }

    private static boolean isShadowedInOwner(Field f, Class<?> ownerClass) {
        Class<?> declaring = f.getDeclaringClass();
        if (ownerClass == null || ownerClass.equals(declaring)) {
            return false;
        }
        if (!declaring.isAssignableFrom(ownerClass)) {
            return false;
        }
        Class<?> current = ownerClass;
        while (current != null && !current.equals(declaring)) {
            try {
                current.getDeclaredField(f.getName());
                return true;
            } catch (NoSuchFieldException ignored) {
                // not shadowed at this level
            } catch (LinkageError e) {
                // getDeclaredField resolves the types of *all* declared fields of
                // the class, so a single field whose type is missing from the
                // project classpath (e.g. an Eclipse ListenerList) throws
                // NoClassDefFoundError here. This runs while the test cluster is
                // being built, and the caller does not expect a LinkageError, so
                // treat the field as not shadowed rather than abort the run.
                logger.debug("Cannot inspect declared fields of {} while checking shadowing of {}: {}",
                        current.getName(), f.getName(), e.toString());
                return false;
            }
            current = current.getSuperclass();
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
     * Whether {@code ownerClass} has a public, non-abstract, non-static method with the same
     * name, parameter types and return type (the JVM descriptor, so a compiler-generated bridge
     * counts) as {@code abstractMethod}, i.e. whether invoking the abstract
     * declaration on an instance of {@code ownerClass} reaches an implementation.
     */
    private static boolean hasConcreteImplementation(Class<?> ownerClass, Method abstractMethod) {
        try {
            for (Method candidate : ownerClass.getMethods()) {
                if (matchesDescriptor(candidate, abstractMethod) && isConcretePublicMethod(candidate)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // A missing type in an unrelated signature can prevent getMethods() from linking
            // the whole hierarchy. Individual superclass declarations may still be inspectable.
            logger.debug("Cannot enumerate methods of " + ownerClass.getName() + ": " + t);
        }
        for (Class<?> current = ownerClass; current != null; current = current.getSuperclass()) {
            Method[] declared = getDeclaredMethodsOrNull(current);
            if (declared != null) {
                for (Method candidate : declared) {
                    if (matchesDescriptor(candidate, abstractMethod)) {
                        // Stop at the nearest declaration: an abstract override must not expose
                        // a concrete implementation which it hides farther up the hierarchy.
                        return isConcretePublicMethod(candidate);
                    }
                }
            } else {
                Boolean implementation = inspectClassfileImplementation(current, abstractMethod);
                if (implementation != null) {
                    return implementation;
                }
            }
        }
        return false;
    }

    /**
     * Declared methods of a class, or null if its signatures cannot be linked. A failure is
     * remembered per class: a failing lookup is not free, because every attempt makes the
     * instrumenting class loader load and instrument the types in the signatures again, and this
     * is asked once per abstract method of every analysed class.
     */
    private static Method[] getDeclaredMethodsOrNull(Class<?> clazz) {
        synchronized (unlinkableDeclaredMethods) {
            if (unlinkableDeclaredMethods.contains(clazz)) {
                return null;
            }
        }
        try {
            return Reflection.inStableOrder(clazz.getDeclaredMethods());
        } catch (Throwable t) {
            logger.debug("Cannot enumerate declared methods of " + clazz.getName() + ": " + t);
            synchronized (unlinkableDeclaredMethods) {
                unlinkableDeclaredMethods.add(clazz);
            }
            return null;
        }
    }

    /** Weakly held, and only failures: a cached Method[] would keep its class alive. */
    private static final Set<Class<?>> unlinkableDeclaredMethods =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Null means the verified class file has no matching declaration; false also covers unreadable metadata. */
    private static Boolean inspectClassfileImplementation(Class<?> owner, Method method) {
        Map<String, Integer> declarations = getClassfileMethods(owner);
        if (declarations == UNREADABLE_CLASSFILE) {
            return false;
        }
        Integer access = declarations.get(method.getName() + Type.getMethodDescriptor(method));
        if (access == null) {
            return null;
        }
        return Modifier.isPublic(access) && !Modifier.isAbstract(access)
                && !Modifier.isStatic(access) && descriptorTypesMatch(owner, method);
    }

    /**
     * Method name plus descriptor mapped to the access flags of the verified class file of
     * {@code owner}, or {@link #UNREADABLE_CLASSFILE}. Parsed once per class: this runs for every
     * abstract method of every class whose reflective method table cannot be linked, and a
     * hierarchy such as Xerces' HTML DOM has hundreds of those, each with a large class file.
     * Values are plain integers, so the weak keys are not kept alive by the cache.
     */
    private static Map<String, Integer> getClassfileMethods(Class<?> owner) {
        synchronized (classfileMethodsCache) {
            Map<String, Integer> cached = classfileMethodsCache.get(owner);
            if (cached != null) {
                return cached;
            }
        }
        Map<String, Integer> result = readClassfileMethods(owner);
        synchronized (classfileMethodsCache) {
            classfileMethodsCache.put(owner, result);
        }
        return result;
    }

    private static final Map<String, Integer> UNREADABLE_CLASSFILE = Collections.unmodifiableMap(new HashMap<>());

    private static final Map<Class<?>, Map<String, Integer>> classfileMethodsCache = new WeakHashMap<>();

    private static Map<String, Integer> readClassfileMethods(Class<?> owner) {
        String resourceName = owner.getName().replace('.', '/') + ".class";
        try {
            URL resource = owner.getResource("/" + resourceName);
            if (resource == null || !matchesDefiningSource(owner, resource, resourceName)) {
                return UNREADABLE_CLASSFILE;
            }
            try (InputStream stream = resource.openStream()) {
                ClassReader reader = new ClassReader(stream);
                if (!reader.getClassName().equals(owner.getName().replace('.', '/'))) {
                    return UNREADABLE_CLASSFILE;
                }
                final Map<String, Integer> declarations = new HashMap<>();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String signature, String[] exceptions) {
                        declarations.put(name + desc, access);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return declarations;
            }
        } catch (Throwable t) {
            logger.debug("Cannot inspect classfile methods of " + owner.getName() + ": " + t);
            return UNREADABLE_CLASSFILE;
        }
    }

    private static boolean matchesDefiningSource(Class<?> owner, URL resource, String resourceName)
            throws java.io.IOException {
        CodeSource source = owner.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return false;
        }
        String location = source.getLocation().toExternalForm();
        // InstrumentingClassLoader records the exact class resource; URLClassLoader records its root.
        if (location.equals(resource.toExternalForm())) {
            return true;
        }
        if ("jar".equals(resource.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            return location.equals(connection.getJarFileURL().toExternalForm());
        }
        return "file".equals(resource.getProtocol()) && "file".equals(source.getLocation().getProtocol())
                && location.endsWith("/")
                && new URL(source.getLocation(), resourceName).toExternalForm().equals(resource.toExternalForm());
    }

    private static boolean descriptorTypesMatch(Class<?> owner, Method method) {
        try {
            if (!descriptorTypeMatches(owner, method.getReturnType())) {
                return false;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (!descriptorTypeMatches(owner, parameter)) {
                    return false;
                }
            }
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean descriptorTypeMatches(Class<?> owner, Class<?> type) throws ClassNotFoundException {
        return type.isPrimitive() || Class.forName(type.getName(), false, owner.getClassLoader()) == type;
    }

    private static boolean isConcretePublicMethod(Method method) {
        int modifiers = method.getModifiers();
        return Modifier.isPublic(modifiers) && !Modifier.isAbstract(modifiers)
                && !Modifier.isStatic(modifiers);
    }

    private static boolean matchesDescriptor(Method candidate, Method method) {
        return candidate.getName().equals(method.getName())
                && candidate.getReturnType().equals(method.getReturnType())
                && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes());
    }

    /** First class-file major version whose source level allows static interface method calls (Java 8). */
    static final int JAVA_8_CLASS_FILE_MAJOR = 52;

    private static Integer targetMajorVersionOverride;
    private static String cachedTargetMajorClass;
    private static int cachedTargetMajor = -1;

    static void setTargetClassFileMajorVersionForTesting(Integer major) {
        targetMajorVersionOverride = major;
    }

    /**
     * Reads the class-file major version of the class under test, or -1 if it cannot be determined.
     * Suites for projects compiled for an older source level (for example -source 6) are compiled at that level,
     * where a call to a static interface method is an error.
     */
    static int targetClassFileMajorVersion() {
        if (targetMajorVersionOverride != null) {
            return targetMajorVersionOverride;
        }
        String target = Properties.TARGET_CLASS;
        if (target == null || target.isEmpty()) {
            return -1;
        }
        synchronized (TestUsageChecker.class) {
            if (target.equals(cachedTargetMajorClass)) {
                return cachedTargetMajor;
            }
            int major = -1;
            try {
                major = readClassFileMajorVersion(
                        org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT(), target);
            } catch (Throwable t) {
                logger.debug("Cannot determine class-file version of " + target + ": " + t);
            }
            cachedTargetMajorClass = target;
            cachedTargetMajor = major;
            return major;
        }
    }

    static int readClassFileMajorVersion(ClassLoader loader, String className) throws java.io.IOException {
        try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (in == null) {
                return -1;
            }
            java.io.DataInputStream data = new java.io.DataInputStream(in);
            if (data.readInt() != 0xCAFEBABE) {
                return -1;
            }
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }

    private static boolean isStaticInterfaceMethodBeyondTargetLevel(Method m) {
        if (!m.getDeclaringClass().isInterface() || !Modifier.isStatic(m.getModifiers())) {
            return false;
        }
        int major = targetClassFileMajorVersion();
        return major > 0 && major < JAVA_8_CLASS_FILE_MAJOR;
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

        if (isNotCallableFromSource(m)) {
            logger.debug("Excluding method that source code cannot call: " + m);
            return false;
        }

        if (!isValidJavaIdentifier(m.getName())) {
            logger.debug("Excluding method with non-Java identifier name: " + m.getName());
            return false;
        }

        if (isCompilerGeneratedEnumMethod(m)) {
            logger.debug("Excluding compiler-generated enum method: " + m);
            return false;
        }

        if (isStaticInterfaceMethodBeyondTargetLevel(m)) {
            logger.debug("Excluding static interface method, which a pre-Java-8 target cannot call: " + m);
            return false;
        }

        if (!Properties.USE_DEPRECATED && m.isAnnotationPresent(Deprecated.class)
                && !isExemptFromDeprecationFilter(m.getDeclaringClass())) {
            logger.debug("Excluding deprecated method " + m.getName());
            return false;
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

        if (isUnstableGuiAccessor(m)) {
            return false;
        }

        // EvoSuite can't deal properly with the static factory methods added to the
        // JDK collection interfaces in Java 9+ (List/Set/Map .of(...)/copyOf(...)/
        // ofEntries(...)/entry(...), and the List.ofLazy(...) preview API). These are
        // *static interface methods*: invoking them requires -source 8 or higher, so
        // any generated test that uses one fails to compile against the many Defects4J
        // targets built at -source 6/7. Exclude them regardless of the running JVM.
        if (Modifier.isStatic(m.getModifiers())
                && (m.getDeclaringClass().equals(java.util.List.class)
                        || m.getDeclaringClass().equals(java.util.Set.class)
                        || m.getDeclaringClass().equals(java.util.Map.class))) {
            return false;
        }

        if (isUnboundedStreamFactory(m)) {
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

        // A class compiled against a leaner version of an interface than the one it is now
        // loaded against can have its method list contain that interface's own abstract
        // declaration as if it were a method of the concrete class: nothing in the hierarchy
        // actually implements it, so invoking it would throw AbstractMethodError. But an abstract
        // Method object on a concrete ownerClass is not by itself proof of that: the cluster's
        // TestClusterUtils.getMethods lets an interface's declaration replace an implementation
        // inherited from a superclass (class C extends Base implements I, with Base.m()
        // implementing I.m()). Such a Method is still invocable, because the call dispatches
        // virtually to Base.m(). So reject only when the owner's public methods contain no
        // concrete method with this name and parameter types. On an interface or abstract
        // ownerClass an abstract method is legitimately usable (mocked, or routed to a concrete
        // subtype).
        if (Modifier.isAbstract(m.getModifiers())
                && ownerClass != null
                && !ownerClass.isInterface()
                && !Modifier.isAbstract(ownerClass.getModifiers())
                && !hasConcreteImplementation(ownerClass, m)) {
            return false;
        }

        if (!isVisibleFromTestPackage(m.getDeclaringClass(), m.getModifiers())) {
            return false;
        }
        if (!Modifier.isPublic(m.getModifiers())) {
            // A non-public method is only reachable through an owner in its own package.
            if (!ClassUtils.getPackageName(ownerClass).equals(ClassUtils.getPackageName(m.getDeclaringClass()))) {
                return false;
            }
            // A default-access abstract method is only skipped for ordinary classes, where the
            // implementation may live in a subclass outside the package. The constants of an
            // enum with constant-specific bodies are nested in the enum itself, and such an
            // abstract method is often its only entry point (a state-machine enum, for example).
            if (Modifier.isAbstract(m.getModifiers()) && !m.getDeclaringClass().isEnum()) {
                return false;
            }
        }
        TestClusterUtils.makeAccessible(m);
        return true;
    }

    /**
     * GUI accessors inherited from JDK AWT/Swing base classes are highly environment-sensitive:
     * they depend on headless state, Look&Feel initialization, windowing backends, and component
     * realization. Treat them as unusable test calls to avoid brittle tests on GUI subclasses such
     * as Plot2D/VisualizePanel that merely inherit these accessors from Component/Container.
     */
    public static boolean isUnstableGuiAccessor(Method method) {
        if (method == null || method.getParameterCount() != 0) {
            return false;
        }

        Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass == null) {
            return false;
        }

        String className = declaringClass.getName();
        if (!className.startsWith("java.awt.") && !className.startsWith("javax.swing.")) {
            return false;
        }

        try {
            if (!java.awt.Component.class.isAssignableFrom(declaringClass)) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        return UNSTABLE_GUI_ACCESSORS.contains(method.getName());
    }

    private static boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
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
     * Whether javac cannot resolve the method from source: bridges, synthetic members (access$NNN
     * stubs, lambda bodies) and accessor stubs whose modifiers instrumentation may have widened.
     * Such a method must never be rendered, whatever its visibility. So must a method with a
     * parameter of a source-private type (or an array of one): no argument for it can be written,
     * because a test can neither name the type nor cast to it.
     */
    public static boolean isNotCallableFromSource(Method m) {
        return m.isSynthetic() || m.isBridge()
                || COMPILER_ACCESSOR_NAME.matcher(m.getName()).matches()
                || hasSourcePrivateParameterType(m);
    }

    private static boolean hasSourcePrivateParameterType(Method m) {
        return hasSourcePrivateParameterType(m.getParameterTypes());
    }

    private static boolean hasSourcePrivateParameterType(Class<?>[] parameterTypes) {
        for (Class<?> parameter : parameterTypes) {
            Class<?> element = parameter;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            for (Class<?> current = element; current != null; current = getDeclaringClassSafely(current)) {
                if (current == UNRESOLVABLE_OWNER_CLASS) {
                    break;
                }
                if (Modifier.isPrivate(getSourceModifiers(current))) {
                    return true;
                }
            }
        }
        return false;
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
     * Stream.iterate(...) and Stream.generate(...) produce unbounded streams.
     * Generated tests often pass those streams to SUT methods that consume the
     * whole stream (e.g. collect/toArray), which can stall or exhaust memory.
     * Finite stream factories such as Stream.of(...) remain usable.
     */
    private static boolean isUnboundedStreamFactory(Method m) {
        if (!Modifier.isStatic(m.getModifiers())) {
            return false;
        }

        Class<?> declaringClass = m.getDeclaringClass();
        if (!declaringClass.equals(java.util.stream.Stream.class)
                && !declaringClass.equals(java.util.stream.IntStream.class)
                && !declaringClass.equals(java.util.stream.LongStream.class)
                && !declaringClass.equals(java.util.stream.DoubleStream.class)) {
            return false;
        }

        String name = m.getName();
        return "iterate".equals(name) || "generate".equals(name);
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
